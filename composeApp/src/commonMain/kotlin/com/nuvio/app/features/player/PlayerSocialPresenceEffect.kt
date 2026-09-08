package com.nuvio.app.features.player

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
        if (snapshot.isEnded || snapshot.isLoading || snapshot.durationMs <= 0L) return
        SocialRepository.publishPresence(
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
