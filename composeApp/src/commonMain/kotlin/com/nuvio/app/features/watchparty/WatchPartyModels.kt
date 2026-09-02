package com.nuvio.app.features.watchparty

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.max

const val WatchPartyMaxParticipants = 8
const val WatchPartyHostGraceMs = 15_000L
const val WatchPartySnapshotIntervalMs = 5_000L

/**
 * How long to wait for the realtime channel to report itself subscribed.
 *
 * A topic the server refuses never reaches `SUBSCRIBED`: the client library retries the join in the
 * background and a caller blocking on the subscription waits for a message that is never coming.
 * Long enough that a slow socket still connects, short enough that a refused one is reported.
 */
const val WatchPartyChannelSubscribeTimeoutMs = 12_000L

/** How long to wait for a channel to be given up, over a socket that may be exactly what failed. */
const val WatchPartyChannelCloseTimeoutMs = 3_000L

@Serializable
enum class WatchPartyControlMode { host_only, collaborative }

@Serializable
enum class WatchPartyStatus { lobby, playing, paused, buffering, ended }

@Serializable
enum class SourceResolutionState { joined, resolving, buffering, ready, failed, left }

@Serializable
enum class PartyConnectionState { connected, reconnecting, disconnected }

@Serializable
data class PartyContent(
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val title: String,
    val poster: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("episode_title") val episodeTitle: String? = null,
)

@Serializable
data class SourceFingerprint(
    @SerialName("addon_id") val addonId: String? = null,
    @SerialName("info_hash") val infoHash: String? = null,
    @SerialName("file_index") val fileIndex: Int? = null,
    @SerialName("release_fingerprint") val releaseFingerprint: String,
    val resolution: String? = null,
    val quality: String? = null,
    val languages: Set<String> = emptySet(),
    @SerialName("media_tags") val mediaTags: Set<String> = emptySet(),
)

@Serializable
data class WatchPartyParticipant(
    @SerialName("profile_id") val profileId: String,
    val role: String,
    @SerialName("ready_state") val readyState: SourceResolutionState,
    @SerialName("ready_error") val readyError: String? = null,
    @SerialName("resolved_duration_ms") val resolvedDurationMs: Long? = null,
    val connected: Boolean = true,
    @SerialName("joined_at") val joinedAt: String,
)

@Serializable
data class WatchPartyState(
    val id: String,
    @SerialName("host_profile_id") val hostProfileId: String,
    val status: WatchPartyStatus,
    @SerialName("control_mode") val controlMode: WatchPartyControlMode,
    @SerialName("content_generation") val contentGeneration: Int,
    val content: PartyContent,
    @SerialName("source_fingerprint") val sourceFingerprint: SourceFingerprint? = null,
    @SerialName("position_ms") val positionMs: Long,
    @SerialName("duration_ms") val durationMs: Long,
    @SerialName("playback_speed") val playbackSpeed: Float,
    val sequence: Long,
    @SerialName("state_updated_at") val stateUpdatedAt: String,
    val members: List<WatchPartyParticipant> = emptyList(),
)

@Serializable
data class WatchPartyCommand(
    @SerialName("command_id") val commandId: String,
    val type: String,
    @SerialName("position_ms") val positionMs: Long? = null,
    @SerialName("playback_speed") val playbackSpeed: Float? = null,
)

fun arePartyDurationsCompatible(hostDurationMs: Long, candidateDurationMs: Long): Boolean {
    if (hostDurationMs <= 0L || candidateDurationMs <= 0L) return true
    val tolerance = max(90_000L, (hostDurationMs * 0.02).toLong())
    return abs(hostDurationMs - candidateDurationMs) <= tolerance
}

