package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.reaction.ReactionFingerprinter.FRAME_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.min

/**
 * A low-resolution copy of a web reaction, on disk, for analysis. mpv streams YouTube at about real
 * time (measured 1.3-2x), so streaming analysis could only stay a little ahead of playback; yt-dlp's
 * own downloader fetched a 20-minute compilation's 720p video in 9 s. The copy is video only, at most
 * 480p (the detector works at 480x270), and the newest few are kept so a rewatch starts at once.
 */
internal object ReactionDownloads {
    private val log = Logger.withTag("ReactionDownloads")
    private const val KEEP = 6
    const val ANALYSIS_FORMAT = "bv*[height<=480]/b[height<=480]/wv*/w"

    private val dir: File by lazy { DesktopStorage.cacheDir.resolve("reaction").resolve("media").toFile().apply { mkdirs() } }

    suspend fun analysisCopy(url: String, key: String, tools: ReactionTools.Paths, onProgress: (Double) -> Unit): File =
        withContext(Dispatchers.IO) {
            existing(key)?.let { it.setLastModified(System.currentTimeMillis()); return@withContext it }
            val command = listOf(
                tools.ytDlp.absolutePath,
                "--js-runtimes", "deno:${tools.deno.absolutePath}",
                "--no-playlist",
                "-f", ANALYSIS_FORMAT,
                "-o", File(dir, "$key.%(ext)s").absolutePath,
                "--newline", "--progress",
                "--progress-template", "download:progress %(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s",
                "--print", "after_move:filepath",
                url,
            )
            val process = ProcessBuilder(command).redirectErrorStream(true).start()
            var finalPath: String? = null
            val tail = ArrayDeque<String>()
            try {
                runInterruptible {
                    process.inputStream.bufferedReader().useLines { lines ->
                        for (line in lines) {
                            if (line.startsWith("progress ")) {
                                val parts = line.split(' ')
                                val done = parts.getOrNull(1)?.toDoubleOrNull()
                                val total = parts.getOrNull(2)?.toDoubleOrNull() ?: parts.getOrNull(3)?.toDoubleOrNull()
                                if (done != null && total != null && total > 0) onProgress((done / total).coerceIn(0.0, 1.0))
                            } else if (line.isNotBlank()) {
                                if (File(line.trim()).isFile) finalPath = line.trim()
                                tail.addLast(line)
                                if (tail.size > 6) tail.removeFirst()
                            }
                        }
                    }
                    process.waitFor()
                }
            } finally {
                if (process.isAlive) process.destroyForcibly()
            }
            coroutineContext.ensureActive()
            val file = finalPath?.let(::File)?.takeIf { it.isFile } ?: existing(key)
                ?: error("the reaction could not be downloaded for analysis: ${tail.lastOrNull() ?: "yt-dlp exit ${process.exitValue()}"}")
            evict()
            log.i { "analysis copy ${file.name} ${file.length() / 1_000_000} MB" }
            file
        }

    private fun existing(key: String): File? =
        dir.listFiles()?.firstOrNull { it.isFile && it.name.startsWith("$key.") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") }

    private fun evict() {
        val files = dir.listFiles()?.filter { it.isFile && !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() } ?: return
        files.drop(KEEP).forEach { it.delete() }
    }
}

/** The episode as the main player opened it, so a second reader can open it the same way. */
internal data class EpisodeSource(
    val target: String,
    val options: List<String>,
    /** Identifies the content, not the URL: debrid links change per session, the file does not. */
    val key: String,
    val durationMs: Long?,
)

/**
 * What of the episode to read next (Stage 4). Reading a 4K remux over debrid ran at ~4x real time on
 * the desktop PC, so all of Interstellar took 40+ minutes - and the matcher does not need all of it:
 * it carries a line through what it has not seen until something says the reaction cut. So, in order:
 *  1. AHEAD: what is about to play, densely, so it is checked before it is on screen;
 *  2. PROBES: [PROBE_FRAMES] every [PROBE_EVERY_FRAMES] over the whole film, from the playhead on
 *     and then from the start - enough for the matcher to find and place its lines;
 *  3. EDGES: around every start and end of a matched stretch, so a cut is placed exactly;
 *  4. FILL: the rest, from the playhead on, in the background.
 * Steps 1-3 are what "checked" means; the fill only sharpens what is already there.
 */
internal class EpisodeReadPlanner(private val totalFrames: Int) {
    enum class Phase { AHEAD, PROBE, EDGE, FILL }

    data class Plan(val range: IntRange, val phase: Phase)

    private val unreadable = ArrayList<IntRange>()
    private val probes = (0 until totalFrames step PROBE_EVERY_FRAMES).map { it until min(it + PROBE_FRAMES, totalFrames) }

    /** A range that was read and gave nothing (past the real end, say): never asked for again. */
    fun markUnreadable(range: IntRange) {
        unreadable += range
    }

    fun probesDone(track: FingerprintTrack): Int = probes.count { p -> settledRead(track, p) }

    val probeCount: Int get() = probes.size

    /**
     * Steps 1-3 are done for this position and these edges. Looser ahead than the reads themselves
     * ([CHECKED_AHEAD_FRAMES] of the [AHEAD_FRAMES] read), so a window sliding with playback does not
     * flicker between checked and not every second.
     */
    fun checked(track: FingerprintTrack, position: Int, edges: List<Int>): Boolean =
        isRead(track, position.coerceAtLeast(0) until (position + CHECKED_AHEAD_FRAMES).coerceAtMost(totalFrames)) &&
            probes.all { settledRead(track, it) } &&
            edges.all { isRead(track, (it - EDGE_FRAMES).coerceAtLeast(0) until (it + EDGE_FRAMES).coerceAtMost(totalFrames)) }

