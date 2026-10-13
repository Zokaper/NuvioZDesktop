package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import com.nuvio.app.features.reaction.ReactionFingerprinter.FRAME_MS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest

/**
 * Reads the reaction once, from [analyze]'s start to the end, and gets two things out of it: the
 * layout timeline (Stage 3: where the reactors and the movie are, per stretch), published after
 * every analysis window, and fingerprints of the movie inside it (Stage 4), taken once a stretch's
 * layout is known. Web reactions are analysed from a downloaded copy (ReactionDownloads): YouTube
 * streams at about real time, a download runs at tens of MB/s.
 *
 * A long video is read as up to [MAX_PARTS] stretches at once, each by its own decoder: one reader
 * took 5 m 46 s over a 2 h 45 m compilation on the desktop PC (AV1 at 480p decodes at ~2,200
 * frames/s even multithreaded, then every frame is analysed in one thread). Stretches are
 * independent: a layout window or a letterbox verdict never needs more than its own 45 s or +-5 s.
 */
internal object ReactionTimelineAnalyzer {
    private val log = Logger.withTag("ReactionAnalyzer")

    /** Parallel readers: a quarter of the logical cores, at most four. */
    private val MAX_PARTS = (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)

    /** Below this much video one reader is fast enough and starting more is not worth it. */
    private const val MIN_PART_MS = 10 * 60 * 1000L

    suspend fun analyze(
        target: String,
        fromMs: Long,
        durationMs: Long?,
        fingerprints: FingerprintTrack,
        onProgress: (Double) -> Unit,
        onTimeline: (ReactionLayoutTimeline) -> Unit,
        onFingerprints: () -> Unit,
    ): ReactionLayoutTimeline = coroutineScope {
        val w = ReactionLayoutDetector.ANALYSIS_WIDTH
        val h = ReactionLayoutDetector.ANALYSIS_HEIGHT
        val step = ReactionFingerprinter.FPS / ReactionLayoutDetector.ANALYSIS_FPS
        // Start on an even grid index: the layout builder takes every other frame, at its own 4 fps.
        val fromIndex = ((fromMs / FRAME_MS).toInt() / step) * step
        val endIndex = durationMs?.let { (it / FRAME_MS).toInt() }
        val parts = parts(fromIndex, endIndex, step)
        val lock = Any()
        val timelines = arrayOfNulls<ReactionLayoutTimeline>(parts.size)
        val read = IntArray(parts.size)
        val total = endIndex?.let { (it - fromIndex).coerceAtLeast(1) }
        fun merged() = ReactionLayoutTimeline(timelines.filterNotNull().flatMap { it.epochs }.sortedBy { it.startMs }).steadied()
        val started = System.currentTimeMillis()
        parts.mapIndexed { k, (first, until) ->
            async(Dispatchers.IO) {
                val builder = ReactionLayoutTimelineBuilder(startMs = first * FRAME_MS)
                val pending = MovieFingerprinter(w / 2, h / 2, fingerprints)
                ReactionFrames.read(target, emptyList(), w, h, ReactionFingerprinter.FPS, first, until) { index, frame ->
                    pending.add(index, ReactionFrames.halve(frame, w, h))
                    if ((index - first) % step == 0 && builder.add(frame)) {
                        val timeline = synchronized(lock) {
                            timelines[k] = builder.timeline()
                            merged()
                        }
                        onTimeline(timeline)
                        pending.flush(timeline, untilMs = builder.processedMs)
                        onFingerprints()
                    }
                    if (index % 40 == 0 && total != null) {
                        val done = synchronized(lock) { read[k] = index - first; read.sum() }
                        onProgress((done.toDouble() / total).coerceIn(0.0, 0.99))
                    }
                }
                builder.finish()
                val timeline = synchronized(lock) {
                    timelines[k] = builder.timeline()
                    merged()
                }
                pending.flush(timeline, untilMs = Long.MAX_VALUE)
                onFingerprints()
            }
        }.awaitAll()
        val timeline = synchronized(lock) { merged() }
        onProgress(1.0)
        log.i {
            "timeline: ${timeline.epochs.size} epochs in ${(System.currentTimeMillis() - started) / 1000} s with ${parts.size} readers, " +
                "movie ${timeline.epochs.groupingBy { it.layout.movie?.rect }.eachCount().maxByOrNull { it.value }?.key}, " +
                timeline.epochs.joinToString { "${it.startMs / 1000}-${it.endMs / 1000}s:${it.layout.reactors.size}" }
        }
        onTimeline(timeline)
        timeline
    }