fun sourceFingerprintMatchScore(host: SourceFingerprint, candidate: SourceFingerprint): Int {
    if (host.infoHash != null && candidate.infoHash != null && host.infoHash.equals(candidate.infoHash, true)) {
        return if (host.fileIndex == candidate.fileIndex) 10_000 else 9_000
    }
    var score = 0
    if (host.addonId != null && host.addonId == candidate.addonId) score += 500
    if (host.releaseFingerprint == candidate.releaseFingerprint) score += 4_000
    if (host.resolution != null && host.resolution == candidate.resolution) score += 200
    if (host.quality != null && host.quality == candidate.quality) score += 100
    score += host.languages.intersect(candidate.languages).size * 20
    score += host.mediaTags.intersect(candidate.mediaTags).size * 10
    return score
}

fun normalizeReleaseFingerprint(value: String): String = value
    .lowercase()
    .replace(Regex("[^a-z0-9]+"), ".")
    .trim('.')
    .replace(Regex("\\.+"), ".")

/** Why a client is holding playback back while it sits in a party. */
enum class PartyHoldReason { NONE, WAITING_FOR_PARTICIPANTS, WAITING_FOR_HOST, HOST_BUFFERING }

data class PartyPlaybackGate(
    val allowPlayback: Boolean,
    val reason: PartyHoldReason,
    val waitingOn: Int = 0,
)

private val PartyBlockingReadyStates = setOf(
    SourceResolutionState.joined,
    SourceResolutionState.resolving,
    SourceResolutionState.buffering,
)

/**
 * Whether this party is the one behind the video currently open in the player.
 *
 * Every party action is scoped through this, so it lives beside the state rather than being
 * re-spelled at each call site - the seek that fires against the wrong title is the one written
 * out by hand a fourth time.
 */
fun WatchPartyState.matchesPlayback(contentId: String, videoId: String?): Boolean =
    status != WatchPartyStatus.ended &&
        content.contentId == contentId &&
        content.videoId == videoId

/**
 * Members the host is still waiting on before playback can begin.
 *
 * A member counts only while they are connected and still working towards a source: someone whose
 * resolution failed, who left, or whose app is gone cannot be waited for, and treating them as a
 * blocker is how one closed laptop holds a party hostage forever.
 */
fun partyMembersAwaitingSource(
    party: WatchPartyState,
    excludeProfileId: String? = null,
): List<WatchPartyParticipant> = party.members.filter { member ->
    member.profileId != excludeProfileId &&
        member.connected &&
        member.readyState in PartyBlockingReadyStates
}

/**
 * Whether this client may play, and what it is waiting for when it may not.
 *
 * The host used to begin the moment their own source opened, which started the authoritative clock
 * while everyone else was still on the source list; by the time a guest had a stream the shared
 * timeline had run minutes ahead of anything they could show, and the correction policy chased it
 * with seeks into an unbuffered file. Holding the host until every connected member reports a
 * resolved source is what makes "everyone starts together" true rather than aspirational.
 *
 * [hostStartReleased] is the escape: once the party has genuinely started, the host owns their own
 * transport again and a mid-film pause must not be mistaken for a fresh start.
 */
fun partyPlaybackGate(
    party: WatchPartyState?,
    viewerProfileId: String?,
    hostStartReleased: Boolean,
    hostBufferingReleased: Boolean = false,
): PartyPlaybackGate {
    if (party == null || party.status == WatchPartyStatus.ended) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    if (party.status == WatchPartyStatus.playing) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    if (party.hostProfileId != viewerProfileId) {
        return when {
            party.status == WatchPartyStatus.buffering && hostBufferingReleased ->
                PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
            party.status == WatchPartyStatus.buffering ->
                PartyPlaybackGate(allowPlayback = false, reason = PartyHoldReason.HOST_BUFFERING)
            else ->
                PartyPlaybackGate(allowPlayback = false, reason = PartyHoldReason.WAITING_FOR_HOST)
        }
    }
    if (hostStartReleased) {
        return PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    }
    val waiting = partyMembersAwaitingSource(party, excludeProfileId = viewerProfileId)
    return if (waiting.isEmpty()) {
        PartyPlaybackGate(allowPlayback = true, reason = PartyHoldReason.NONE)
    } else {
        PartyPlaybackGate(
            allowPlayback = false,
            reason = PartyHoldReason.WAITING_FOR_PARTICIPANTS,
            waitingOn = waiting.size,
        )
    }
}
