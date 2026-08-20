package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.debug.SelfTestHooks
import com.nuvio.app.core.debug.selftest.CheckScope
import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.debug.selftest.SelfTestRedaction
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.player.desktop.NativeMpvDiagnostics
import com.nuvio.app.features.player.desktop.NativePlayerDiagnosticsRegistry
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.streams.StreamItem
import com.nuvio.app.navigation.PlayerRoute
import kotlinx.coroutines.delay

/**
 * Actually playing a real stream, on this GPU, and looking at what mpv says about it.
 *
 * **This is the suite the whole harness exists for.** Every other check has some offline analogue;
 * this one has none, and cannot. Whether a 4K HEVC remux decodes in hardware on a particular
 * adapter, drops frames, or fills its buffer the way the code assumes is not a property of the
 * source tree - it is a property of the machine, and the only way to find out is to play it there.
 *
 * The stream comes from S2 rather than being chosen here, so a failure means playback, not source
 * selection. Route behaviour - the quality sheet, the progress overlay, the failure chain - is S5's
 * job; this one goes straight to the player so its numbers are about the engine.
 */
internal object S3Playback {

    private const val SAMPLE_INTERVAL_MS = 500L

    suspend fun run(context: SelfTestContext) {
        val stream = context.playableStream
        val url = stream?.playableDirectUrl

        context.check("S3.1", "A real stream reaches first frame") { scope ->
            if (stream == null || url == null) scope.skip("No playable URL was resolved - see S2.1.")
            val navigate = SelfTestHooks.navigate
                ?: scope.skip("The app did not publish a navigator - `App.kt` hook missing.")

            val launchId = PlayerLaunchStore.put(playerLaunch(context, stream, url))
            val started = System.currentTimeMillis()
            navigate(PlayerRoute(launchId))

            // The desktop surface is deliberately held at 1dp until its first paint and only then
            // grown, and the native handle is created off the EDT after that. "Playing" here means
            // mpv reported a real position, which is the earliest honest signal.
            val timeoutMs = context.fixtures.firstFrameTimeoutSeconds * 1_000L
            var diagnostics: NativeMpvDiagnostics? = null
            while (System.currentTimeMillis() - started < timeoutMs) {
                delay(SAMPLE_INTERVAL_MS)
                val current = NativePlayerDiagnosticsRegistry.snapshot()
                if (current != null && current.videoWidth > 0) {
                    diagnostics = current
                    break
                }
            }
            val firstFrameMs = System.currentTimeMillis() - started
            scope.redactedValue("url", SelfTestRedaction.streamUrl(url))
            scope.value("time to first frame ms", firstFrameMs)

            if (diagnostics == null) {
                scope.screenshot(context.capture.both("S3.1-no-first-frame"))
                scope.fail(
                    "No decoded frame after ${firstFrameMs}ms. mpv " +
                        (if (NativePlayerDiagnosticsRegistry.current == null) {
                            "never created a player handle at all"
                        } else {
                            "created a handle but reported no video dimensions"
                        }) + ".",
                )
            }

            recordDiagnostics(scope, diagnostics)
            scope.screenshot(context.capture.both("S3.1-first-frame"))
            scope.summary = "First frame in ${firstFrameMs}ms, " +
                "${diagnostics.videoWidth}x${diagnostics.videoHeight}, hwdec=${hwdecLabel(diagnostics)}."
        }

        context.observe("S3.2", "Decoder and stream properties") { scope ->
            val diagnostics = NativePlayerDiagnosticsRegistry.snapshot()
                ?: scope.skip("Nothing is playing - see S3.1.")
            recordDiagnostics(scope, diagnostics)

            // Deliberately an observation, not an assertion. Software decoding is a legitimate
            // outcome for some codecs on some adapters, and failing the run for it would make the
            // summary confidently wrong. The *number* is what a person needs; the judgement is
            // theirs.
            val claimed = stream?.behaviorHints?.videoSize
            claimed?.let { scope.value("source claimed size", it) }
            scope.summary = "hwdec=${hwdecLabel(diagnostics)} · ${diagnostics.videoCodec} " +
                "${diagnostics.videoWidth}x${diagnostics.videoHeight} · ${diagnostics.audioCodec}"
        }

        context.check("S3.3", "Playback holds for the watch window") { scope ->
            if (NativePlayerDiagnosticsRegistry.snapshot() == null) {
                scope.skip("Nothing is playing - see S3.1.")
            }
            val watchMs = context.fixtures.playbackWatchSeconds * 1_000L
            val started = System.currentTimeMillis()
            val samples = mutableListOf<Sample>()
            var screenshotsTaken = 0

            // ⚠ **Wall-clock, not sample counts.** Android polls at 250ms and desktop at 500ms, so
            // "twenty samples" silently means two different durations across platforms - a trap
            // `AGENTS.md` names explicitly. Everything below is expressed in elapsed milliseconds.
            while (System.currentTimeMillis() - started < watchMs) {
                delay(SAMPLE_INTERVAL_MS)
                val elapsed = System.currentTimeMillis() - started
                val diagnostics = NativePlayerDiagnosticsRegistry.snapshot() ?: continue
                val controller = NativePlayerDiagnosticsRegistry.current ?: continue
                samples += Sample(elapsed, controller.snapshot().positionMs, diagnostics)

                // Three evenly spaced marks: start, middle, end of the window.
                if (screenshotsTaken < 3 && elapsed >= watchMs / 3 * screenshotsTaken) {
                    scope.screenshot(context.capture.both("S3.3-watch-${screenshotsTaken + 1}"))
                    screenshotsTaken++
                }
            }

            if (samples.size < 3) {
                scope.fail("Only ${samples.size} usable samples in ${watchMs}ms - the player died.")
            }

            val first = samples.first()
            val last = samples.last()
            val positionAdvancedMs = last.positionMs - first.positionMs
            val wallClockMs = last.elapsedMs - first.elapsedMs
            val droppedDelta = last.diagnostics.droppedFrames - first.diagnostics.droppedFrames
            val decoderDroppedDelta =
                last.diagnostics.decoderDroppedFrames - first.diagnostics.decoderDroppedFrames
            val stalls = samples.count { it.diagnostics.pausedForCache }
            val regressions = samples.zipWithNext().count { (a, b) -> b.positionMs < a.positionMs }

            scope.value("samples", samples.size)
            scope.value("wall clock ms", wallClockMs)
            scope.value("position advanced ms", positionAdvancedMs)
            scope.value("advance ratio", "%.2f".format(positionAdvancedMs.toDouble() / wallClockMs))
            scope.value("frames dropped", droppedDelta)
            scope.value("decoder frames dropped", decoderDroppedDelta)
            scope.value("samples paused for cache", stalls)
            scope.value("position regressions", regressions)

            scope.require(positionAdvancedMs > 0) {
                "The position never advanced across ${wallClockMs}ms - the player is open on a " +
                    "frozen stream, which is the case a user reports as \"it just stopped\"."
            }
            scope.require(regressions == 0) {
                "The reported position went backwards $regressions time(s) without a seek."
            }
            // 0.5 rather than something near 1.0: buffering pauses inside the window are normal on
            // a real connection, and a bar set where a good network passes and a mediocre one fails
            // would report the connection rather than the player.
            val ratio = positionAdvancedMs.toDouble() / wallClockMs
            scope.require(ratio >= 0.5) {
                "Playback advanced only ${"%.0f".format(ratio * 100)}% of real time " +
                    "($stalls of ${samples.size} samples were paused for cache)."
            }

            scope.summary = "Advanced ${positionAdvancedMs}ms in ${wallClockMs}ms, " +
                "$droppedDelta dropped, $stalls cache pauses."
        }

        context.check("S3.4", "The buffer reading is ahead of the position and bounded") { scope ->
            val controller = NativePlayerDiagnosticsRegistry.current
                ?: scope.skip("Nothing is playing - see S3.1.")
            val diagnostics = NativePlayerDiagnosticsRegistry.snapshot()
                ?: scope.skip("Nothing is playing - see S3.1.")
            val snapshot = controller.snapshot()
            val aheadMs = snapshot.bufferedPositionMs - snapshot.positionMs

            scope.value("position ms", snapshot.positionMs)
            scope.value("buffered position ms", snapshot.bufferedPositionMs)
            scope.value("buffer ahead ms", aheadMs)
            scope.value("demuxer-cache-time (absolute)", diagnostics.demuxerCacheTime)
            scope.value("demuxer-cache-duration (relative)", diagnostics.demuxerCacheDuration)

            // ⚠ This is the `demuxer-cache-time` trap, pinned on real hardware for the first time.
            // That property is an **absolute stream timestamp**, not a duration ahead of the
            // position; iOS shipped it as a duration and its buffer readout grew all through
            // playback until it was caught. Desktop derives `bufferedPositionMs` as
            // `time-pos + cacheAhead`, so a regression there shows up here as a buffer that is
            // either behind the position or implausibly far ahead of it.
            scope.require(aheadMs >= 0) {
                "The buffer reads ${aheadMs}ms *behind* the position. `bufferedPositionMs` is " +
                    "supposed to be an absolute position ahead of playback."
            }
            scope.require(aheadMs <= MAX_CREDIBLE_BUFFER_AHEAD_MS) {
                "The buffer claims ${aheadMs / 1000}s ahead, past the ${MAX_CREDIBLE_BUFFER_AHEAD_MS / 1000}s " +
                    "ceiling. That is the shape of an absolute timestamp being read as a duration."
            }
            scope.summary = "${aheadMs / 1000}s ahead of the position."
        }
    }

