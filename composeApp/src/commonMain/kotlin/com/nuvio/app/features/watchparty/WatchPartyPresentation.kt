package com.nuvio.app.features.watchparty

/**
 * How a participant's readiness should *read*, as opposed to what it is called.
 *
 * The lobby and the in-player panel were each spelling this out for themselves, and neither carried
 * severity: the lobby appended the raw enum to a run-on line of `bodySmall`, and the player sent
 * `readyState.name` across the native bridge as a bare string, so the one question anybody asks of
 * that list - is this person ready, or are we waiting on them - was the hardest thing on either
 * screen to answer. Naming the tone once means both surfaces colour the same state the same way,
 * and the native side can style a pill without re-deriving anything.
 */
enum class PartyReadyTone {
    Ready,
    Working,
    Paused,
    Buffering,
    Reconnecting,
    Failed,
    Offline;

    val wireName: String
        get() = when (this) {
            Ready -> "ready"
            Working -> "working"
            Paused -> "paused"
            Buffering -> "buffering"
            Reconnecting -> "reconnecting"
            Failed -> "failed"
            Offline -> "offline"
        }
}

data class DerivedMemberStatus(
    val label: String,
    val tone: PartyReadyTone,
)

/**
 * Derives truthful, fine-grained member status considering connection, location, resolution, and party state.
 */
fun WatchPartyParticipant.derivedStatus(
    livePlaybackStatus: WatchPartyStatus? = null,
    isSelfResyncing: Boolean = false,
): DerivedMemberStatus {
    if (!connected || readyState == SourceResolutionState.disconnected) {
        return if (readyState == SourceResolutionState.left) {
            DerivedMemberStatus("Left", PartyReadyTone.Offline)
        } else {
            DerivedMemberStatus("Offline", PartyReadyTone.Offline)
        }
    }
    if (readyState == SourceResolutionState.left) {
        return DerivedMemberStatus("Left", PartyReadyTone.Offline)
    }
    if (readyState == SourceResolutionState.failed) {
        return DerivedMemberStatus("Failed", PartyReadyTone.Failed)
    }
    if (clientLocation == WatchPartyClientLocation.reconnecting) {
        return DerivedMemberStatus("Reconnecting", PartyReadyTone.Reconnecting)
    }
    if (isSelfResyncing) {
        return DerivedMemberStatus("Resynchronizing", PartyReadyTone.Working)
    }
    if (clientLocation == WatchPartyClientLocation.matching) {
        return DerivedMemberStatus("Matching source", PartyReadyTone.Working)
    }
    if (readyState == SourceResolutionState.fetching ||
        readyState == SourceResolutionState.resolving ||
        readyState == SourceResolutionState.choosing_fallback
    ) {
        return DerivedMemberStatus("Resolving source", PartyReadyTone.Working)
    }
    if (clientLocation == WatchPartyClientLocation.loading) {
        return DerivedMemberStatus("Loading", PartyReadyTone.Working)
    }
    if (readyState == SourceResolutionState.buffering) {
        return DerivedMemberStatus("Buffering", PartyReadyTone.Buffering)
    }
    if (clientLocation == WatchPartyClientLocation.player) {
        return when (livePlaybackStatus) {
            WatchPartyStatus.buffering -> DerivedMemberStatus("Buffering", PartyReadyTone.Buffering)
            WatchPartyStatus.playing -> DerivedMemberStatus("Playing", PartyReadyTone.Ready)
            WatchPartyStatus.paused -> DerivedMemberStatus("Paused", PartyReadyTone.Paused)
            else -> DerivedMemberStatus("Ready", PartyReadyTone.Ready)
        }
    }
    if (readyState == SourceResolutionState.source_ready || readyState == SourceResolutionState.ready) {
        return DerivedMemberStatus("Ready", PartyReadyTone.Ready)
    }
    return DerivedMemberStatus("In lobby", PartyReadyTone.Working)
}

/**
 * Disconnection outranks the last reported state on purpose: a member whose app is gone may have
 * left mid-resolve, and painting them as "finding the host source" implies progress that nobody is
 * making. [partyMembersAwaitingSource] already refuses to wait on them for the same reason.
 */
fun SourceResolutionState.tone(connected: Boolean = true): PartyReadyTone = when {
    !connected -> PartyReadyTone.Offline
    this == SourceResolutionState.left || this == SourceResolutionState.disconnected -> PartyReadyTone.Offline
    this == SourceResolutionState.failed -> PartyReadyTone.Failed
    this == SourceResolutionState.ready || this == SourceResolutionState.source_ready -> PartyReadyTone.Ready
    else -> PartyReadyTone.Working
}

fun WatchPartyParticipant.readyTone(): PartyReadyTone = derivedStatus().tone

/**
 * The short, human label for a readiness state.
 */
fun SourceResolutionState.readyLabel(): String = when (this) {
    SourceResolutionState.joined -> "In lobby"
    SourceResolutionState.waiting_for_host -> "Waiting for host"
    SourceResolutionState.fetching -> "Finding source"
    SourceResolutionState.resolving -> "Resolving source"
    SourceResolutionState.choosing_fallback -> "Choosing alternate"
    SourceResolutionState.source_ready -> "Source ready"
    SourceResolutionState.buffering -> "Buffering"
    SourceResolutionState.ready -> "Ready"
    SourceResolutionState.failed -> "Failed"
    SourceResolutionState.left -> "Left"
    SourceResolutionState.disconnected -> "Offline"
}

/**
 * What a member's pill says, given that a lost connection hides whatever they last reported.
 */
fun WatchPartyParticipant.readyLabel(): String = derivedStatus().label

