package com.nuvio.app.features.reaction

import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Small grey frames decoded from a video by a windowless libmpv in encode mode, on a fixed grid:
 * frame k of a run is at exactly gridIndex * (1000 / fps) ms of the video's own clock.
 *
 * Measured, and the reasons this looks the way it does:
 * - No `setpts=PTS-STARTPTS`. mpv drops frames earlier than `start` *after* the filter chain, so a
 *   rebased chain made every mid-video run begin at twice the requested position (start=137 s
 *   delivered frames from 274 s). With the timestamps left alone, a run from any grid point gives
 *   frames pixel-identical to a run from zero.
 * - The output is cut into 4 s parts (ffmpeg's segment muxer; every raw frame is a keyframe, so the
 *   cuts are exact) that are read and deleted as they fill. One raw file of a feature-length
 *   compilation at 480x270 would have reached gigabytes before analysis finished.
 * - YouTube is not read through here directly: streamed DASH timestamps made the fps filter pad, and
 *   the stream arrives at about real time. Web reactions are downloaded first (ReactionDownloads).
 */
internal object ReactionFrames {
    suspend fun read(
        target: String,
        extraOptions: List<String>,
        width: Int,
        height: Int,
        fps: Int,
        fromIndex: Int,
        /** Stop before this grid index (null: the end of the video). */
        toIndex: Int? = null,
        /** True while decoding should hold (the main player is dropping frames). */
        holdWhile: () -> Boolean = { false },
        onFrame: (index: Int, frame: ByteArray) -> Unit,
    ): Unit = withContext(Dispatchers.IO) {
        val dir = DesktopStorage.cacheDir.resolve("reaction").resolve("frames").resolve("run-${System.nanoTime()}").toFile()
        dir.mkdirs()
        val startSeconds = fromIndex.toDouble() / fps
        val options = extraOptions + listOf(
            "o=" + File(dir, "part-%05d.gray").absolutePath,
            "of=segment",
            "ofopts=segment_format=rawvideo,segment_time=4",
            "ovc=rawvideo",
            "vf=lavfi=[fps=$fps,scale=$width:$height,format=gray]",
            "audio=no",
            "sub=no",
            "keep-open=no",
            "start=" + String.format(Locale.ROOT, "%.4f", startSeconds),
        ) + listOfNotNull(toIndex?.let { "end=" + String.format(Locale.ROOT, "%.4f", it.toDouble() / fps) })
        val handle = ReactionNative.createHeadlessStage(options.toTypedArray())
        val frameBytes = width * height
        val buffer = ByteArray(frameBytes)
        var part = 0
        var offset = 0L
        var index = fromIndex
        var finished = false
        var held = false
        var lastProgress = System.currentTimeMillis()
        try {
            ReactionNative.stageCommand(handle, arrayOf("loadfile", target))
            while (true) {
                coroutineContext.ensureActive()
                for (event in ReactionNative.stageTakeEvents(handle).lines()) {
                    if (!event.startsWith("end-file")) continue
                    finished = true
                    if (event.split(' ').getOrNull(1)?.toIntOrNull() == 4) error("the video could not be read for analysis")
                }
                val hold = holdWhile()
                if (hold != held) {
                    held = hold
                    ReactionNative.stageSetProperty(handle, "pause", if (hold) "yes" else "no")
                }
                var progressed = false
                while (true) {
                    val current = File(dir, partName(part))
                    if (!current.exists()) break
                    RandomAccessFile(current, "r").use { input ->
                        while (input.length() - offset >= frameBytes) {
                            input.seek(offset)
                            input.readFully(buffer)
                            offset += frameBytes
                            onFrame(index++, buffer.copyOf())
                            progressed = true
                        }
                    }
                    // A part is done once the muxer has started the next one (or the run ended).
                    val next = File(dir, partName(part + 1))
                    if (!next.exists() && !finished) break
                    if (current.length() - offset >= frameBytes) continue
                    current.delete()
                    part++
                    offset = 0
                }
                if (progressed || held) lastProgress = System.currentTimeMillis()
                if (finished && !File(dir, partName(part)).exists()) break
                check(System.currentTimeMillis() - lastProgress < 120_000) { "analysis stalled" }
                if (!progressed) delay(200)
            }
        } finally {
            ReactionNative.destroyStage(handle)
            dir.listFiles()?.forEach { it.delete() }
            dir.delete()
        }
    }

    private fun partName(part: Int) = String.format(Locale.ROOT, "part-%05d.gray", part)

    /** Halves a grey frame in both directions (2x2 average). */
    fun halve(frame: ByteArray, width: Int, height: Int): ByteArray {
        val w = width / 2
        val h = height / 2
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * 2) * width + x * 2
            out[y * w + x] = (((frame[i].toInt() and 0xFF) + (frame[i + 1].toInt() and 0xFF) +
                (frame[i + width].toInt() and 0xFF) + (frame[i + width + 1].toInt() and 0xFF)) / 4).toByte()
        }
        return out
    }
}