    private fun recordDiagnostics(scope: CheckScope, diagnostics: NativeMpvDiagnostics) {
        scope.value("hwdec", hwdecLabel(diagnostics))
        scope.value("hardware decoding", diagnostics.isHardwareDecoding)
        scope.value("video codec", diagnostics.videoCodec)
        scope.value("audio codec", diagnostics.audioCodec)
        scope.value("decoded size", "${diagnostics.videoWidth}x${diagnostics.videoHeight}")
        scope.value("container fps", diagnostics.containerFps)
        scope.value("estimated output fps", diagnostics.estimatedVfFps)
        scope.value("video bitrate", diagnostics.videoBitrate)
        scope.value("a/v sync", diagnostics.avsync)
        scope.value("mpv version", diagnostics.mpvVersion)
    }

    private fun hwdecLabel(diagnostics: NativeMpvDiagnostics): String =
        diagnostics.hwdec.ifBlank { "no (software)" }

    private fun playerLaunch(context: SelfTestContext, stream: StreamItem, url: String): PlayerLaunch {
        val isEpisode = stream in context.episodeStreams
        val meta = if (isEpisode) context.seriesMeta else context.filmMeta
        return PlayerLaunch(
            profileId = ProfileRepository.activeProfileId,
            title = meta?.name ?: "Self test",
            sourceUrl = url,
            sourceHeaders = stream.behaviorHints.proxyHeaders?.request.orEmpty(),
            sourceResponseHeaders = stream.behaviorHints.proxyHeaders?.response.orEmpty(),
            externalSubtitles = stream.externalSubtitles,
            streamType = stream.streamType,
            seasonNumber = if (isEpisode) context.fixtures.seasonNumber else null,
            episodeNumber = if (isEpisode) context.fixtures.episodeNumber else null,
            streamTitle = stream.name ?: stream.title ?: "Self test source",
            providerName = stream.addonName,
            providerAddonId = stream.addonId,
            contentType = if (isEpisode) "series" else "movie",
            videoId = if (isEpisode) context.episodeVideoId else context.fixtures.filmId,
            parentMetaId = if (isEpisode) context.fixtures.seriesId else context.fixtures.filmId,
            parentMetaType = if (isEpisode) "series" else "movie",
            // ⚠ Deliberately no failure chain. If this source dies the check must **report** that,
            // not quietly swap in a different one and measure whatever played instead.
            autoPickedWithFailureChain = false,
        )
    }

    private data class Sample(
        val elapsedMs: Long,
        val positionMs: Long,
        val diagnostics: NativeMpvDiagnostics,
    )

    /**
     * mpv is configured with `cache-secs=36000` and `demuxer-max-bytes=512MiB`, so a genuinely
     * large read-ahead is possible - but ten minutes ahead of the position on a streamed source is
     * not a buffer, it is a unit confusion.
     */
    private const val MAX_CREDIBLE_BUFFER_AHEAD_MS = 600_000L
}
