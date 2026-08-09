package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.debrid.DebridProviders
import com.nuvio.app.features.debrid.DebridSettingsRepository
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamClientResolve
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.net.URI
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.downloads_error_stalled
import org.jetbrains.compose.resources.getString
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Drives the real download queue against a real HTTP source, on the real desktop
 * downloader.
 *
 * Everything below this test is shipped code: [DownloadsRepository] with its queue,
 * its retry budgets and its reclaim sweep, and the desktop
 * [DownloadsPlatformDownloader] with its stall watchdog, its resume logic and its
 * `.part` files on a real disk. Only the media host is a stand-in, and it is one
 * because the faults that matter are things a *server* does - see [FaultyMediaServer].
 *
 * This exists because every download fault so far has been found the same way: start
 * a real batch, watch it, notice a row that stopped moving, guess. The queue's
 * recovery paths - a source that goes quiet, a transfer the queue lost, a link that
 * expired before its turn came - could not be reached from a unit test and were never
 * covered by one, so each fix shipped on an argument rather than on evidence.
 *
 * The deadlines those paths run on are minutes long, so [DownloadsTiming] is turned
 * down to seconds for the duration. Nothing else about the code under test changes.
 */
class DesktopDownloadQueueE2ETest {

    private lateinit var server: FaultyMediaServer
    private lateinit var defaultResolver: suspend (StreamItem, Int?, Int?) -> DownloadSourceResolution

    @BeforeTest
    fun setUp() {
        // Guards against a test run writing into the developer's own Nuvio Z install.
        // The Gradle task points the home directory at the build folder; if that ever
        // stops working, this is where it will be noticed rather than after the fact.
        val storageRoot = DesktopStorage.rootDir.toAbsolutePath().toString()
        assertTrue(
            storageRoot.contains("build") || storageRoot.contains("test"),
            "Desktop tests must not run against the real app data directory: $storageRoot",
        )

        // Through the app's own API rather than by deleting the store, so a test never
        // starts on state the previous one left behind - files and `.part` files
        // included.
        DownloadsRepository.deleteDownloadsForTitle(META_ID)
        DownloadsTiming.stallTimeoutMs = STALL_TIMEOUT_MS
        DownloadsTiming.queueWatchdogTimeoutMs = QUEUE_WATCHDOG_MS
        DownloadsTiming.sourceResolveTimeoutMs = SOURCE_RESOLVE_TIMEOUT_MS
        defaultResolver = DownloadsRepository.resolvePlayableStream
        server = FaultyMediaServer()
    }

    @AfterTest
    fun tearDown() {
        server.close()
        DownloadsRepository.deleteDownloadsForTitle(META_ID)
        DownloadsTiming.reset()
        DownloadsRepository.resolvePlayableStream = defaultResolver
    }

    @Test
    fun `a queue of downloads drains when every source behaves`() {
        val episodes = (1..4).map { publishEpisode(it) }

        episodes.forEach { enqueue(it) }
        awaitQueueDrained()

        episodes.forEach { episode ->
            val item = itemFor(episode)
            assertEquals(DownloadStatus.Completed, item.status, "${episode.path} did not complete")
            assertEquals(episode.content.size.toLong(), item.totalBytes, "${episode.path} recorded the wrong size")
            assertContentOnDisk(item, episode.content)
        }
    }