    /** [fromIndex, end) cut into equal stretches on the layout's grid; the last one runs to the end. */
    private fun parts(fromIndex: Int, endIndex: Int?, step: Int): List<Pair<Int, Int?>> {
        if (endIndex == null) return listOf(fromIndex to null)
        val count = ((endIndex - fromIndex) * FRAME_MS / MIN_PART_MS).toInt().coerceIn(1, MAX_PARTS)
        val length = ((endIndex - fromIndex) / count / step) * step
        return (0 until count).map { k ->
            val first = fromIndex + k * length
            first to (if (k == count - 1) null else first + length)
        }
    }
}

/**
 * Holds half-size reaction frames until the layout of their stretch is known, then fingerprints the
 * movie box in each (the whole frame when no movie was found: a reaction that is mostly movie still
 * matches, one that is all reactors simply does not), with the channel's overlay taken out.
 */
internal class MovieFingerprinter(private val width: Int, private val height: Int, track: FingerprintTrack) {
    private val frames = ArrayList<Pair<Int, ByteArray>>()
    private val sliding = SlidingFingerprinter(width, track, OverlayNormaliser(width * height))

    fun add(index: Int, halfFrame: ByteArray) {
        frames += index to halfFrame
    }

    /** Places every frame before [untilMs] in its box; [untilMs] = Long.MAX_VALUE ends the video. */
    fun flush(timeline: ReactionLayoutTimeline, untilMs: Long) {
        // Frames arrive in order, so the ready ones are a prefix.
        val count = frames.indexOfFirst { it.first * FRAME_MS >= untilMs }.let { if (it < 0) frames.size else it }
        val ready = frames.subList(0, count).toList()
        frames.subList(0, count).clear()
        for ((index, frame) in ready) {
            val movie = timeline.at(index * FRAME_MS)?.movie?.rect ?: LayoutRect.FULL
            sliding.add(index, frame, PixelRect.of(movie, width, height))
        }
        if (untilMs == Long.MAX_VALUE) sliding.finish()
    }
}

/** The episode side: the whole frame, minus any bars the file itself carries. */
internal class EpisodeFingerprinter(private val width: Int, private val height: Int, track: FingerprintTrack) {
    private val sliding = SlidingFingerprinter(width, track)

    fun add(index: Int, frame: ByteArray) = sliding.add(index, frame, PixelRect(0, 0, width, height))

    fun finish() = sliding.finish()
}

/** Layout timelines are cached per reaction video, so one is analysed once (Stage 3). */
internal object ReactionLayoutCache {
    @Serializable
    private data class Stored(val version: Int, val epochs: List<StoredEpoch>)

    @Serializable
    private data class StoredEpoch(val startMs: Long, val endMs: Long, val tiles: List<StoredTile>)

    @Serializable
    private data class StoredTile(val x: Double, val y: Double, val w: Double, val h: Double, val kind: String)

    // 3: earlier mid-video analyses began at twice the requested position (see ReactionFrames).
    // 4: the movie box is steadied over the whole video (ReactionLayoutTimeline.steadied).
    private const val VERSION = 4
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

/**
 * Fingerprint tracks on disk (Stage 4): a reaction's, keyed like its layout, and an episode's, keyed
 * by its content. A track that was only partly analysed is saved too, and completed later.
 */
internal object ReactionTrackCache {
    private const val VERSION = 1

    private val dir: File by lazy {
        val base = System.getProperty("nuvio.reactionDataDir")?.let(::File)
            ?: DesktopStorage.resolveAppDataDir().resolve("reaction").toFile()
        File(base, "fingerprints").apply { mkdirs() }
    }

    fun load(key: String): FingerprintTrack? = runCatching {
        DataInputStream(File(dir, "$key.bin").inputStream().buffered()).use { input ->
            if (input.readInt() != VERSION) return null
            FingerprintTrack.read(input)
        }
    }.getOrNull()

    fun save(key: String, track: FingerprintTrack) {
        runCatching {
            val target = File(dir, "$key.bin")
            val partial = File(dir, "$key.bin.part")
            DataOutputStream(partial.outputStream().buffered()).use { out ->
                out.writeInt(VERSION)
                track.snapshot().write(out)
            }
            java.nio.file.Files.move(partial.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}
