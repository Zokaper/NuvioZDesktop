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
 * The whole video, streamed: frames are read while libmpv is still writing them, a layout timeline
 * is published after every analysis window, so the start of the video is usable within seconds.
 */
internal object ReactionTimelineAnalyzer {
    private val log = Logger.withTag("ReactionAnalyzer")

    /** Layout analysis needs no more than this; YouTube's 480p decodes several times faster. */
    const val WEB_ANALYSIS_FORMAT = "bestvideo[height<=480]/best[height<=480]/worst"

    /**
     * Analyses from [fromMs] to the end. YouTube serves each connection at roughly real time (measured
     * 1.3-2x, at 240p as at 480p), so the whole video cannot be had up front; started at the playback
     * position, the analysis stays ahead of playback and every later viewing comes from the cache.
     */
    suspend fun analyze(
        target: String,
        extraOptions: List<String>,
        fromMs: Long,
        durationMs: Long?,
        onProgress: (Double) -> Unit,
        onTimeline: (ReactionLayoutTimeline) -> Unit,
    ): ReactionLayoutTimeline = withContext(Dispatchers.IO) {
        val w = ReactionLayoutDetector.ANALYSIS_WIDTH
        val h = ReactionLayoutDetector.ANALYSIS_HEIGHT
        val fps = ReactionLayoutDetector.ANALYSIS_FPS
        val dir = DesktopStorage.cacheDir.resolve("reaction").resolve("analysis").toFile().apply { mkdirs() }
        val frames = File(dir, "timeline-${System.nanoTime()}.gray")
        val options = extraOptions + listOf(
            "o=${frames.absolutePath}",
            "of=rawvideo",
            "ovc=rawvideo",
            // setpts: started mid-video, a YouTube stream's timestamps made the fps filter pad the
            // output with the whole skipped stretch as duplicate frames (measured).
            "vf=lavfi=[setpts=PTS-STARTPTS,fps=$fps,scale=$w:$h,format=gray]",
            "audio=no",
            "keep-open=no",
            "sub=no",
            "start=" + String.format(Locale.ROOT, "%.3f", fromMs / 1000.0),
        )
        val builder = ReactionLayoutTimelineBuilder(startMs = fromMs)
        val handle = ReactionNative.createHeadlessStage(options.toTypedArray())
        var finished = false
        try {
            ReactionNative.stageCommand(handle, arrayOf("loadfile", target))
            val frameBytes = w * h
            val buffer = ByteArray(frameBytes)
            var stalledSince = System.currentTimeMillis()
            // Wait for the encoder to create the file.
            while (!frames.exists() && System.currentTimeMillis() - stalledSince < 60_000) {
                coroutineContext.ensureActive()
                delay(250)
                if (ReactionNative.stageTakeEvents(handle).lines().any { it.startsWith("end-file 4") }) {
                    error("the reaction video could not be read for analysis")
                }
            }
            java.io.RandomAccessFile(frames, "r").use { input ->
                var offset = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    for (event in ReactionNative.stageTakeEvents(handle).lines()) {
                        if (event.startsWith("end-file")) {
                            finished = true
                            if (event.split(' ').getOrNull(1)?.toIntOrNull() == 4) error("the reaction video could not be read for analysis")
                        }
                    }
                    var progressed = false
                    while (input.length() - offset >= frameBytes) {
                        input.seek(offset)
                        input.readFully(buffer)
                        offset += frameBytes
                        progressed = true
                        if (builder.add(buffer.copyOf())) onTimeline(builder.timeline())
                    }
                    durationMs?.let { onProgress(((builder.processedMs - fromMs).toDouble() / (it - fromMs).coerceAtLeast(1)).coerceIn(0.0, 0.99)) }
                    if (finished && !progressed && input.length() - offset < frameBytes) break
                    if (progressed) stalledSince = System.currentTimeMillis()
                    check(System.currentTimeMillis() - stalledSince < 120_000) { "analysis stalled" }
                    if (!progressed) delay(300)
                }
            }
        } finally {
            ReactionNative.destroyStage(handle)
            frames.delete()
        }
        builder.finish()
        onProgress(1.0)
        builder.timeline().also { t ->
            log.i { "timeline: ${t.epochs.size} epochs, " + t.epochs.joinToString { "${it.startMs / 1000}-${it.endMs / 1000}s:${it.layout.reactors.size}" } }
            onTimeline(t)
        }
    }
}

/** Layout timelines are cached per reaction video, so one is analysed once (Stage 3). */
internal object ReactionLayoutCache {
    @Serializable
    private data class Stored(val version: Int, val epochs: List<StoredEpoch>)

    @Serializable
    private data class StoredEpoch(val startMs: Long, val endMs: Long, val tiles: List<StoredTile>)

    @Serializable
    private data class StoredTile(val x: Double, val y: Double, val w: Double, val h: Double, val kind: String)

    private const val VERSION = 2
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

    fun load(key: String): ReactionLayoutTimeline? = runCatching {
        val stored = json.decodeFromString(Stored.serializer(), File(dir, "$key.json").readText())
        if (stored.version != VERSION) return null
        ReactionLayoutTimeline(
            stored.epochs.map { e ->
                LayoutEpoch(e.startMs, e.endMs, DetectedLayout(e.tiles.map { DetectedTile(LayoutRect(it.x, it.y, it.w, it.h), TileKind.valueOf(it.kind)) }))
            },
        )
    }.getOrNull()

    fun save(key: String, timeline: ReactionLayoutTimeline) {
        runCatching {
            val stored = Stored(
                VERSION,
                timeline.epochs.map { e ->
                    StoredEpoch(e.startMs, e.endMs, e.layout.tiles.map { StoredTile(it.rect.x, it.rect.y, it.rect.w, it.rect.h, it.kind.name) })
                },
            )
            File(dir, "$key.json").writeText(json.encodeToString(Stored.serializer(), stored))
        }
    }

    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }.take(24)
}
