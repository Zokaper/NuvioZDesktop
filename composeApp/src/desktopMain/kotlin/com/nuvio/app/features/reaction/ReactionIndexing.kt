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
 * Indexes the episode (Stage 4): a second, windowless reader of the same file or stream, at 128x72
 * grey, 8 fps, from wherever playback is to the end. Decoding is held while the main player drops
 * frames, so a 4K episode is never starved by its own index.
 */
internal object ReactionEpisodeIndexer {
    const val WIDTH = 128
    const val HEIGHT = 72

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
        val toIndex = range.last + 1
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
