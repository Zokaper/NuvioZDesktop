package com.nuvio.app.features.reaction

import co.touchlab.kermit.Logger
import com.nuvio.app.core.storage.DesktopStorage
import java.io.File
import java.util.Locale

/** Where the stage draws. */
internal sealed interface StagePlacement {
    /** Borderless popup covering [bounds] (virtual-screen pixels) - a second monitor. */
    data class Monitor(val bounds: java.awt.Rectangle) : StagePlacement

    /** Inside the main player, between the episode and the controls (single-screen mode). */
    data class InPlayer(val containerHwnd: Long, val width: Int, val height: Int) : StagePlacement
}

/**
 * The reaction video's own libmpv instance. Thread-safe the way libmpv is: every call is a
 * property read/write or a command on the stage's handle, and the native side owns its threads.
 */
internal class ReactionStage private constructor(
    private val handle: Long,
    val placement: StagePlacement,
) {
    private val log = Logger.withTag("ReactionStage")
    private var shaderFile: File? = null
    private var lastShaderOptions: String? = null
    private var lastOverlay: String? = null
    @Volatile var closed = false
        private set

    fun load(source: ReactionSource) {
        val target = when (source) {
            is ReactionSource.LocalFile -> source.path
            is ReactionSource.WebVideo -> source.url
        }
        command("loadfile", target, "replace")
    }

    fun command(vararg args: String): Int =
        if (closed) -1 else ReactionNative.stageCommand(handle, arrayOf(*args))

    fun set(name: String, value: String): Int =
        if (closed) -1 else ReactionNative.stageSetProperty(handle, name, value)

    fun get(name: String): String? = if (closed) null else ReactionNative.stageGetProperty(handle, name)

    fun double(name: String): Double? = get(name)?.toDoubleOrNull()

    fun flag(name: String): Boolean? = when (get(name)) {
        "yes" -> true
        "no" -> false
        else -> null
    }

    fun sample(): FollowerSample = FollowerSample(
        positionMs = double("time-pos")?.let { (it * 1000).toLong() },
        paused = flag("pause") ?: true,
        speed = double("speed") ?: 1.0,
        seeking = (flag("seeking") ?: false) || (flag("paused-for-cache") ?: false),
        durationMs = double("duration")?.let { (it * 1000).toLong() },
    )

    fun apply(command: FollowerCommand) {
        when (command) {
            is FollowerCommand.Seek -> command(
                "seek",
                String.format(Locale.ROOT, "%.3f", command.positionMs / 1000.0),
                if (command.exact) "absolute+exact" else "absolute",
            )
            is FollowerCommand.SetPaused -> set("pause", if (command.paused) "yes" else "no")
            is FollowerCommand.SetSpeed -> set("speed", String.format(Locale.ROOT, "%.4f", command.speed))
        }
    }

    fun setVolume(percent: Double) {
        set("volume", String.format(Locale.ROOT, "%.1f", percent.coerceIn(0.0, 100.0)))
    }

    /** Replaces the tile shader. A new file each time: mpv re-reads shaders when the list changes. */
    fun setTiles(tiles: List<ShaderTile>) {
        val dir = DesktopStorage.cacheDir.resolve("reaction").resolve("shaders").toFile().apply { mkdirs() }
        val file = File(dir, "stage-${System.nanoTime()}.glsl")
        file.writeText(ReactionShader.build(tiles))
        if (set("glsl-shaders", file.absolutePath) < 0) log.w { "glsl-shaders rejected for $file" }
        shaderFile?.delete()
        shaderFile = file
        lastShaderOptions = null
    }

    fun setLiveParameters(dim: Double, blackoutMask: Int) {
        val options = ReactionShader.options(dim, blackoutMask)
        if (options == lastShaderOptions) return
        lastShaderOptions = options
        set("glsl-shader-opts", options)
    }

    /** A small centred chip drawn by mpv's own OSD (ASS), e.g. "Reactions resume in 1:42". */
    fun setOverlay(text: String?) {
        if (text == lastOverlay) return
        lastOverlay = text
        if (text == null) {
            command("osd-overlay", "1", "none", "")
            return
        }
        val escaped = text.replace("\\", "\\\\").replace("{", "(").replace("}", ")")
        // BorderStyle 3 draws an opaque box behind the text; \an5 centres it. 1920x1080 script space.
        val ass = "{\\an5\\pos(960,1000)\\fs34\\bord14\\shad0\\3c&H1D1610&\\3a&H30&\\1c&HFFFFFF&\\fnSegoe UI Semibold}$escaped"
        command("osd-overlay", "1", "ass-events", ass, "1920", "1080")
    }

    fun setBounds(x: Int, y: Int, width: Int, height: Int) {
        if (!closed) ReactionNative.stageSetBounds(handle, x, y, width, height)
    }

    fun setHole(rect: LayoutRect?) {
        if (closed) return
        if (rect == null) {
            ReactionNative.stageSetHole(handle, 0, 0, 0, 0)
        } else {
            ReactionNative.stageSetHole(handle, rect.x.toInt(), rect.y.toInt(), rect.w.toInt(), rect.h.toInt())
        }
    }

    fun setVisible(visible: Boolean) {
        if (!closed) ReactionNative.stageSetVisible(handle, visible)
    }

    fun restack() {
        if (!closed) ReactionNative.stageRestack(handle)
    }

    fun takeEvents(): List<String> =
        if (closed) emptyList() else ReactionNative.stageTakeEvents(handle).lines().filter { it.isNotBlank() }

    fun close() {
        if (closed) return
        closed = true
        runCatching { ReactionNative.destroyStage(handle) }.onFailure { log.w(it) { "destroy failed" } }
        shaderFile?.delete()
    }

    companion object {
        fun open(placement: StagePlacement, extraOptions: List<String>): ReactionStage {
            val options = (BASE_OPTIONS + extraOptions).toTypedArray()
            val handle = when (placement) {
                is StagePlacement.Monitor -> with(placement.bounds) {
                    ReactionNative.createStage(0L, x, y, width, height, options)
                }
                is StagePlacement.InPlayer -> ReactionNative.createStage(
                    placement.containerHwnd, 0, 0, placement.width, placement.height, options,
                )
            }
            check(handle != 0L) { "the reaction stage did not start" }
            return ReactionStage(handle, placement)
        }

        private val BASE_OPTIONS = listOf(
            "pause=yes",
            "audio-pitch-correction=yes",
            // The whole reaction stays in memory once read, so seeks back within it are instant
            // (a network seek costs 2-3 s; Stage 0, S1).
            "cache=yes",
            "demuxer-max-bytes=1536MiB",
            "demuxer-max-back-bytes=1536MiB",
            "demuxer-seekable-cache=yes",
            "cache-secs=36000",
            "osd-level=0",
        )
    }
}

internal sealed interface ReactionSource {
    val label: String

    data class LocalFile(val path: String) : ReactionSource {
        override val label: String get() = File(path).name
    }

    data class WebVideo(val url: String, val title: String? = null) : ReactionSource {
        override val label: String get() = title ?: url
    }
}