data class PartyMemberPresentation(
    val profileId: String,
    val label: String,
    val tone: PartyReadyTone,
    val connected: Boolean,
)

data class PartyPresentationState(
    val capability: PartySyncCapability,
    val connection: PartyConnectionState,
    val connectionBanner: String?,
    val members: Map<String, PartyMemberPresentation>,
    val freshHostStatus: WatchPartyStatus?,
)

/**
 * The one presentation authority shared by the Compose lobby and native player surface.
 *
 * Durable party state owns membership/readiness/location. Playback labels require fresh,
 * exact-generation live evidence: the host's tick or a guest's peer telemetry. In particular,
 * the party-wide durable status is never reused as a participant's engine status.
 */
object PartyPresentationProjector {
    fun project(
        party: WatchPartyState?,
        selfProfileId: String?,
        health: PartyHealthState,
        realtime: WatchPartySyncState,
        partyNowMs: Long,
        localPlaybackStatus: WatchPartyStatus? = null,
        selfResyncing: Boolean = false,
    ): PartyPresentationState {
        val capability = health.capability()
        val connection = when (health.realtime) {
            PartyRealtimeHealth.Live -> PartyConnectionState.connected
            PartyRealtimeHealth.Connecting,
            PartyRealtimeHealth.SubscribedUnverified,
            PartyRealtimeHealth.Degraded,
            -> PartyConnectionState.reconnecting
            PartyRealtimeHealth.Detached -> PartyConnectionState.disconnected
        }
        val hostStatus = realtime.tickStatus.takeIf {
            realtime.tickCapturedAtPartyMs?.let { captured ->
                partyNowMs - captured <= WatchPartyTickStaleMs
            } == true
        }
        val projectedMembers = party?.members.orEmpty().associate { member ->
            val liveStatus = when {
                member.profileId == selfProfileId -> localPlaybackStatus
                member.profileId == party?.hostProfileId -> hostStatus
                else -> realtime.peerTelemetry[member.profileId]?.takeIf {
                    partyNowMs - it.receivedAtPartyMs <= WatchPartyClockStaleMs
                }?.status
            }
            val derived = member.derivedStatus(
                livePlaybackStatus = liveStatus,
                isSelfResyncing = member.profileId == selfProfileId && selfResyncing,
            )
            member.profileId to PartyMemberPresentation(
                profileId = member.profileId,
                label = derived.label,
                tone = derived.tone,
                connected = member.connected && derived.tone != PartyReadyTone.Offline,
            )
        }
        return PartyPresentationState(
            capability = capability,
            connection = connection,
            connectionBanner = when (capability) {
                PartySyncCapability.FullSync -> null
                PartySyncCapability.DurableFallback -> "Live sync unavailable — following the party every few seconds"
                PartySyncCapability.RealtimeOnly -> "Party service unavailable — live playback continuing"
                PartySyncCapability.OfflineLocalPlayback -> if (party == null) null else
                    "Connection lost — playback continuing locally"
            },
            members = projectedMembers,
            freshHostStatus = hostStatus,
        )
    }
}


/** How many connected members have a source open, over how many are present. */
fun WatchPartyState.readyCount(): Int = members.count {
    it.connected && it.readyState.tone(true) == PartyReadyTone.Ready
}

/**
 * The lobby's progress rail, in the order a party actually moves through it.
 *
 * [WatchPartyStage.playing] is not a rail step - once the party is playing nobody is looking at the
 * lobby - so the rail runs to `ready_to_launch` and reads as complete from there on.
 */
val WatchPartyStageRail: List<WatchPartyStage> = listOf(
    WatchPartyStage.lobby,
    WatchPartyStage.waiting_for_host_source,
    WatchPartyStage.resolving_sources,
    WatchPartyStage.ready_to_launch,
)

fun WatchPartyStage.railLabel(): String = when (this) {
    WatchPartyStage.lobby -> "Lobby"
    WatchPartyStage.waiting_for_host_source -> "Host source"
    WatchPartyStage.resolving_sources -> "Resolving"
    WatchPartyStage.ready_to_launch -> "Ready"
    WatchPartyStage.playing -> "Playing"
}

/** Where the rail's filled section ends. `playing` is past the end, so it fills the rail. */
fun WatchPartyStage.railIndex(): Int =
    if (this == WatchPartyStage.playing) WatchPartyStageRail.lastIndex else WatchPartyStageRail.indexOf(this)

/**
 * The one line under the title that says what the party as a whole is doing.
 *
 * [hostSourceStaged] is the host's own view and nobody else's: the pick is held on their machine
 * until they press Start, so the party genuinely has no source yet and every other member is
 * correctly told the host is still choosing. Without it the host read "waiting for the host to pick
 * a source" while their own button said Start watching.
 */
fun WatchPartyState.stageHeadline(hostSourceStaged: Boolean = false): String = when (effectiveStage()) {
    WatchPartyStage.lobby ->
        if (hostSourceStaged) "Source picked - start when everyone is here" else "Waiting in the lobby"
    WatchPartyStage.waiting_for_host_source ->
        if (hostSourceStaged) "Source picked - start when everyone is here"
        else "Waiting for the host to pick a source"
    WatchPartyStage.resolving_sources -> {
        val waiting = members.count { it.connected && it.readyState.tone(true) == PartyReadyTone.Working }
        if (waiting > 0) "Everyone is finding their source · $waiting to go" else "Everyone is finding their source"
    }
    WatchPartyStage.ready_to_launch -> "Everyone is ready"
    WatchPartyStage.playing -> "Playing together"
}
