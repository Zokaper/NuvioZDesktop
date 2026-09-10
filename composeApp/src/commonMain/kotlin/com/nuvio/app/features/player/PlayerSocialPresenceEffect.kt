package com.nuvio.app.features.player

import co.touchlab.kermit.Logger
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import com.nuvio.app.core.sync.SyncClientIdentity
import com.nuvio.app.features.social.SocialPlaybackState
import com.nuvio.app.features.social.SocialPresenceHeartbeatMs
import com.nuvio.app.features.social.SocialPresencePublish
import com.nuvio.app.features.social.SocialPresenceSession
import com.nuvio.app.features.social.SocialRepository
import com.nuvio.app.features.watchparty.ActivePlaybackContext
import com.nuvio.app.features.watchparty.WatchPartySessionCoordinator
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Why presence did or did not reach the backend.
 *
 * Watching Now showed nobody through an entire two-client friend test while the same session
 * published watched activity correctly, and nothing in the app could say which half of this file
 * declined: the readiness guard returns silently, and the publish `Result` was dropped on the
 * floor. Both are named here now, because "no row in `watch_presence`" is indistinguishable from
 * "never asked" without them.
 */
private val presenceLog = Logger.withTag("SocialPresence")

/** Z-owned player seam. It deliberately never reads or sends the active source URL or headers. */
@OptIn(ExperimentalUuidApi::class)
@Composable
internal fun PlayerScreenRuntime.BindSocialPresenceEffect() {
    val deviceId = remember { SyncClientIdentity.currentClientId() }
    val sessionId = remember { Uuid.random().toString() }
    val attachmentId = remember { Uuid.random().toString() }
    val videoKey = "$parentMetaType:$parentMetaId:$activeVideoId:$activeSeasonNumber:$activeEpisodeNumber"
    val defaultJoinPolicy = SocialRepository.uiState.value.me?.defaultJoinPolicy
        ?: com.nuvio.app.features.social.WatchJoinPolicy.approval

    LaunchedEffect(deviceId, sessionId, defaultJoinPolicy) {
        SocialPresenceSession.attach(deviceId, sessionId, defaultJoinPolicy)
    }

    suspend fun publishCurrent() {
        val snapshot = playbackSnapshot
        // Presence describes something a friend could join, so a session that has ended, has not
        // yet produced a frame, or has no known duration is deliberately not published. Named
        // rather than silent: this early return is the first thing to rule out when Watching Now
        // is empty, and it is invisible from the backend.
        if (snapshot.isEnded || snapshot.isLoading || snapshot.durationMs <= 0L) {
            presenceLog.d {
                "skip publish - ended=${snapshot.isEnded} loading=${snapshot.isLoading} " +
                    "durationMs=${snapshot.durationMs}"
            }
            return
        }
        val result = SocialRepository.publishPresence(
            deviceId = deviceId,
            entry = SocialPresencePublish(
                sessionId = sessionId,
                contentId = parentMetaId,
                contentType = parentMetaType,
                videoId = playbackSession.videoId,
                title = title,
                poster = poster,
                background = background,
                episodeThumbnail = activeEpisodeThumbnail,
                season = activeSeasonNumber,
                episode = activeEpisodeNumber,
                episodeTitle = activeEpisodeTitle,
                positionMs = snapshot.positionMs.coerceAtLeast(0L),
                durationMs = snapshot.durationMs,
                playbackSpeed = snapshot.playbackSpeed,
                state = if (snapshot.isPlaying) SocialPlaybackState.playing else SocialPlaybackState.paused,
                effectiveJoinPolicy = SocialPresenceSession.state.value.effectivePolicy,
                sourceFingerprint = activePartySourceDescriptor,
            ),
        )
        // ⚠ This Result used to be discarded. A rejected RPC - an expired token, a missing social
        // profile, a sanitizer that refused the descriptor - then looked exactly like a healthy
        // publish from inside the app, and the only symptom was a friend seeing nobody.
        result.onFailure { presenceLog.w(it) { "presence publish failed" } }
    }

    LaunchedEffect(videoKey,activePartySourceDescriptor,playbackSnapshot.positionMs,playbackSnapshot.durationMs,playbackSnapshot.playbackSpeed) {
        activePartySourceDescriptor?.let { descriptor ->
            WatchPartySessionCoordinator.registerPlayback(
                ActivePlaybackContext(
                    attachmentId=attachmentId,contentId=parentMetaId,videoId=playbackSession.videoId,
                    descriptor=descriptor,positionMs=playbackSnapshot.positionMs,durationMs=playbackSnapshot.durationMs,
                    playbackSpeed=playbackSnapshot.playbackSpeed,
                ),sessionId,deviceId,
            )
        }
    }

    // Immediate updates for play/pause, item/source transition, and first known duration.
    LaunchedEffect(videoKey, playbackSnapshot.isPlaying, playbackSnapshot.isLoading, playbackSnapshot.isEnded, playbackSnapshot.durationMs) {
        publishCurrent()
    }
    LaunchedEffect(videoKey) {
        while (true) {
            delay(SocialPresenceHeartbeatMs)
            publishCurrent()
        }
    }
    DisposableEffect(deviceId) {
        onDispose {
            WatchPartySessionCoordinator.unregisterPlayback(attachmentId)
            SocialPresenceSession.detach(deviceId, sessionId)
            scope.launch { SocialRepository.clearPresence(deviceId) }
        }
    }
}
