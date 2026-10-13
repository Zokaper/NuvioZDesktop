package com.nuvio.app.features.reaction

import kotlin.test.Test
import kotlin.test.assertEquals

class ReactionAudioMixerTest {
    @Test
    fun movieIsMutedWithReactionsAndFadesUpInGaps() {
        val mixer = ReactionAudioMixer(ReactionAudioSettings(movieVolumeWithReactions = 0.0, movieVolumeInGaps = 80.0, fadeMs = 500))
        assertEquals(0.0, mixer.step(0, showingReactions = true, observedMovieVolume = null).movieVolume)
        // Gap starts: 100 points per 500 ms, so 250 ms later it is halfway up the scale.
        assertEquals(0.0, mixer.step(0, showingReactions = false, observedMovieVolume = 0.0).movieVolume)
        assertEquals(50.0, mixer.step(250, showingReactions = false, observedMovieVolume = 0.0).movieVolume, 1e-9)
        assertEquals(80.0, mixer.step(500, showingReactions = false, observedMovieVolume = 50.0).movieVolume, 1e-9)
        // Reactions resume: back down.
        assertEquals(30.0, mixer.step(750, showingReactions = true, observedMovieVolume = 80.0).movieVolume, 1e-9)
    }

    @Test
    fun movingThePlayersOwnSliderSetsThatPhasesLevel() {
        val mixer = ReactionAudioMixer(ReactionAudioSettings(movieVolumeWithReactions = 0.0, movieVolumeInGaps = 100.0))
        mixer.step(0, showingReactions = true, observedMovieVolume = null)
        // The user drags the main volume to 15 while reactions play.
        assertEquals(15.0, mixer.step(50, showingReactions = true, observedMovieVolume = 15.0).movieVolume)
        assertEquals(15.0, mixer.settings.movieVolumeWithReactions)
        assertEquals(100.0, mixer.settings.movieVolumeInGaps)
    }

    @Test
    fun reactionVolumeIsPassedThrough() {
        val mixer = ReactionAudioMixer(ReactionAudioSettings(reactionVolume = 70.0))
        assertEquals(70.0, mixer.step(0, true, null).reactionVolume)
    }
}