    @Test
    fun `a source that drops the connection is retried and finishes from its partial file`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = episode.content.size / 3L),
        )

        enqueue(episode)
        awaitQueueDrained()

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Completed, item.status)
        assertContentOnDisk(item, episode.content)
        assertEquals(2, server.requestCount(episode.path), "the retry should have resumed rather than restarted")
    }

    /**
     * The reported Punisher stall: 5.8 of 6.2 GB, "Retrying", it does retry, nothing happens.
     *
     * `attemptCount` used to be zeroed by any forward byte movement, so a source that trickles
     * a little and then drops refreshed its retry budget every cycle. `shouldRetry` never
     * returned false, the row never reached Failed, and it cycled Downloading -> trickle ->
     * drop -> Queued forever. Pause and resume could not clear it either: during the backoff
     * the item is Queued with no handle, so `pauseDownload` has nothing to cancel and
     * `resumeDownload` zeroes `attemptCount` - which is what the loop was already doing to
     * itself.
     *
     * Twelve drops, each a little further along, is that shape: real progress every attempt,
     * never enough to mean the source works.
     */
    @Test
    fun `a source that trickles and drops forever fails instead of retrying forever`() {
        val episode = publishEpisode(1)
        val trickle = (episode.content.size / 64L).coerceAtLeast(1L)
        server.failNextRequests(
            episode.path,
            *Array(12) { index ->
                FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = trickle * (index + 1))
            },
        )

        enqueue(episode)
        awaitCondition(STALLED_GIVE_UP_TIMEOUT_MS, "the download to give up") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }?.status == DownloadStatus.Failed
        }

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Failed, item.status)
        // Not a bare "Download failed": the row has to say something the user can act on.
        assertEquals(
            runBlocking { getString(Res.string.downloads_error_stalled) },
            item.errorMessage,
            "an exhausted download must name what happened",
        )
        // It gave up rather than settling into a slower loop.
        assertNull(item.nextRetryAtEpochMs, "a failed download must not still be counting down")
    }

    @Test
    fun `a stalled download restarts from zero once before giving up`() {
        val episode = publishEpisode(1)
        val trickle = (episode.content.size / 64L).coerceAtLeast(1L)
        server.failNextRequests(
            episode.path,
            *Array(12) { index ->
                FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = trickle * (index + 1))
            },
        )

        enqueue(episode)
        var sawRestart = false
        awaitCondition(
            STALLED_GIVE_UP_TIMEOUT_MS,
            "the download to give up",
            onSample = { items ->
                items.firstOrNull { it.episodeNumber == episode.number }?.let {
                    if (it.restartedFromZero) sawRestart = true
                }
            },
        ) { items ->
            items.firstOrNull { it.episodeNumber == episode.number }?.status == DownloadStatus.Failed
        }

        // A partial file the server will not correctly resume is the likeliest explanation
        // for a stall pinned near the end, so starting over is the last thing worth trying.
        assertTrue(sawRestart, "the download never tried starting over")
        // And exactly once - a restart loop is the same fault wearing a different hat.
        assertTrue(itemFor(episode).restartedFromZero)
        assertTrue(
            server.rangeStarts(episode.path).any { it == 0L },
            "the restart did not fetch from the beginning",
        )
    }

    @Test
    fun `a download that stalls near the end completes once the source recovers`() {
        val episode = publishEpisode(1)
        val trickle = (episode.content.size / 64L).coerceAtLeast(1L)
        // Enough drops to spend the first budget, then a clean serve. The escalation exists
        // to recover downloads, not merely to fail them faster.
        server.failNextRequests(
            episode.path,
            *Array(MAX_DOWNLOAD_ATTEMPTS) { index ->
                FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = trickle * (index + 1))
            },
        )

        enqueue(episode)
        awaitQueueDrained(timeoutMs = STALLED_GIVE_UP_TIMEOUT_MS)

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Completed, item.status)
        assertContentOnDisk(item, episode.content)
    }

    @Test
    fun `a source that goes quiet is given up on and the download still finishes`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.GoSilent(bytesBeforeSilence = episode.content.size / 4L),
        )

        enqueue(episode)
        awaitQueueDrained()

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Completed, item.status)
        assertContentOnDisk(item, episode.content)
    }

    /**
     * The regression this harness was written for.
     *
     * A transfer that is cancelled does not stop the instant it is asked to: the read
     * it is parked in has to end first, and the last thing it reports arrives after
     * the download has already been started again. That report used to be applied to
     * whichever attempt was running by then. It took the live transfer's handle out of
     * the queue - leaving a transfer nothing could pause or cancel, and a slot the
     * queue believed was free - and stamped the item system-paused, which on desktop
     * nothing ever undoes.
     *
     * What that looks like from the outside is the reported symptom exactly: a row
     * that stops moving partway through while the queue carries on to the next one.
     * The download is not dead at that moment - its transfer is still running,
     * unmanaged - so the fault only becomes permanent when that transfer also stops,
     * because a failure reported against an item that is no longer marked downloading
     * is recorded and never retried. Hence the two faults here: one to provoke the
     * reclaim, one to stop the attempt that gets hijacked.
     *
     * A source going quiet is the shortest route to the first: the reclaim sweep
     * cancels the transfer and restarts the item in the same breath, which is exactly
     * the window the stale report lands in.
     */
    @Test
    fun `a transfer the queue reclaims cannot strand the attempt that replaces it`() {
        val stalling = publishEpisode(1)
        val follower = publishEpisode(2)
        // Long enough that the queue's own reclaim sweep, not the transfer's stall
        // watchdog, is what ends the silent transfer - that is the path where the
        // cancelled transfer's last word and its replacement overlap.
        DownloadsTiming.stallTimeoutMs = QUEUE_WATCHDOG_MS * 4
        server.failNextRequests(
            stalling.path,
            FaultyMediaServer.Behavior.GoSilent(bytesBeforeSilence = stalling.content.size / 4L),
            FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = stalling.content.size / 3L),
        )

        val watch = QueueWatch().start()
        enqueue(stalling)
        enqueue(follower)
        try {
            awaitQueueDrained(timeoutMs = 90_000L)
        } finally {
            watch.stop()
        }

        watch.assertNothingWasStranded()
        listOf(stalling, follower).forEach { episode ->
            val item = itemFor(episode)
            assertEquals(DownloadStatus.Completed, item.status, "${episode.path} was left ${statusOf(episode)}")
            assertContentOnDisk(item, episode.content)
        }
    }

    @Test
    fun `an expired link is re-minted before the transfer rather than replayed`() {
        val episode = publishEpisode(1)
        val freshPath = "/fresh/${episode.path.trimStart('/')}"
        server.publish(freshPath, episode.content)
        server.failNextRequests(episode.path, FaultyMediaServer.Behavior.Reject(statusCode = 403))
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            DownloadSourceResolution.Ready(
                if (checks.incrementAndGet() == 1) stream else stream.copy(url = server.urlFor(freshPath)),
            )
        }

        enqueue(episode, withOrigin = true)
        awaitQueueDrained()

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Completed, item.status)
        assertContentOnDisk(item, episode.content)
        assertTrue(server.requestCount(freshPath) > 0, "the re-minted link was never used")
    }

    @Test
    fun `re-minting may fail once and then recover`() {
        val episode = publishEpisode(1)
        val freshPath = "/fresh/${episode.path.trimStart('/')}"
        server.publish(freshPath, episode.content)
        val calls = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            if (calls.incrementAndGet() == 1) {
                DownloadSourceResolution.RetryableFailure("Provider unavailable")
            } else {
                DownloadSourceResolution.Ready(stream.copy(url = server.urlFor(freshPath)))
            }
        }

        enqueue(episode, withOrigin = true, resolvedAtEpochMs = 0L)
        awaitQueueDrained(timeoutMs = 20_000L)

        assertEquals(2, calls.get())
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    @Test
    fun `re-minting that always fails ends with an actionable failure`() {
        val episode = publishEpisode(1)
        DownloadsRepository.resolvePlayableStream = { _, _, _ ->
            DownloadSourceResolution.RetryableFailure("Provider unavailable")
        }

        enqueue(episode, withOrigin = true, resolvedAtEpochMs = 0L)
        awaitCondition(30_000L, "re-mint retries to reach a terminal failure") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Failed && !it.errorMessage.isNullOrBlank() }
                ?: false
        }

        assertEquals(MAX_SOURCE_RERESOLVE_ATTEMPTS + 1, itemFor(episode).attemptCount)
    }

    @Test
    fun `a provider that hangs cannot hold a transfer slot forever`() {
        val episode = publishEpisode(1)
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        DownloadsRepository.resolvePlayableStream = { _, _, _ ->
            entered.countDown()
            release.await()
            DownloadSourceResolution.RetryableFailure("Provider timed out")
        }

        enqueue(episode, withOrigin = true, resolvedAtEpochMs = 0L)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the provider was never called")
        try {
            awaitCondition(10_000L, "the hung provider call to release its queue slot") { items ->
                items.firstOrNull { it.episodeNumber == episode.number }
                    ?.let { it.status != DownloadStatus.Downloading && it.attemptCount > 0 }
                    ?: false
            }
        } finally {
            release.complete(Unit)
        }
    }

    @Test
    fun `a re-minted link for a different file cannot corrupt a partial download`() {
        val episode = publishEpisode(1)
        val replacement = ByteArray(episode.content.size) { index -> ((index + 97) % 251).toByte() }
        val freshPath = "/different/${episode.path.trimStart('/')}"
        server.publish(freshPath, replacement)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = episode.content.size / 3L),
            FaultyMediaServer.Behavior.Reject(statusCode = 403),
        )
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            DownloadSourceResolution.Ready(
                if (checks.incrementAndGet() <= 2) stream else stream.copy(url = server.urlFor(freshPath)),
            )
        }

        enqueue(episode, withOrigin = true)
        awaitQueueDrained(timeoutMs = 30_000L)

        assertContentOnDisk(itemFor(episode), replacement)
        assertTrue(
            server.responseRangeStarts(freshPath).firstOrNull() == 0L,
            "the replacement file was appended to bytes from the expired source",
        )
    }

    @Test
    fun `a re-minted link for a materially truncated file is rejected`() {
        val episode = publishEpisode(1)
        val truncated = episode.content.copyOf(episode.content.size / 2)
        val freshPath = "/truncated/${episode.path.trimStart('/')}"
        server.publish(freshPath, truncated)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = episode.content.size / 3L),
            FaultyMediaServer.Behavior.Reject(statusCode = 403),
        )
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            if (checks.incrementAndGet() <= 2) {
                DownloadSourceResolution.Ready(stream)
            } else {
                DownloadSourceResolution.Ready(
                    stream.copy(
                        url = server.urlFor(freshPath),
                        behaviorHints = stream.behaviorHints.copy(videoSize = truncated.size.toLong()),
                    ),
                )
            }
        }

        enqueue(episode, withOrigin = true)
        awaitCondition(20_000L, "the contradictory provider file size to fail") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Failed && !it.errorMessage.isNullOrBlank() }
                ?: false
        }

        assertEquals(0, server.requestCount(freshPath), "the known-truncated replacement was downloaded")
        assertTrue(itemFor(episode).localFileUri == null)
    }

    @Test
    fun `a link that expires twenty percent into the transfer is re-minted and resumed`() {
        assertMidTransferExpiryAt(0.20)
    }

    @Test
    fun `a link that expires ninety percent into the transfer is re-minted and resumed`() {
        assertMidTransferExpiryAt(0.90)
    }

    @Test
    fun `rate limits and provider server errors recover without stranding the queue`() {
        val rateLimited = publishEpisode(1)
        val unavailable = publishEpisode(2)
        server.failNextRequests(rateLimited.path, FaultyMediaServer.Behavior.Reject(statusCode = 429))
        server.failNextRequests(unavailable.path, FaultyMediaServer.Behavior.Reject(statusCode = 503))

        enqueue(rateLimited)
        enqueue(unavailable)
        awaitQueueDrained(timeoutMs = 30_000L)

        listOf(rateLimited, unavailable).forEach { episode ->
            assertEquals(2, server.requestCount(episode.path))
            assertContentOnDisk(itemFor(episode), episode.content)
        }
    }

    @Test
    fun `pausing while a re-mint is in flight stays user-paused`() {
        val episode = publishEpisode(1)
        val freshPath = "/fresh/${episode.path.trimStart('/')}"
        server.publish(freshPath, episode.content)
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            entered.countDown()
            release.await()
            DownloadSourceResolution.Ready(stream.copy(url = server.urlFor(freshPath)))
        }

        enqueue(episode, withOrigin = true, resolvedAtEpochMs = 0L)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the provider was never called")
        DownloadsRepository.pauseDownload(itemFor(episode).id)
        release.complete(Unit)
        awaitUserPaused(episode)
        Thread.sleep(1_500L)
        awaitUserPaused(episode)
        assertEquals(0, server.requestCount(freshPath), "the cancelled re-mint started a transfer")

        DownloadsRepository.resumeDownload(itemFor(episode).id)
        awaitQueueDrained()
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    @Test
    fun `reordering while a re-mint is in flight cannot strand either attempt`() {
        val first = publishEpisode(1)
        val resolving = publishEpisode(2)
        val promoted = publishEpisode(3)
        val freshPath = "/fresh/${resolving.path.trimStart('/')}"
        server.publish(freshPath, resolving.content)
        server.failNextRequests(first.path, FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L))
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            if (calls.incrementAndGet() == 1) {
                entered.countDown()
                release.await()
            }
            DownloadSourceResolution.Ready(stream.copy(url = server.urlFor(freshPath)))
        }

        enqueue(first)
        enqueue(resolving, withOrigin = true, resolvedAtEpochMs = 0L)
        enqueue(promoted)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the provider was never called")
        DownloadsRepository.moveDownloadToTop(itemFor(promoted).id)
        release.complete(Unit)

        val watch = QueueWatch().start()
        try {
            awaitQueueDrained(timeoutMs = 60_000L)
        } finally {
            watch.stop()
        }
        watch.assertNothingWasStranded()
        listOf(first, resolving, promoted).forEach { assertContentOnDisk(itemFor(it), it.content) }
    }

    @Test
    fun `cancelling while a re-mint is in flight cannot resurrect the row`() {
        val episode = publishEpisode(1)
        val entered = CountDownLatch(1)
        val release = CompletableDeferred<Unit>()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            entered.countDown()
            release.await()
            DownloadSourceResolution.Ready(stream)
        }

        enqueue(episode, withOrigin = true, resolvedAtEpochMs = 0L)
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the provider was never called")
        val id = itemFor(episode).id
        DownloadsRepository.cancelDownload(id)
        release.complete(Unit)
        Thread.sleep(1_500L)

        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == id })
        assertEquals(0, server.requestCount(episode.path), "the cancelled re-mint started a transfer")
    }

    @Test
    fun `provider cache readiness is checked immediately before transfer`() {
        val episode = publishEpisode(1)
        val freshPath = "/verified/${episode.path.trimStart('/')}"
        server.publish(freshPath, episode.content)
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            checks.incrementAndGet()
            DownloadSourceResolution.Ready(stream.copy(url = server.urlFor(freshPath)))
        }

        // The URL is deliberately fresh. Addon-time readiness is not enough: the
        // provider must still be asked now, when the transfer is about to start.
        enqueue(episode, withOrigin = true)
        awaitQueueDrained()

        assertEquals(1, checks.get(), "the queue trusted planning-time cache metadata")
        assertEquals(0, server.requestCount(episode.path), "the unverified URL was used")
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    @Test
    fun `a source evicted from provider cache after planning is not downloaded`() {
        val episode = publishEpisode(1)
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { _, _, _ ->
            checks.incrementAndGet()
            DownloadSourceResolution.NotReady("The file is not cached on the provider yet")
        }

        enqueue(episode, withOrigin = true)
        awaitCondition(10_000L, "the uncached source to wait without transferring") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Queued && it.attemptCount > 0 }
                ?: false
        }

        assertEquals(1, checks.get())
        assertEquals(0, server.requestCount(episode.path), "an uncached source reached the media host")
    }

    @Test
    fun `provider uncertainty waits with a visible reason instead of using an unverified link`() {
        val episode = publishEpisode(1)
        DownloadsRepository.resolvePlayableStream = { _, _, _ ->
            DownloadSourceResolution.RetryableFailure("The provider could not verify this file yet")
        }

        enqueue(episode, withOrigin = true)
        awaitCondition(10_000L, "provider uncertainty to become a named wait") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let {
                    it.status == DownloadStatus.Queued &&
                        it.attemptCount > 0 &&
                        !it.errorMessage.isNullOrBlank()
                }
                ?: false
        }

        assertEquals(0, server.requestCount(episode.path))
    }

    @Test
    fun `a dead provider account fails plainly without touching the media link`() {
        val episode = publishEpisode(1)
        DownloadsRepository.resolvePlayableStream = { _, _, _ ->
            DownloadSourceResolution.FatalFailure("Reconnect the provider account")
        }

        enqueue(episode, withOrigin = true)
        awaitCondition(10_000L, "the dead account to fail") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Failed && it.errorMessage == "Reconnect the provider account" }
                ?: false
        }

        assertEquals(0, server.requestCount(episode.path))
    }

    @Test
    fun `a placeholder that arrives after a successful cache check is still rejected`() {
        val episode = publishEpisode(1)
        val verifiedPath = "/verified/${episode.path.trimStart('/')}"
        server.publish(verifiedPath, episode.content)
        server.failNextRequests(verifiedPath, FaultyMediaServer.Behavior.Placeholder(sizeBytes = 4 * 1024))
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            DownloadSourceResolution.Ready(stream.copy(url = server.urlFor(verifiedPath)))
        }

        enqueue(episode, withOrigin = true)
        awaitCondition(20_000L, "the post-check placeholder to be rejected") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Queued && it.attemptCount > 0 }
                ?: false
        }

        assertEquals(1, server.requestCount(verifiedPath))
        assertTrue(itemFor(episode).localFileUri == null)
    }

    @Test
    fun `a placeholder file is not accepted as the episode`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.Placeholder(sizeBytes = 4 * 1024),
        )

        enqueue(episode)
        // The placeholder retry waits a minute by design, so this only checks that the
        // download was not marked complete and is on its way back round.
        awaitCondition(timeoutMs = 20_000L, description = "the placeholder to be rejected") {
            val item = itemFor(episode)
            item.status == DownloadStatus.Queued && item.attemptCount > 0
        }

        val item = itemFor(episode)
        assertTrue(item.localFileUri == null, "the placeholder file was kept")
    }

    /**
     * The same queue, against whatever URLs are handed to it.
     *
     * Skipped unless `NUVIO_DOWNLOAD_TEST_URLS` is set to a comma-separated list of
     * direct media URLs - a real debrid link is the point, since the provider quirks
     * that have caused every fault so far are the one thing a local server cannot
     * imitate. Two links exercise the concurrency limit; a whole season's worth left
     * running past the fifteen-minute link window still only exercises raw transfer
     * behaviour: these URLs carry no durable provider/hash origin, so they cannot be
     * re-minted. The provider-backed test below covers that path.
     *
     * Nothing here is turned down: the real stall and watchdog deadlines apply, so
     * allow it the time a real download takes.
     */
    @Test
    fun `real sources download end to end`() {
        val urls = System.getenv(REAL_SOURCE_URLS_ENV)
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        if (urls.isEmpty()) {
            println("Skipping: set $REAL_SOURCE_URLS_ENV to a comma-separated list of media URLs to run this.")
            return
        }

        DownloadsTiming.reset()
        val watch = QueueWatch().start()
        urls.forEachIndexed { index, url -> enqueueUrl(url, episodeNumber = index + 1) }
        try {
            awaitQueueDrained(timeoutMs = REAL_SOURCE_TIMEOUT_MS)
        } finally {
            watch.stop()
        }

        watch.assertNothingWasStranded()
        DownloadsRepository.uiState.value.items.forEach { item ->
            assertEquals(DownloadStatus.Completed, item.status, "${item.fileName} did not complete")
            val uri = assertNotNull(item.localFileUri, "${item.fileName} recorded no file")
            val file = File(URI(uri))
            assertTrue(file.exists() && file.length() > 0L, "${file.absolutePath} is missing or empty")
            assertEquals(item.totalBytes, file.length(), "${file.name} is not the size the queue recorded")
        }
    }

    @Test
    fun `no more than the concurrency limit ever transfers at once`() {
        val episodes = (1..5).map { publishEpisode(it) }
        var peak = 0

        episodes.forEach { enqueue(it) }
        awaitQueueDrained(
            onSample = { items ->
                peak = maxOf(peak, items.count { it.status == DownloadStatus.Downloading })
            },
        )

        assertTrue(peak in 1..DownloadsRepository.MAX_CONCURRENT_TRANSFERS, "peak concurrency was $peak")
        assertTrue(
            episodes.all { itemFor(it).status == DownloadStatus.Completed },
            "not every episode completed",
        )
    }

    @Test
    fun `reordering under load preempts and resumes without losing bytes`() {
        val episodes = (1..5).map { publishEpisode(it) }
        episodes.take(2).forEach { episode ->
            server.failNextRequests(
                episode.path,
                FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L),
            )
        }

        episodes.forEach { enqueue(it) }
        episodes.take(2).forEach { awaitProgress(it) }

        DownloadsRepository.moveDownloadUp(itemFor(episodes[4]).id)
        assertQueueOrder(1, 2, 3, 5, 4)
        DownloadsRepository.moveDownloadDown(itemFor(episodes[4]).id)
        assertQueueOrder(1, 2, 3, 4, 5)
        DownloadsRepository.moveDownloadToBottom(itemFor(episodes[2]).id)
        assertQueueOrder(1, 2, 4, 5, 3)

        DownloadsRepository.moveDownloadToTop(itemFor(episodes[4]).id)
        assertQueueOrder(5, 1, 2, 4, 3)
        awaitCondition(10_000L, "the promoted download to start") { items ->
            items.firstOrNull { it.episodeNumber == 5 }?.status == DownloadStatus.Downloading
        }

        awaitQueueDrained(timeoutMs = 90_000L)
        val preempted = episodes[1]
        assertTrue(server.requestCount(preempted.path) >= 2, "the lower-priority transfer was not preempted")
        assertTrue(
            server.rangeStarts(preempted.path).any { it > 0L },
            "the preempted transfer restarted instead of resuming its partial file",
        )
        episodes.forEach { assertContentOnDisk(itemFor(it), it.content) }
    }

    @Test
    fun `a user pause survives queue recovery and restart then resumes from disk`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L),
        )
        enqueue(episode)
        awaitProgress(episode)

        DownloadsRepository.pauseDownload(itemFor(episode).id)
        awaitUserPaused(episode)
        val requestsWhilePaused = server.requestCount(episode.path)

        DownloadsRepository.moveDownloadToTop(itemFor(episode).id)
        DownloadsRepository.resumeSystemPausedDownloads()
        Thread.sleep(QUEUE_WATCHDOG_MS + 500L)
        awaitUserPaused(episode)
        assertEquals(requestsWhilePaused, server.requestCount(episode.path), "recovery restarted a user pause")

        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        awaitUserPaused(episode)
        assertQueueOrder(1)

        DownloadsRepository.resumeDownload(itemFor(episode).id)
        awaitQueueDrained()
        assertTrue(server.rangeStarts(episode.path).any { it > 0L }, "resume did not use the partial file")
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    @Test
    fun `pausing during retry backoff is sticky`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.DropConnection(bytesBeforeDrop = episode.content.size / 3L),
        )
        enqueue(episode)
        awaitCondition(15_000L, "the download to enter retry backoff") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Queued && it.attemptCount == 1 && it.nextRetryAtEpochMs != null }
                ?: false
        }

        DownloadsRepository.pauseDownload(itemFor(episode).id)
        awaitUserPaused(episode)
        val requestsWhilePaused = server.requestCount(episode.path)
        Thread.sleep(3_000L)
        awaitUserPaused(episode)
        assertEquals(requestsWhilePaused, server.requestCount(episode.path), "the retry timer undid a user pause")

        DownloadsRepository.resumeDownload(itemFor(episode).id)
        awaitQueueDrained()
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    @Test
    fun `cancelling the only running download removes its files and does not resurrect it`() {
        val episode = publishEpisode(1)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L),
        )
        enqueue(episode)
        awaitProgress(episode)
        val item = itemFor(episode)

        DownloadsRepository.cancelDownload(item.id)
        awaitCondition(5_000L, "the cancelled row to disappear") { items ->
            items.none { it.id == item.id }
        }
        Thread.sleep(QUEUE_WATCHDOG_MS + 500L)

        assertTrue(DownloadsRepository.uiState.value.items.none { it.id == item.id }, "the cancelled row came back")
        assertEquals(0L, DownloadsPlatformDownloader.partialFileBytes(item.fileName), "the partial file survived cancel")
        assertEquals(
            null,
            DownloadsPlatformDownloader.resolveLocalFileUri(null, item.fileName),
            "a completed file survived cancel",
        )
    }

    @Test
    fun `an active reordered queue reloads in the same order and resumes partial files`() {
        val episodes = (1..4).map { publishEpisode(it) }
        episodes.take(2).forEach { episode ->
            server.failNextRequests(episode.path, FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L))
        }
        episodes.forEach { enqueue(it) }
        episodes.take(2).forEach { awaitProgress(it) }
        DownloadsRepository.moveDownloadUp(itemFor(episodes[3]).id)
        assertQueueOrder(1, 2, 4, 3)

        DownloadsRepository.clearLocalState()
        DownloadsRepository.ensureLoaded()
        assertQueueOrder(1, 2, 4, 3)
        awaitQueueDrained(timeoutMs = 60_000L)

        episodes.take(2).forEach { episode ->
            assertTrue(server.rangeStarts(episode.path).any { it > 0L }, "${episode.path} restarted from zero")
        }
        episodes.forEach { assertContentOnDisk(itemFor(it), it.content) }
    }

    @Test
    fun `deleting a title under load removes every row and partial file`() {
        val episodes = (1..3).map { publishEpisode(it) }
        episodes.take(2).forEach { episode ->
            server.failNextRequests(episode.path, FaultyMediaServer.Behavior.Throttle(delayPerChunkMs = 8L))
        }
        episodes.forEach { enqueue(it) }
        episodes.take(2).forEach { awaitProgress(it) }
        val fileNames = DownloadsRepository.uiState.value.items.map { it.fileName }

        DownloadsRepository.deleteDownloadsForTitle(META_ID)
        awaitCondition(5_000L, "the title deletion to clear the queue") { it.isEmpty() }
        Thread.sleep(QUEUE_WATCHDOG_MS + 500L)

        assertTrue(DownloadsRepository.uiState.value.items.isEmpty(), "a cancelled callback restored a row")
        fileNames.forEach { fileName ->
            assertEquals(0L, DownloadsPlatformDownloader.partialFileBytes(fileName), "$fileName kept a partial")
            assertEquals(null, DownloadsPlatformDownloader.resolveLocalFileUri(null, fileName))
        }
    }

    /**
     * A real TorBox season run, starting from the durable source metadata the app
     * persists rather than from already-minted download URLs.
     *
     * This is deliberately opt-in. [TORBOX_API_KEY_ENV] is read only into the
     * disposable desktop-test profile and is cleared in `finally`; [TORBOX_FIXTURE_ENV]
     * names a local JSON file whose contents are never printed. Preparing every
     * source before enqueueing reproduces a real automatic season batch. Setting
     * `waitAfterPrepareSeconds` above TorBox's signed-link lifetime proves that each
     * queued transfer re-checks the provider and obtains a usable whole-file link.
     */
    @Test
    fun `real TorBox season rechecks and remints every source`() {
        val apiKey = System.getenv(TORBOX_API_KEY_ENV)?.trim().orEmpty()
        val fixturePath = System.getenv(TORBOX_FIXTURE_ENV)?.trim().orEmpty()
        if (apiKey.isEmpty() || fixturePath.isEmpty()) {
            println(
                "Skipping: set $TORBOX_API_KEY_ENV and $TORBOX_FIXTURE_ENV to run the " +
                    "provider-backed TorBox test.",
            )
            return
        }

        val fixtureFile = File(fixturePath)
        assertTrue(fixtureFile.isFile, "$TORBOX_FIXTURE_ENV must name a readable JSON file")
        val fixture = TORBOX_FIXTURE_JSON.decodeFromString<RealTorboxFixture>(fixtureFile.readText())
        fixture.validate()

        DownloadsTiming.reset()
        DebridSettingsRepository.setTorboxApiKey(apiKey)
        try {
            DebridSettingsRepository.setPreferredResolverProviderId(DebridProviders.TORBOX_ID)
            DebridSettingsRepository.setEnabled(true)
            assertEquals(
                DebridProviders.TORBOX_ID,
                DebridSettingsRepository.snapshot().activeResolverProviderId,
                "the disposable test profile did not activate TorBox",
            )

            val providerChecks = AtomicInteger()
            DownloadsRepository.resolvePlayableStream = { stream, season, episode ->
                providerChecks.incrementAndGet()
                defaultResolver(stream, season, episode)
            }

            val prepared = fixture.sources.map { source ->
                val origin = source.toOriginStream()
                val resolved = runBlocking {
                    DirectDebridPlaybackResolver.resolveToPlayableStream(
                        stream = origin,
                        season = source.season,
                        episode = source.episode,
                        forceRefresh = true,
                    )
                }
                val playable = (resolved as? DirectDebridPlayableResult.Success)?.stream
                    ?: fail("TorBox could not prepare fixture episode ${source.episode}: ${resolved::class.simpleName}")
                assertNotNull(playable.playableDirectUrl, "TorBox returned no playable URL for episode ${source.episode}")
                PreparedTorboxSource(source, origin, playable)
            }
            val preparedAt = DownloadsClock.nowEpochMs()

            if (fixture.waitAfterPrepareSeconds > 0L) {
                Thread.sleep(fixture.waitAfterPrepareSeconds * 1_000L)
            }

            val watch = QueueWatch().start()
            prepared.forEach { enqueueTorboxSource(it, preparedAt) }
            try {
                awaitQueueDrained(timeoutMs = fixture.queueTimeoutMinutes * 60_000L)
            } finally {
                watch.stop()
            }

            watch.assertNothingWasStranded()
            assertTrue(
                providerChecks.get() >= fixture.sources.size,
                "every TorBox transfer must perform a fresh provider readiness check",
            )
            DownloadsRepository.uiState.value.items.forEach { item ->
                assertEquals(DownloadStatus.Completed, item.status, "${item.fileName} did not complete")
                val uri = assertNotNull(item.localFileUri, "${item.fileName} recorded no file")
                val file = File(URI(uri))
                assertTrue(file.exists() && file.length() > 0L, "${file.name} is missing or empty")
                assertEquals(item.totalBytes, file.length(), "${file.name} is not the recorded whole file")
                item.expectedSizeBytes?.let { expected ->
                    assertEquals(expected, file.length(), "${file.name} differs from TorBox's prepared size")
                }
            }
        } finally {
            DownloadsRepository.resolvePlayableStream = defaultResolver
            DebridSettingsRepository.setEnabled(false)
            DebridSettingsRepository.setTorboxApiKey("")
        }
    }

    // --- Harness ------------------------------------------------------------------

    /**
     * Watches the queue while a test runs and remembers what it should never have seen.
     *
     * The end state alone is too forgiving: a download whose transfer has been
     * hijacked is still transferring, so it can finish in spite of the fault and hide
     * it. What cannot be explained away is a desktop download sitting in a state only
     * a platform pause is supposed to produce, or more transfers running at once than
     * the queue allows because a slot was released twice.
     */
    private class QueueWatch {
        @Volatile
        private var running = true
        private val stranded = mutableSetOf<String>()
        private var peakConcurrency = 0
        private var watcher: Thread? = null

        fun start(): QueueWatch {
            watcher = kotlin.concurrent.thread(isDaemon = true, name = "queue-watch") {
                while (running) {
                    val items = DownloadsRepository.uiState.value.items
                    synchronized(this) {
                        items.filter { it.isSystemPaused }.forEach { stranded += it.fileName }
                        peakConcurrency = maxOf(
                            peakConcurrency,
                            items.count { it.status == DownloadStatus.Downloading },
                        )
                    }
                    Thread.sleep(WATCH_INTERVAL_MS)
                }
            }
            return this
        }

        fun stop() {
            running = false
            watcher?.join(1_000L)
        }

        fun assertNothingWasStranded() = synchronized(this) {
            assertTrue(
                stranded.isEmpty(),
                "system-paused on desktop, where nothing resumes it: $stranded",
            )
            assertTrue(
                peakConcurrency <= DownloadsRepository.MAX_CONCURRENT_TRANSFERS,
                "$peakConcurrency transfers ran at once, over the limit of " +
                    "${DownloadsRepository.MAX_CONCURRENT_TRANSFERS}",
            )
        }
    }

    private class Episode(val number: Int, val path: String, val content: ByteArray)

    @Serializable
    private data class RealTorboxFixture(
        val waitAfterPrepareSeconds: Long = 0L,
        val queueTimeoutMinutes: Long = 180L,
        val sources: List<RealTorboxSource>,
    ) {
        fun validate() {
            assertTrue(sources.isNotEmpty(), "the TorBox fixture must contain at least one source")
            assertTrue(waitAfterPrepareSeconds in 0L..3_600L, "waitAfterPrepareSeconds must be between 0 and 3600")
            assertTrue(queueTimeoutMinutes in 1L..720L, "queueTimeoutMinutes must be between 1 and 720")
            assertEquals(sources.size, sources.map { it.episode }.distinct().size, "fixture episodes must be unique")
            sources.forEach { source ->
                assertTrue(
                    source.infoHash.length == 32 || source.infoHash.length == 40,
                    "each fixture source needs a 32- or 40-character info hash",
                )
                assertTrue(source.infoHash.all { it.isLetterOrDigit() }, "fixture info hashes must be alphanumeric")
                assertTrue(source.season > 0 && source.episode > 0, "fixture season and episode must be positive")
                assertTrue(source.expectedSizeBytes == null || source.expectedSizeBytes > 0L, "expected sizes must be positive")
            }
        }
    }

    @Serializable
    private data class RealTorboxSource(
        val infoHash: String,
        val fileIdx: Int? = null,
        val filename: String? = null,
        val expectedSizeBytes: Long? = null,
        val season: Int = 1,
        val episode: Int,
        val trackers: List<String> = emptyList(),
    ) {
        fun toOriginStream(): StreamItem = StreamItem(
            name = "TorBox E2E S%02dE%02d".format(season, episode),
            addonName = "TorBox E2E",
            addonId = "addon:torbox-e2e",
            behaviorHints = StreamBehaviorHints(
                videoSize = expectedSizeBytes,
                filename = filename,
            ),
            clientResolve = StreamClientResolve(
                type = "debrid",
                infoHash = infoHash,
                fileIdx = fileIdx,
                sources = trackers.map { tracker ->
                    if (tracker.startsWith("tracker:")) tracker else "tracker:$tracker"
                },
                filename = filename,
                season = season,
                episode = episode,
                service = DebridProviders.TORBOX_ID,
                isCached = true,
            ),
        )
    }

    private data class PreparedTorboxSource(
        val fixture: RealTorboxSource,
        val origin: StreamItem,
        val resolved: StreamItem,
    )

    private fun publishEpisode(number: Int): Episode {
        val path = "/media/s01e%02d.mp4".format(number)
        // Above the "no real episode is this small" floor the placeholder check uses,
        // and large enough that a transfer is still running when a fault fires.
        val content = ByteArray(EPISODE_BYTES) { index -> ((index + number) % 251).toByte() }
        server.publish(path, content)
        return Episode(number, path, content)
    }

    private fun enqueue(
        episode: Episode,
        withOrigin: Boolean = false,
        resolvedAtEpochMs: Long? = if (withOrigin) DownloadsClock.nowEpochMs() else null,
    ) {
        val stream = StreamItem(
            name = "Test source ${episode.number}",
            url = server.urlFor(episode.path),
            addonName = "Harness",
            addonId = "addon:harness",
            behaviorHints = StreamBehaviorHints(videoSize = episode.content.size.toLong()),
        )
        val result = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$META_ID:1:${episode.number}",
            parentMetaId = META_ID,
            parentMetaType = "series",
            title = "Harness",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episode.number,
            episodeTitle = "Episode ${episode.number}",
            episodeThumbnail = null,
            stream = stream,
            sourceOrigin = if (withOrigin) DownloadSourceOrigin(stream, season = 1, episode = episode.number) else null,
            sourceUrlResolvedAtEpochMs = resolvedAtEpochMs,
        )
        assertEquals(DownloadEnqueueResult.Started, result, "${episode.path} was not accepted")
    }

    /** Enqueues a URL the harness did not publish, for the real-source run. */
    private fun enqueueUrl(url: String, episodeNumber: Int) {
        val stream = StreamItem(
            name = "Real source $episodeNumber",
            url = url,
            addonName = "Harness",
            addonId = "addon:harness",
        )
        val result = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$META_ID:1:$episodeNumber",
            parentMetaId = META_ID,
            parentMetaType = "series",
            title = "Harness",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = 1,
            episodeNumber = episodeNumber,
            episodeTitle = "Episode $episodeNumber",
            episodeThumbnail = null,
            stream = stream,
        )
        assertEquals(DownloadEnqueueResult.Started, result, "$url was not accepted")
    }

    private fun enqueueTorboxSource(source: PreparedTorboxSource, preparedAt: Long) {
        val fixture = source.fixture
        val result = DownloadsRepository.enqueueFromStream(
            contentType = "series",
            videoId = "$META_ID:${fixture.season}:${fixture.episode}",
            parentMetaId = META_ID,
            parentMetaType = "series",
            title = "TorBox E2E",
            logo = null,
            poster = null,
            background = null,
            seasonNumber = fixture.season,
            episodeNumber = fixture.episode,
            episodeTitle = "Episode ${fixture.episode}",
            episodeThumbnail = null,
            stream = source.resolved,
            expectedSizeBytes = fixture.expectedSizeBytes ?: source.resolved.behaviorHints.videoSize,
            sourceOrigin = DownloadSourceOrigin(
                stream = source.origin,
                season = fixture.season,
                episode = fixture.episode,
            ),
            sourceUrlResolvedAtEpochMs = preparedAt,
        )
        assertEquals(DownloadEnqueueResult.Started, result, "TorBox episode ${fixture.episode} was not accepted")
    }

    private fun itemFor(episode: Episode): DownloadItem =
        DownloadsRepository.uiState.value.items.firstOrNull { it.episodeNumber == episode.number }
            ?: fail("no download for ${episode.path}")

    private fun awaitProgress(episode: Episode, minimumBytes: Long = 256L * 1024L) {
        awaitCondition(10_000L, "${episode.path} to make progress") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Downloading && it.downloadedBytes >= minimumBytes }
                ?: false
        }
    }

    private fun assertMidTransferExpiryAt(fraction: Double) {
        val episode = publishEpisode(1)
        val freshPath = "/fresh/${episode.path.trimStart('/')}"
        server.publish(freshPath, episode.content)
        server.failNextRequests(
            episode.path,
            FaultyMediaServer.Behavior.DropConnection(
                bytesBeforeDrop = (episode.content.size * fraction).toLong(),
            ),
            FaultyMediaServer.Behavior.Reject(statusCode = 403),
        )
        val checks = AtomicInteger()
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            DownloadSourceResolution.Ready(
                if (checks.incrementAndGet() <= 2) stream else stream.copy(url = server.urlFor(freshPath)),
            )
        }

        enqueue(episode, withOrigin = true)
        awaitQueueDrained(timeoutMs = 30_000L)

        assertTrue(server.rangeStarts(episode.path).any { it > 0L }, "the expired source was never resumed")
        assertTrue(server.rangeStarts(freshPath).any { it > 0L }, "the fresh source did not resume the partial")
        assertContentOnDisk(itemFor(episode), episode.content)
    }

    private fun awaitUserPaused(episode: Episode) {
        awaitCondition(5_000L, "${episode.path} to remain user-paused") { items ->
            items.firstOrNull { it.episodeNumber == episode.number }
                ?.let { it.status == DownloadStatus.Paused && it.pauseReason == DownloadPauseReason.User }
                ?: false
        }
    }

    private fun assertQueueOrder(vararg episodeNumbers: Int) {
        val actual = DownloadsRepository.uiState.value.items
            .filter { it.status != DownloadStatus.Completed }
            .sortedBy { it.queuePosition }
            .mapNotNull { it.episodeNumber }
        assertEquals(episodeNumbers.toList(), actual)
    }

    private fun statusOf(episode: Episode): String {
        val item = itemFor(episode)
        return "${item.status}/${item.pauseReason} at ${item.downloadedBytes} bytes: ${item.errorMessage}"
    }

    private fun assertContentOnDisk(item: DownloadItem, expected: ByteArray) {
        val uri = assertNotNull(item.localFileUri, "no file was recorded for ${item.fileName}")
        val file = File(URI(uri))
        assertTrue(file.exists(), "${file.absolutePath} is missing")
        assertEquals(expected.size.toLong(), file.length(), "${file.name} is the wrong size")
        assertTrue(file.readBytes().contentEquals(expected), "${file.name} does not match what was served")
    }

    private fun awaitQueueDrained(
        timeoutMs: Long = 60_000L,
        onSample: (List<DownloadItem>) -> Unit = {},
    ) {
        awaitCondition(timeoutMs, "the queue to drain", onSample) { items ->
            items.isNotEmpty() && items.all { it.status == DownloadStatus.Completed }
        }
    }

    private fun awaitCondition(
        timeoutMs: Long,
        description: String,
        onSample: (List<DownloadItem>) -> Unit = {},
        condition: (List<DownloadItem>) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val items = DownloadsRepository.uiState.value.items
            onSample(items)
            if (condition(items)) return
            Thread.sleep(POLL_INTERVAL_MS)
        }
        fail(
            "Timed out after ${timeoutMs}ms waiting for $description. Queue was:\n" +
                DownloadsRepository.uiState.value.items.joinToString("\n") { item ->
                    "  ${item.fileName}: ${item.status}/${item.pauseReason} " +
                        "${item.downloadedBytes}/${item.totalBytes} attempts=${item.attemptCount} " +
                        "error=${item.errorMessage}"
                },
        )
    }

    private companion object {
        const val META_ID = "tt-harness"
        const val EPISODE_BYTES = 6 * 1024 * 1024
        const val STALL_TIMEOUT_MS = 3_000L
        const val QUEUE_WATCHDOG_MS = 5_000L
        const val SOURCE_RESOLVE_TIMEOUT_MS = 1_000L
        const val POLL_INTERVAL_MS = 100L

        /**
         * Long enough for two whole retry budgets plus the restart between them.
         *
         * `retryBackoffMs` is real time, not simulated: 2 + 5 + 15 + 30 seconds to spend one
         * budget, a 30 second wait before the from-zero run, then the same again - about 130
         * seconds before a trickling source is allowed to give up. `DownloadsTiming` turns the
         * stall and watchdog deadlines down for the harness but deliberately leaves the retry
         * schedule alone, because the escalation being tested here *is* that schedule.
         */
        const val STALLED_GIVE_UP_TIMEOUT_MS = 240_000L
        const val WATCH_INTERVAL_MS = 25L
        const val REAL_SOURCE_URLS_ENV = "NUVIO_DOWNLOAD_TEST_URLS"
        const val REAL_SOURCE_TIMEOUT_MS = 30L * 60L * 1000L
        const val TORBOX_API_KEY_ENV = "NUVIO_TORBOX_API_KEY"
        const val TORBOX_FIXTURE_ENV = "NUVIO_TORBOX_TEST_SOURCES"
        val TORBOX_FIXTURE_JSON = Json {
            ignoreUnknownKeys = false
            explicitNulls = false
        }
    }
}