    /** Every frame of [range] read, or known not to exist. */
    fun isRead(track: FingerprintTrack, range: IntRange): Boolean {
        var a = range.first
        val end = range.last + 1
        while (a < end) {
            val run = track.missingRange(a, end) ?: return true
            val known = unreadable.firstOrNull { it.first <= run.first && run.first <= it.last } ?: return false
            a = known.last + 1
        }
        return true
    }

    fun next(track: FingerprintTrack, position: Int, edges: List<Int>, busy: List<IntRange>): Plan? {
        fun missing(from: Int, until: Int, cap: Int): IntRange? {
            var a = from.coerceIn(0, totalFrames)
            val end = until.coerceIn(0, totalFrames)
            while (a < end) {
                val run = track.missingRange(a, end) ?: return null
                val blocked = (unreadable + busy).firstOrNull { it.first <= run.first && run.first <= it.last }
                if (blocked == null) {
                    val stop = (busy + unreadable).filter { it.first > run.first }.minOfOrNull { it.first } ?: Int.MAX_VALUE
                    return run.first until minOf(run.last + 1, run.first + cap, stop)
                }
                a = blocked.last + 1
            }
            return null
        }
        missing(position - AHEAD_BACK_FRAMES, position + AHEAD_FRAMES, CHUNK_FRAMES)?.let { return Plan(it, Phase.AHEAD) }
        val first = probes.indexOfFirst { it.first >= position }.let { if (it < 0) 0 else it }
        for (k in probes.indices) {
            val p = probes[(first + k) % probes.size]
            if (settledRead(track, p)) continue
            missing(p.first, p.last + 1, PROBE_FRAMES)?.let { return Plan(it, Phase.PROBE) }
        }
        for (edge in edges.sortedBy { if (it >= position) it - position else it + totalFrames }) {
            missing(edge - EDGE_FRAMES, edge + EDGE_FRAMES, CHUNK_FRAMES)?.let { return Plan(it, Phase.EDGE) }
        }
        missing(position, totalFrames, CHUNK_FRAMES)?.let { return Plan(it, Phase.FILL) }
        missing(0, position, CHUNK_FRAMES)?.let { return Plan(it, Phase.FILL) }
        return null
    }

    private fun settledRead(track: FingerprintTrack, range: IntRange): Boolean = isRead(track, range)

    companion object {
        private const val FPS = ReactionFingerprinter.FPS
        const val AHEAD_FRAMES = 180 * FPS
        const val AHEAD_BACK_FRAMES = 20 * FPS
        const val CHECKED_AHEAD_FRAMES = 120 * FPS
        /** Each probe is a stream open and seek: ~10 s over debrid for a 4K remux (Debug 93). */
        const val PROBE_EVERY_FRAMES = 180 * FPS
        const val PROBE_FRAMES = 8 * FPS
        const val EDGE_FRAMES = 60 * FPS
        /** One read at a time is at most this long, so the plan follows the playhead. */
        const val CHUNK_FRAMES = 90 * FPS
    }
}

/**
 * Indexes the episode (Stage 4): a second, windowless reader of the same file or stream, at 128x72
 * grey, 8 fps, from wherever playback is to the end. Decoding is held while the main player drops
 * frames, so a 4K episode is never starved by its own index.
 */
internal object ReactionEpisodeIndexer {
    const val WIDTH = 128
    const val HEIGHT = 72
    private const val READ_PADDING_FRAMES = 2 * ReactionFingerprinter.FPS

    fun sourceOf(playerHandle: Long): EpisodeSource? {
        val path = ReactionNative.mainGetProperty(playerHandle, "path")?.takeIf { it.isNotBlank() } ?: return null
        val duration = ReactionNative.mainGetProperty(playerHandle, "duration")?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val size = ReactionNative.mainGetProperty(playerHandle, "file-size")?.toLongOrNull()
        val options = mutableListOf<String>()
        ReactionNative.mainGetProperty(playerHandle, "http-header-fields")?.takeIf { it.isNotBlank() }?.let { options += "http-header-fields=$it" }
        options += "network-timeout=30"
        options += "hwdec=auto-copy"
        val local = File(path)
        val identity = when {
            local.isFile -> "file|${local.absolutePath}|${local.length()}|${local.lastModified()}"
            size != null && duration != null -> "stream|$size|$duration"
            else -> "url|$path"
        }
        return EpisodeSource(path, options, "ep-" + sha(identity), duration)
    }

    suspend fun index(
        source: EpisodeSource,
        track: FingerprintTrack,
        range: IntRange,
        holdWhile: () -> Boolean,
        onProgress: (Double) -> Unit,
        onFingerprints: () -> Unit,
    ) {
        val fingerprinter = EpisodeFingerprinter(WIDTH, HEIGHT, track)
        // The decoder stops about a second short of `end`: ask for more, or the tail of every read
        // comes back missing and costs a second stream open (~10 s over debrid, Debug 93's first test).
        val toIndex = range.last + 1 + READ_PADDING_FRAMES
        var lastPublish = range.first
        val fps = ReactionFingerprinter.FPS
        ReactionFrames.read(source.target, source.options, WIDTH, HEIGHT, fps, range.first, toIndex, holdWhile) { index, frame ->
            fingerprinter.add(index, frame)
            if (index - lastPublish >= 80) {
                lastPublish = index
                onProgress(((index - range.first).toDouble() / (toIndex - range.first).coerceAtLeast(1)).coerceIn(0.0, 0.99))
                onFingerprints()
            }
        }
        fingerprinter.finish()
        onFingerprints()
        onProgress(1.0)
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
}
