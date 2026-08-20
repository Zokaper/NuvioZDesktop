package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.debug.selftest.SelfTestRedaction
import com.nuvio.app.features.debrid.DirectDebridPlayableResult
import com.nuvio.app.features.debrid.DirectDebridPlaybackResolver
import com.nuvio.app.features.streams.StreamItem
import java.net.HttpURLConnection
import java.net.URI

/**
 * Turning a listed source into a link that serves bytes.
 *
 * This is the step that fails most often in the wild and is least visible when it does: a source
 * appears in the list, the user taps it, and the player shows a black screen. The nine debrid tests
 * in `commonTest` all run against fake HTTP, so none of them can tell whether *this* account, with
 * *this* provider, right now, will mint a working link.
 *
 * Three things are checked, in order of how badly each has behaved:
 *
 * 1. The mint itself, with `forceRefresh = true` - the resolver keeps a 15-minute cache, and a
 *    check that silently reads it would pass on a provider that has been down for ten minutes.
 * 2. The link actually **serves bytes**. A 200 with an empty body, or a 40-byte placeholder where a
 *    film should be, is a documented debrid failure mode and is indistinguishable from success
 *    until something tries to read it.
 * 3. A second mint of the same source, which is the re-mint path that expiry depends on.
 *
 * The distinction between `NotCached`, `Stale`, `MissingApiKey` and `Error` is preserved rather
 * than collapsed into "failed". They mean completely different things - one is the provider saying
 * no, one is the app's own state being wrong - and a report that flattens them sends the reader to
 * the wrong place.
 */
internal object S2Debrid {

    private const val PROBE_BYTES = 1L shl 20
    private const val PROBE_TIMEOUT_MS = 30_000

    suspend fun run(context: SelfTestContext) {
        context.check("S2.1", "A source resolves to a playable link") { scope ->
            if (!context.debridConfigured) scope.skip("No validated debrid provider - see S0.2.")
            val candidates = (context.episodeStreams + context.filmStreams)
                .filter(DirectDebridPlaybackResolver::shouldResolveToPlayableStream)
            if (context.episodeStreams.isEmpty() && context.filmStreams.isEmpty()) {
                scope.skip("No sources were found - see S1.3 and S1.4.")
            }
            if (candidates.isEmpty()) {
                // Not a failure. A direct-HTTP addon set needs no debrid resolve at all, and the
                // suite carries on to play whatever is directly playable.
                context.playableStream = firstDirectlyPlayable(context)
                scope.skip(
                    "No source needs a debrid resolve - every result is already a direct link, " +
                        "or none came from an installed debrid addon.",
                )
            }

            scope.value("resolvable candidates", candidates.size)
            var lastResult: String = "none attempted"
            var resolved: StreamItem? = null

            // Walks the ranked head rather than insisting on the single top source: one uncached
            // release is a normal state of the world, not a fault, and stopping there would report
            // a working account as broken.
            for (candidate in candidates.take(MAX_RESOLVE_ATTEMPTS)) {
                val label = SelfTestRedaction.text(candidate.name ?: candidate.title ?: "(unnamed)")
                val season = context.fixtures.seasonNumber.takeIf { candidate in context.episodeStreams }
                val episode = context.fixtures.episodeNumber.takeIf { candidate in context.episodeStreams }
                val started = System.currentTimeMillis()
                val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                    stream = candidate,
                    season = season,
                    episode = episode,
                    // ⚠ Without this the 15-minute resolve cache answers and the provider is never
                    // contacted, which is the opposite of what this check is for.
                    forceRefresh = true,
                )
                val elapsed = System.currentTimeMillis() - started
                lastResult = describe(result)
                scope.value("  $label", "$lastResult in ${elapsed}ms")
                if (result is DirectDebridPlayableResult.Success) {
                    resolved = result.stream
                    break
                }
            }

            if (resolved == null) {
                scope.fail("No candidate resolved. Last result: $lastResult.")
            }
            context.playableStream = resolved
            scope.redactedValue("resolved url", SelfTestRedaction.streamUrl(resolved.url.orEmpty()))
            scope.summary = "Resolved to a playable link."
        }

        context.check("S2.2", "The minted link serves bytes") { scope ->
            val url = context.playableStream?.playableDirectUrl
                ?: scope.skip("Nothing resolved to a direct URL - see S2.1.")

            val probe = rangeProbe(url)
            scope.redactedValue("url", SelfTestRedaction.streamUrl(url))
            scope.value("status", probe.status)
            scope.value("bytes read", probe.bytesRead)
            scope.value("content-type", probe.contentType ?: "(none)")
            scope.value("content-length", probe.contentLength?.toString() ?: "(none)")
            scope.value("ms to first byte", probe.firstByteMs)
            scope.value("throughput Mb/s", "%.1f".format(probe.megabitsPerSecond))
            probe.error?.let { scope.value("error", SelfTestRedaction.text(it)) }

            scope.require(probe.status in 200..299) {
                "The provider minted a link and then answered ${probe.status} for it."
            }
            // The floor is the point. A body that arrives but is tiny is the "placeholder video"
            // failure the download stack has a whole `FaultyMediaServer` behaviour for, and it
            // looks exactly like success from a status code.
            scope.require(probe.bytesRead >= MINIMUM_CREDIBLE_BYTES) {
                "Only ${probe.bytesRead} bytes came back. A link that returns almost nothing is " +
                    "the placeholder case, not a working stream."
            }
            scope.summary = "${probe.bytesRead} bytes at %.1f Mb/s, first byte in ${probe.firstByteMs}ms"
                .format(probe.megabitsPerSecond)
        }

