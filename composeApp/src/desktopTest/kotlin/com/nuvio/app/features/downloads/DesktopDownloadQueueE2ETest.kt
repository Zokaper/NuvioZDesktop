package com.nuvio.app.features.downloads

import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.streams.StreamBehaviorHints
import com.nuvio.app.features.streams.StreamItem
import java.io.File
import java.net.URI
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
    private lateinit var defaultResolver: suspend (StreamItem, Int?, Int?) -> StreamItem?

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
        DownloadsRepository.resolvePlayableStream = { stream, _, _ ->
            stream.copy(url = server.urlFor(freshPath))
        }

        enqueue(episode, withOrigin = true)
        awaitQueueDrained()

        val item = itemFor(episode)
        assertEquals(DownloadStatus.Completed, item.status)
        assertContentOnDisk(item, episode.content)
        assertTrue(server.requestCount(freshPath) > 0, "the re-minted link was never used")
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
     * running past the fifteen-minute link window is what exercises re-minting for
     * real.
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

    private fun publishEpisode(number: Int): Episode {
        val path = "/media/s01e%02d.mp4".format(number)
        // Above the "no real episode is this small" floor the placeholder check uses,
        // and large enough that a transfer is still running when a fault fires.
        val content = ByteArray(EPISODE_BYTES) { index -> ((index + number) % 251).toByte() }
        server.publish(path, content)
        return Episode(number, path, content)
    }

    private fun enqueue(episode: Episode, withOrigin: Boolean = false) {
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
            sourceUrlResolvedAtEpochMs = if (withOrigin) DownloadsClock.nowEpochMs() else null,
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

    private fun itemFor(episode: Episode): DownloadItem =
        DownloadsRepository.uiState.value.items.firstOrNull { it.episodeNumber == episode.number }
            ?: fail("no download for ${episode.path}")

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
        const val POLL_INTERVAL_MS = 100L
        const val WATCH_INTERVAL_MS = 25L
        const val REAL_SOURCE_URLS_ENV = "NUVIO_DOWNLOAD_TEST_URLS"
        const val REAL_SOURCE_TIMEOUT_MS = 30L * 60L * 1000L
    }
}
