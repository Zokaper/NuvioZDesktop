package com.nuvio.app.core.debug.selftest.suites

import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.debug.selftest.SelfTestContext
import com.nuvio.app.core.sync.SyncManager
import com.nuvio.app.features.downloads.CodecPreference
import com.nuvio.app.features.downloads.DynamicRangePolicy
import com.nuvio.app.features.player.PlayerSettingsRepository
import com.nuvio.app.features.profiles.ProfileRepository
import kotlinx.coroutines.delay

/**
 * Do settings survive a write, a reload from disk, and a pull from the server?
 *
 * This is the least glamorous suite here and arguably the most important, because it covers a class
 * of fault that has shipped twice and is invisible until it has already destroyed something:
 *
 * - `0.4.0-beta` **wiped every playback setting**, because `replaceFromSyncPayload` cleared all of
 *   `syncKeys` before writing back only the keys the remote blob happened to carry. Anything added
 *   since that blob was last written simply vanished.
 * - A later pass **re-gated the app behind the first-launch setup wizard on every single launch**,
 *   because a monotonic value was replaced rather than merged and a stale remote blob dragged it
 *   backwards. That one can never self-correct: the screen that would push the newer value is the
 *   one being gated.
 *
 * Both are only reachable with a real account and a real server round trip, which is why no test in
 * either repository can reach them. The shape here is deliberately the shape of the fault: write,
 * reload from disk, pull, and check the value did not go backwards.
 *
 * ⚠ **This suite writes real settings.** It restores them in a `finally` and reports whether the
 * restore worked, so a crashed run leaves a note rather than a mystery.
 */
internal object S6SettingsAndSync {

    private const val SYNC_SETTLE_MS = 15_000L

    suspend fun run(context: SelfTestContext) {
        context.check("S6.1", "A playback setting survives a reload from disk") { scope ->
            if (!context.fixtures.runSettingsPersistenceCheck) {
                scope.skip("Disabled in the fixture file (`runSettingsPersistenceCheck`).")
            }
            PlayerSettingsRepository.ensureLoaded()
            val originalCodec = PlayerSettingsRepository.uiState.value.playbackCodecPreference
            val originalRange = PlayerSettingsRepository.uiState.value.playbackDynamicRangePolicy
            scope.value("original codec preference", originalCodec.name)
            scope.value("original dynamic range policy", originalRange.name)

            // Pick something that is definitely a change, so a no-op setter cannot make this pass.
            val targetCodec = CodecPreference.entries.first { it != originalCodec }
            val targetRange = DynamicRangePolicy.entries.first { it != originalRange }

            try {
                PlayerSettingsRepository.setPlaybackCodecPreference(targetCodec)
                PlayerSettingsRepository.setPlaybackDynamicRangePolicy(targetRange)
                scope.value("wrote codec preference", targetCodec.name)
                scope.value("wrote dynamic range policy", targetRange.name)

                // Forces the repository to re-read its store rather than answering from the
                // in-memory value it just set - which is the only thing that proves the write
                // reached disk at all.
                PlayerSettingsRepository.onProfileChanged()
                val reloadedCodec = PlayerSettingsRepository.uiState.value.playbackCodecPreference
                val reloadedRange = PlayerSettingsRepository.uiState.value.playbackDynamicRangePolicy
                scope.value("after reload: codec", reloadedCodec.name)
                scope.value("after reload: dynamic range", reloadedRange.name)

                scope.require(reloadedCodec == targetCodec && reloadedRange == targetRange) {
                    "Written $targetCodec/$targetRange but reloaded $reloadedCodec/$reloadedRange - " +
                        "the setting never reached the store, or the store never read it back."
                }

                if (isSignedIn()) {
                    // ⚠ The regression check. A pull that clears keys it does not carry, or that
                    // replaces instead of merging, shows up right here as the value reverting to
                    // what it was before the write.
                    SyncManager.requestForegroundPull(ProfileRepository.activeProfileId, force = true)
                    delay(SYNC_SETTLE_MS)
                    val afterPullCodec = PlayerSettingsRepository.uiState.value.playbackCodecPreference
                    val afterPullRange = PlayerSettingsRepository.uiState.value.playbackDynamicRangePolicy
                    scope.value("after server pull: codec", afterPullCodec.name)
                    scope.value("after server pull: dynamic range", afterPullRange.name)
                    scope.require(afterPullCodec == targetCodec && afterPullRange == targetRange) {
                        "A forced server pull reverted the settings to $afterPullCodec/$afterPullRange. " +
                            "This is the `replaceFromSyncPayload` fault that wiped playback settings " +
                            "in 0.4.0-beta - a stale remote blob is overwriting newer local values."
                    }
                    scope.summary = "Survived a disk reload and a forced server pull."
                } else {
                    scope.value("server pull", "skipped - not signed in")
                    scope.summary = "Survived a disk reload. Not signed in, so no server pull."
                }
            } finally {
                // Restores whatever the user actually had. Recorded rather than assumed: a failed
                // restore leaves their app in a state they did not choose and they need to know.
                val restored = runCatching {
                    PlayerSettingsRepository.setPlaybackCodecPreference(originalCodec)
                    PlayerSettingsRepository.setPlaybackDynamicRangePolicy(originalRange)
                    PlayerSettingsRepository.onProfileChanged()
                    PlayerSettingsRepository.uiState.value.playbackCodecPreference == originalCodec &&
                        PlayerSettingsRepository.uiState.value.playbackDynamicRangePolicy == originalRange
                }.getOrDefault(false)
                scope.value("original settings restored", restored)
            }
        }

        context.observe("S6.2", "A forced sync pull completes") { scope ->
            if (!isSignedIn()) scope.skip("Not signed in to a Nuvio account.")
            val profileId = ProfileRepository.activeProfileId
            val started = System.currentTimeMillis()
            // `requestForegroundPull` is fire-and-forget and publishes no per-step result, so what
            // can be observed from here is that it was accepted and that the app survived it. The
            // value-level regression check in S6.1 is the one with teeth.
            SyncManager.requestForegroundPull(profileId, force = true)
            delay(SYNC_SETTLE_MS)
            scope.value("profile id", profileId)
            scope.value("waited ms", System.currentTimeMillis() - started)
            scope.value("auth state", AuthRepository.state.value::class.simpleName ?: "unknown")
            scope.summary = "Pull requested and settled without throwing."
        }
    }

    private fun isSignedIn(): Boolean {
        val state = AuthRepository.state.value
        return state is AuthState.Authenticated && !state.isAnonymous
    }
}
