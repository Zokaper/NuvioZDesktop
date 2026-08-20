package com.nuvio.app.features.player.desktop

import co.touchlab.kermit.Logger
import com.nuvio.app.core.debug.isDebugBuild
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

/**
 * What mpv knows about the stream it is playing, and `PlayerPlaybackSnapshot` does not.
 *
 * The shipped snapshot carries position, duration, buffer and speed - enough to draw a seek bar,
 * not enough to answer *"did this decode on the GPU, at the resolution the source claimed, without
 * dropping frames"*. That question can only be answered on real hardware against a real stream, so
 * nothing in either repository could answer it at all until this existed.
 *
 * Read by the debug self-test harness. Everything here is gated on [isDebugBuild]; the native
 * exports behind it are not, because one `player_bridge.cpp` builds both channels.
 */
@Serializable
internal data class NativeMpvDiagnostics(
    /** mpv's `hwdec-current`. Empty or `"no"` means software decoding. */
    val hwdec: String = "",
    val videoCodec: String = "",
    val audioCodec: String = "",
    val mpvVersion: String = "",
    /** The **decoded** dimensions, which is what makes them worth comparing against the source's claim. */
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val containerFps: Double = 0.0,
    val estimatedVfFps: Double = 0.0,
    /** `frame-drop-count` - frames the output dropped, i.e. what the viewer saw as a stutter. */
    val droppedFrames: Long = 0,
    /** `decoder-frame-drop-count` - frames the decoder itself gave up on, a harder failure. */
    val decoderDroppedFrames: Long = 0,
    val videoBitrate: Long = 0,
    val avsync: Double = 0.0,
    /**
     * ⚠ `demuxer-cache-time` is an **absolute stream timestamp**, not a duration ahead of the
     * position. `demuxer-cache-duration` is the duration. They are carried separately and
     * unreduced because conflating them is a fault this project has already shipped once - iOS
     * read the absolute one as a duration and its buffer readout grew all through playback.
     * `-1.0` means mpv did not answer.
     */
    val demuxerCacheTime: Double = -1.0,
    val demuxerCacheDuration: Double = -1.0,
    val pausedForCache: Boolean = false,
    val coreIdle: Boolean = false,
) {
    /** True when mpv reports a real hardware decoder rather than `no`/empty. */
    val isHardwareDecoding: Boolean
        get() = hwdec.isNotBlank() && !hwdec.equals("no", ignoreCase = true)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(raw: String): NativeMpvDiagnostics? =
            runCatching { json.decodeFromString<NativeMpvDiagnostics>(raw) }.getOrNull()
    }
}

/**
 * The live player, published so a harness can reach it without owning `onControllerReady`.
 *
 * The self-test drives playback through the **real** route - `StreamLaunchStore` and the player
 * screen - precisely so that the quality sheet, the progress overlay and the failure chain are
 * exercised rather than bypassed. That route owns the controller callback, so there is no way to
 * be handed the controller from outside; the controller has to announce itself.
 *
 * Populated only in debug builds. In a release build [current] is always null and nothing here
 * runs.
 */
internal object NativePlayerDiagnosticsRegistry {
    private val log = Logger.withTag("NativePlayerDiagnostics")

    @Volatile
    var current: NativePlayerController? = null
        private set

    fun publish(controller: NativePlayerController) {
        if (!isDebugBuild) return
        current = controller
    }

    /**
     * Clears only if [controller] is still the published one.
     *
     * A new player attaches before the old one finishes tearing down - `disposeInFlight` exists
     * for exactly that overlap - so an unconditional clear on dispose would blank the registry
     * just after the *next* player published itself.
     */
    fun clear(controller: NativePlayerController) {
        if (current === controller) current = null
    }

    /** Diagnostics for the live player, or null when nothing is playing. */
    fun snapshot(): NativeMpvDiagnostics? = current?.mpvDiagnostics()

    /**
     * Asks mpv to write the current frame to [target], and waits for it to appear.
     *
     * mpv queues the command and writes on its own thread, so the return of
     * [NativePlayerBridge.screenshotToFile] means nothing about the file. Waits for a **stable**
     * size rather than mere existence: the file is created empty and filled, and a screenshot read
     * back mid-write is a truncated PNG that looks like a decode failure.
     */
    fun writeFrame(target: Path, includeSubtitles: Boolean, timeoutMs: Long = 5_000L): Boolean {
        val controller = current ?: return false
        runCatching { Files.createDirectories(target.parent) }
        if (!controller.requestMpvScreenshot(target.toAbsolutePath().toString(), includeSubtitles)) return false

        val deadline = System.currentTimeMillis() + timeoutMs
        var lastSize = -1L
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(100L)
            val size = runCatching { Files.size(target) }.getOrDefault(-1L)
            if (size > 0L && size == lastSize) return true
            lastSize = size
        }
        log.w { "mpv screenshot did not settle within ${timeoutMs}ms: $target" }
        return false
    }
}
