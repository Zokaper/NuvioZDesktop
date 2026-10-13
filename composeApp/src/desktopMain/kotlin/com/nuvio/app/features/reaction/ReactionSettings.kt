package com.nuvio.app.features.reaction

import com.nuvio.app.core.debug.isDebugBuild
import com.nuvio.app.core.storage.DesktopStorage
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.io.File
import java.util.Properties

/** Reaction Mode is experimental: Debug builds only, or `NUVIO_REACTION_MODE=1` for a release build. */
internal object ReactionModeFlag {
    val enabled: Boolean by lazy {
        val forced = System.getenv("NUVIO_REACTION_MODE") ?: System.getProperty("nuvio.reactionMode")
        val osIsWindows = System.getProperty("os.name").orEmpty().lowercase().contains("win")
        osIsWindows && when (forced) {
            "1", "true" -> true
            "0", "false" -> false
            else -> isDebugBuild
        }
    }
}

internal enum class StageMode { DUAL, SINGLE }

internal data class ReactionPreferences(
    val mode: StageMode = StageMode.DUAL,
    /** GraphicsDevice id string of the chosen reactions monitor. */
    val monitorId: String? = null,
    val movieFraction: Double = 0.62,
    val audio: ReactionAudioSettings = ReactionAudioSettings(),
)

/** A small properties file beside the app's other state; nothing here is per-profile. */
internal object ReactionPreferencesStore {
    private val file: File by lazy { DesktopStorage.resolveAppDataDir().resolve("reaction").resolve("settings.properties").toFile() }

    fun load(): ReactionPreferences = runCatching {
        if (!file.isFile) return ReactionPreferences()
        val p = Properties().apply { file.inputStream().use(::load) }
        val defaults = ReactionPreferences()
        ReactionPreferences(
            mode = runCatching { StageMode.valueOf(p.getProperty("mode")) }.getOrDefault(defaults.mode),
            monitorId = p.getProperty("monitor")?.takeIf { it.isNotBlank() },
            movieFraction = p.getProperty("movieFraction")?.toDoubleOrNull() ?: defaults.movieFraction,
            audio = ReactionAudioSettings(
                reactionVolume = p.getProperty("reactionVolume")?.toDoubleOrNull() ?: defaults.audio.reactionVolume,
                movieVolumeWithReactions = p.getProperty("movieVolume")?.toDoubleOrNull() ?: defaults.audio.movieVolumeWithReactions,
                movieVolumeInGaps = p.getProperty("gapVolume")?.toDoubleOrNull() ?: defaults.audio.movieVolumeInGaps,
            ),
        )
    }.getOrDefault(ReactionPreferences())

    fun save(preferences: ReactionPreferences) {
        runCatching {
            file.parentFile.mkdirs()
            val p = Properties()
            p.setProperty("mode", preferences.mode.name)
            p.setProperty("monitor", preferences.monitorId.orEmpty())
            p.setProperty("movieFraction", preferences.movieFraction.toString())
            p.setProperty("reactionVolume", preferences.audio.reactionVolume.toString())
            p.setProperty("movieVolume", preferences.audio.movieVolumeWithReactions.toString())
            p.setProperty("gapVolume", preferences.audio.movieVolumeInGaps.toString())
            val partial = File(file.parentFile, file.name + ".part")
            partial.outputStream().use { p.store(it, "Nuvio Z Reaction Mode") }
            partial.renameTo(file).takeIf { it } ?: run {
                file.delete()
                partial.renameTo(file)
            }
        }
    }
}

internal data class ReactionMonitor(
    val id: String,
    val label: String,
    val detail: String,
    /** Physical pixels, the coordinate space Win32 window placement uses. */
    val physicalBounds: Rectangle,
    val primary: Boolean,
)

internal object ReactionMonitors {
    fun list(): List<ReactionMonitor> = runCatching {
        val environment = GraphicsEnvironment.getLocalGraphicsEnvironment()
        val primaryId = environment.defaultScreenDevice.iDstring
        environment.screenDevices.mapIndexed { index, device ->
            val config = device.defaultConfiguration
            val bounds = config.bounds
            val scaleX = config.defaultTransform.scaleX
            val scaleY = config.defaultTransform.scaleY
            // On Windows the JDK reports a screen's origin in device pixels but its size in scaled
            // user space, so only the size needs scaling back.
            val physical = Rectangle(bounds.x, bounds.y, Math.round(bounds.width * scaleX).toInt(), Math.round(bounds.height * scaleY).toInt())
            val primary = device.iDstring == primaryId
            ReactionMonitor(
                id = device.iDstring,
                label = "Display ${index + 1}" + if (primary) " (main)" else "",
                detail = "${physical.width}×${physical.height}",
                physicalBounds = physical,
                primary = primary,
            )
        }
    }.getOrDefault(emptyList())

    /** The saved monitor if still connected, else the first one that is not the main display. */
    fun choose(monitors: List<ReactionMonitor>, savedId: String?): ReactionMonitor? =
        monitors.firstOrNull { it.id == savedId } ?: monitors.firstOrNull { !it.primary } ?: monitors.firstOrNull()
}
