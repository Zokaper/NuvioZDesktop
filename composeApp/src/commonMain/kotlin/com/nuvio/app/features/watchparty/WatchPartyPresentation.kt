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
enum class PartyReadyTone { Ready, Working, Failed, Offline }

/** The wire name for [PartyReadyTone], as the native player's controls layer classes its pills. */
val PartyReadyTone.wireName: String
    get() = when (this) {
        PartyReadyTone.Ready -> "ready"
        PartyReadyTone.Working -> "working"
        PartyReadyTone.Failed -> "failed"
        PartyReadyTone.Offline -> "offline"
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

fun WatchPartyParticipant.readyTone(): PartyReadyTone = readyState.tone(connected)

/**
 * The short, human label for a readiness state.
 *
 * Deliberately terse and lower-case-by-default: it is rendered inside a pill, where sentence case
 * and a trailing ellipsis fight the shape. The player used to render `readyState.name` with the
 * underscores swapped for spaces - "Choosing fallback" - which said the same thing in the codebase's
 * words rather than the viewer's, and disagreed with the lobby beside it.
 */
fun SourceResolutionState.readyLabel(): String = when (this) {
    SourceResolutionState.joined -> "no source yet"
    SourceResolutionState.waiting_for_host -> "waiting for host"
    SourceResolutionState.fetching -> "finding source"
    SourceResolutionState.resolving -> "picking a source"
    SourceResolutionState.choosing_fallback -> "choosing alternate"
    SourceResolutionState.source_ready -> "source ready"
    SourceResolutionState.buffering -> "buffering"
    SourceResolutionState.ready -> "ready"
    SourceResolutionState.failed -> "no source found"
    SourceResolutionState.left -> "left"
    SourceResolutionState.disconnected -> "disconnected"
}

/**
 * What a member's pill says, given that a lost connection hides whatever they last reported.
 */
fun WatchPartyParticipant.readyLabel(): String =
    if (!connected && readyState != SourceResolutionState.left) "disconnected" else readyState.readyLabel()

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

/** The one line under the title that says what the party as a whole is doing. */
fun WatchPartyState.stageHeadline(): String = when (effectiveStage()) {
    WatchPartyStage.lobby -> "Waiting in the lobby"
    WatchPartyStage.waiting_for_host_source -> "Waiting for the host to pick a source"
    WatchPartyStage.resolving_sources -> {
        val waiting = members.count { it.connected && it.readyState.tone(true) == PartyReadyTone.Working }
        if (waiting > 0) "Everyone is finding their source · $waiting to go" else "Everyone is finding their source"
    }
    WatchPartyStage.ready_to_launch -> "Everyone is ready"
    WatchPartyStage.playing -> "Playing together"
}
