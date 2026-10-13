package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import kotlin.coroutines.coroutineContext

/**
 * Samples a stretch of the reaction video and finds its layout. A windowless libmpv in encode mode
 * writes 480x270 greyscale frames at 4 fps to a scratch file (no ffmpeg needed: libmpv carries
 * it), which [ReactionLayoutDetector] then reads in one streaming pass.
 */
internal object ReactionAnalyzer {
    private val log = Logger.withTag("ReactionAnalyzer")

    /** The stretch analysed: long enough for motion statistics, skipping the usual intro. */
    data class Window(val startMs: Long, val lengthMs: Long)

    fun window(durationMs: Long?): Window {
        val length = 150_000L
        if (durationMs == null || durationMs <= length) return Window(0, durationMs ?: length)
        val start = (durationMs * 0.25).toLong().coerceAtMost(durationMs - length)
        return Window(start, length)
    }

    /**
     * [target] is what mpv should open (a file path, or the reaction's resolved stream URLs);
     * [extraOptions] the yt-dlp options when it is a page URL instead.
     */
    suspend fun detectLayout(
        target: String,
        extraOptions: List<String>,
        durationMs: Long?,
        onProgress: (Double) -> Unit,
    ): DetectedLayout = withContext(Dispatchers.IO) {
        val w = ReactionLayoutDetector.ANALYSIS_WIDTH
        val h = ReactionLayoutDetector.ANALYSIS_HEIGHT
        val fps = ReactionLayoutDetector.ANALYSIS_FPS
        val window = window(durationMs)
        val dir = DesktopStorage.cacheDir.resolve("reaction").resolve("analysis").toFile().apply { mkdirs() }
        val frames = File(dir, "frames-${System.nanoTime()}.gray")
        val f = { ms: Long -> String.format(Locale.ROOT, "%.3f", ms / 1000.0) }
        val options = extraOptions + listOf(
            "o=${frames.absolutePath}",
            "of=rawvideo",
            "ovc=rawvideo",
            "vf=lavfi=[fps=$fps,scale=$w:$h,format=gray]",
            "audio=no",
            "keep-open=no",
            "start=${f(window.startMs)}",
            "end=${f(window.startMs + window.lengthMs)}",
        )
        val expectedBytes = window.lengthMs / 1000.0 * fps * w * h
        val handle = ReactionNative.createHeadlessStage(options.toTypedArray())
        try {
            ReactionNative.stageCommand(handle, arrayOf("loadfile", target))
            val deadline = System.currentTimeMillis() + 8 * 60_000L
            var finished = false
            while (!finished && System.currentTimeMillis() < deadline) {
                coroutineContext.ensureActive()
                delay(500)
                onProgress((frames.length() / expectedBytes).coerceIn(0.0, 0.99))
                for (event in ReactionNative.stageTakeEvents(handle).lines()) {
                    if (event.startsWith("end-file")) {
                        finished = true
                        val reason = event.split(' ').getOrNull(1)?.toIntOrNull()
                        if (reason == 4) error("the reaction video could not be read for analysis")
                    }
                }
            }
            check(finished) { "analysis timed out" }
        } finally {
            // Finalises the encoder too.
            ReactionNative.destroyStage(handle)
        }
        try {
            val detector = ReactionLayoutDetector(w, h)
            val frameBytes = w * h
            frames.inputStream().buffered(1 shl 20).use { input ->
                val buffer = ByteArray(frameBytes)
                while (true) {
                    var read = 0
                    while (read < frameBytes) {
                        val got = input.read(buffer, read, frameBytes - read)
                        if (got < 0) break
                        read += got
                    }
                    if (read < frameBytes) break
                    detector.add(buffer.copyOf())
                }
            }
            onProgress(1.0)
            detector.detect().also { log.i { "layout: ${it.reactors.size} reactors, movie=${it.movie != null}" } }
        } finally {
            frames.delete()
        }
    }
}

/** Layouts are cached per reaction video, so one is analysed once (Stage 3). */
internal object ReactionLayoutCache {
    @Serializable
    private data class Stored(val version: Int, val tiles: List<StoredTile>)

    @Serializable
    private data class StoredTile(val x: Double, val y: Double, val w: Double, val h: Double, val kind: String)

    private const val VERSION = 1
    private val json = Json { ignoreUnknownKeys = true }

    private val dir: File by lazy {
        val base = System.getProperty("nuvio.reactionDataDir")?.let(::File)
            ?: DesktopStorage.resolveAppDataDir().resolve("reaction").toFile()
        File(base, "layouts").apply { mkdirs() }
    }

    /** A stable identity for a reaction video: its YouTube id, or a local file's path, size and date. */
    fun key(source: ReactionSource): String = when (source) {
        is ReactionSource.WebVideo -> ReactionLinkServer.youTubeVideoId(source.url)?.let { "yt-$it" } ?: "url-" + sha(source.url)
        is ReactionSource.LocalFile -> File(source.path).let { "file-" + sha("${it.absolutePath}|${it.length()}|${it.lastModified()}") }
    }

    fun load(key: String): DetectedLayout? = runCatching {
        val stored = json.decodeFromString(Stored.serializer(), File(dir, "$key.json").readText())
        if (stored.version != VERSION) return null
        DetectedLayout(stored.tiles.map { DetectedTile(LayoutRect(it.x, it.y, it.w, it.h), TileKind.valueOf(it.kind)) })
    }.getOrNull()

    fun save(key: String, layout: DetectedLayout) {
        runCatching {
            val stored = Stored(VERSION, layout.tiles.map { StoredTile(it.rect.x, it.rect.y, it.rect.w, it.rect.h, it.kind.name) })
            File(dir, "$key.json").writeText(json.encodeToString(Stored.serializer(), stored))
        }
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
}