        context.check("S2.3", "The same source re-mints") { scope ->
            val stream = context.playableStream ?: scope.skip("Nothing resolved - see S2.1.")
            val original = context.filmStreams.plus(context.episodeStreams)
                .firstOrNull { DirectDebridPlaybackResolver.shouldResolveToPlayableStream(it) }
                ?: scope.skip("No source needs a debrid resolve on this install.")

            // Expiry handling depends on this working every time, not once. A provider that mints
            // one link per source per session is a real failure mode and the user sees it as a
            // stream that plays now and not tomorrow.
            val result = DirectDebridPlaybackResolver.resolveToPlayableStream(
                stream = original,
                season = null,
                episode = null,
                forceRefresh = true,
            )
            scope.value("result", describe(result))
            if (result !is DirectDebridPlayableResult.Success) {
                scope.fail("The second mint of the same source returned ${describe(result)}.")
            }
            val second = result.stream.url.orEmpty()
            scope.redactedValue("second url", SelfTestRedaction.streamUrl(second))
            scope.value("differs from first", second != stream.url)
            scope.summary = "Re-minted."
        }
    }

    private fun firstDirectlyPlayable(context: SelfTestContext): StreamItem? =
        (context.episodeStreams + context.filmStreams).firstOrNull { it.playableDirectUrl != null }

    private fun describe(result: DirectDebridPlayableResult): String = when (result) {
        is DirectDebridPlayableResult.Success -> "Success"
        DirectDebridPlayableResult.MissingApiKey -> "MissingApiKey (the app has no key for this provider)"
        DirectDebridPlayableResult.NotCached -> "NotCached (the provider does not hold this release)"
        DirectDebridPlayableResult.Stale -> "Stale (the app declined to resolve this source)"
        DirectDebridPlayableResult.Error -> "Error (the provider call failed)"
    }

    /**
     * Reads the first megabyte and times it.
     *
     * A `Range` request rather than a plain GET: the target is a film, and the check is whether
     * bytes flow, not whether the whole thing downloads. Servers that ignore `Range` answer 200 and
     * are handled by simply stopping the read.
     */
    private fun rangeProbe(url: String): ProbeResult {
        val started = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        return try {
            connection = (URI(url).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Range", "bytes=0-${PROBE_BYTES - 1}")
                connectTimeout = PROBE_TIMEOUT_MS
                readTimeout = PROBE_TIMEOUT_MS
                instanceFollowRedirects = true
            }
            val status = connection.responseCode
            var firstByteMs = -1L
            var read = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(64 * 1024)
                while (read < PROBE_BYTES) {
                    val count = input.read(buffer)
                    if (count <= 0) break
                    if (firstByteMs < 0) firstByteMs = System.currentTimeMillis() - started
                    read += count
                }
            }
            val elapsed = (System.currentTimeMillis() - started).coerceAtLeast(1)
            ProbeResult(
                status = status,
                bytesRead = read,
                firstByteMs = if (firstByteMs < 0) elapsed else firstByteMs,
                megabitsPerSecond = read * 8.0 / 1_000.0 / elapsed,
                contentType = connection.contentType,
                contentLength = connection.getHeaderField("Content-Range")?.substringAfterLast('/')
                    ?.toLongOrNull() ?: connection.contentLengthLong.takeIf { it >= 0 },
            )
        } catch (error: Exception) {
            ProbeResult(
                status = connection?.let { runCatching { it.responseCode }.getOrDefault(-1) } ?: -1,
                bytesRead = 0,
                firstByteMs = System.currentTimeMillis() - started,
                megabitsPerSecond = 0.0,
                contentType = null,
                contentLength = null,
                error = "${error::class.simpleName}: ${error.message}",
            )
        } finally {
            runCatching { connection?.disconnect() }
        }
    }

    private data class ProbeResult(
        val status: Int,
        val bytesRead: Long,
        val firstByteMs: Long,
        val megabitsPerSecond: Double,
        val contentType: String?,
        val contentLength: Long?,
        val error: String? = null,
    )

    private const val MAX_RESOLVE_ATTEMPTS = 5
    private const val MINIMUM_CREDIBLE_BYTES = 256L * 1024
}
