package com.nuvio.app.features.reaction

import kotlin.math.abs

/**
 * Volumes are mpv percentages (0..100; mpv allows up to 200 on the main player).
 * The reaction's mixed soundtrack is primary; the episode's own audio is muted while reactions play
 * and fades up during gaps so the episode never goes silent.
 */
internal data class ReactionAudioSettings(
    val reactionVolume: Double = 100.0,
    val movieVolumeWithReactions: Double = 0.0,
    val movieVolumeInGaps: Double = 100.0,
    val fadeMs: Long = 500,
)

internal data class AudioTargets(val movieVolume: Double, val reactionVolume: Double)

/**
 * Ramps the episode's volume between its two levels. Also notices the user moving the main player's
 * own volume slider (the volume it reads back is not the one it last wrote) and adopts that as the
 * level for the current phase, so the normal slider keeps working inside Reaction Mode.
 */
internal class ReactionAudioMixer(settings: ReactionAudioSettings = ReactionAudioSettings()) {
    var settings: ReactionAudioSettings = settings
        private set
    private var current: Double? = null
    private var lastWritten: Double? = null
    private var lastStepMs: Long? = null

    fun update(settings: ReactionAudioSettings) {
        this.settings = settings
    }

    /** Returns the volumes to apply now, or null for the movie when nothing needs writing. */
    fun step(nowMs: Long, showingReactions: Boolean, observedMovieVolume: Double?): AudioTargets {
        val written = lastWritten
        if (observedMovieVolume != null && written != null && abs(observedMovieVolume - written) > 1.0) {
            // The user moved the player's own volume slider: that is the new level for this phase.
            settings = if (showingReactions) {
                settings.copy(movieVolumeWithReactions = observedMovieVolume)
            } else {
                settings.copy(movieVolumeInGaps = observedMovieVolume)
            }
            current = observedMovieVolume
        }
        val target = if (showingReactions) settings.movieVolumeWithReactions else settings.movieVolumeInGaps
        val from = current ?: target
        val elapsed = lastStepMs?.let { (nowMs - it).coerceAtLeast(0) } ?: 0L
        lastStepMs = nowMs
        val maxDelta = if (settings.fadeMs <= 0) Double.MAX_VALUE else 100.0 * elapsed / settings.fadeMs
        val next = when {
            abs(target - from) <= maxDelta -> target
            target > from -> from + maxDelta
            else -> from - maxDelta
        }
        current = next
        lastWritten = next
        return AudioTargets(movieVolume = next, reactionVolume = settings.reactionVolume)
    }

    fun reset() {
        current = null
        lastWritten = null
        lastStepMs = null
    }
}
