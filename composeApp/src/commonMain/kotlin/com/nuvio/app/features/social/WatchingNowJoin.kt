package com.nuvio.app.features.social

import co.touchlab.kermit.Logger
import com.nuvio.app.core.ui.NuvioToastController
import com.nuvio.app.features.watchparty.WatchPartyRepository
import com.nuvio.app.features.watchparty.WatchPartyState
import com.nuvio.app.features.watchparty.WatchPartyStatus
import com.nuvio.app.features.watchparty.currentEpochMs
import kotlinx.coroutines.delay

/**
 * Joining a friend from Watching Now, end to end.
 *
 * ⚠ **Hardware Bug 6 (2026-09-15): Ask to join and Join both did nothing.** Traced against the
 * deployed backend (`pg_get_functiondef` on `social_join_watching`, `social_notification_action`,
 * `party_promote_presence_internal`, `social_get_state_v2`, and the trigger list), the flow was
 * dropped in four separate places, any one of which was enough:
 *
 *  1. **Home's button was not wired.** `homeSocialSections` defaulted `onStartParty` to `{}` and Home
 *     never passed one, so the button no RPC ever left. Production held exactly one
 *     `watch_join_requests` row, from 2026-09-12 - the hardware run created none.
 *  2. **Every failure was silent.** The Social tab's handler had `onSuccess` and no `onFailure`, so a
 *     refused call (`friendship_required`, a missing capability, a decode failure) looked exactly
 *     like a button that does nothing.
 *  3. **A guest was never told it had been accepted.** `social_notification_action` adds the
 *     requester to the party server-side and returns the snapshot to the *host*. Nothing reaches the
 *     requester: there is no notification kind for it, and `watch_join_requests` has no invalidation
 *     trigger. The guest sat on "Join request sent" while already a member of a party it did not know
 *     it was in.
 *  4. **A host was never told a direct join promoted its playback.** `social_join_watching` builds
 *     the party from the host's presence row and adds the guest, and returns the party only to the
 *     guest. `party_promote_presence_internal` writes no social table, so no invalidation reaches the
 *     host either: the guest went to a lobby for a party whose host kept watching alone.
 *
 * None of these needs a backend change. Delivery to the host rides the presence heartbeat the host
 * is already sending; discovery on both sides is `party_get_active`, which is scoped to the caller's
 * own profile and so answers for a membership the client did not create itself.
 */
sealed interface WatchingNowJoinStep {
    /** The server made this member part of a party: open its lobby, which starts playback. */
    data class OpenParty(val party: WatchPartyState) : WatchingNowJoinStep

    /** A request is waiting on the host. Watch for this member being added. */
    data object AwaitApproval : WatchingNowJoinStep

    /** Nothing more will happen; say why. */
    data class Notice(val message: String) : WatchingNowJoinStep
}

fun decideWatchingNowJoin(result: Result<SocialActionResult>): WatchingNowJoinStep {
    val action = result.getOrElse { failure ->
        return WatchingNowJoinStep.Notice(
            failure.message?.let(::joinFailureMessage) ?: "Couldn't join. Check your connection and try again.",
        )
    }
    val party = action.party?.takeIf { it.status != WatchPartyStatus.ended }
    return when (action.outcome) {
        "joined", "already_joined", "accepted" ->
            party?.let(WatchingNowJoinStep::OpenParty)
                ?: WatchingNowJoinStep.Notice("Couldn't open the party. Try again.")
        "approval_required" -> WatchingNowJoinStep.AwaitApproval
        "disabled" -> WatchingNowJoinStep.Notice("This playback is not open to joining")
        "stale" -> WatchingNowJoinStep.Notice("This playback is no longer available")
        "full" -> WatchingNowJoinStep.Notice("This party is full")
        "unsupported_contract" -> WatchingNowJoinStep.Notice("Watch Together needs an update to join this playback")
        else -> WatchingNowJoinStep.Notice("Couldn't join. Try again.")
    }
}

private fun joinFailureMessage(raw: String): String? = when {
    "friendship_required" in raw -> "You can only join friends' playback"
    "Watch Together update required" in raw -> "Watch Together needs an update to join this playback"
    else -> null
}

/**
 * How long a guest watches for acceptance. `social_join_watching` inserts the request with the
 * table's two-minute `expires_at`; a few seconds past it covers a host who accepts at the last moment.
 */
const val WatchingNowApprovalWatchMs = 125_000L

/** How often a waiting guest asks whether it has been added. One cheap, profile-scoped RPC. */
const val WatchingNowApprovalPollMs = 3_000L

sealed interface JoinApprovalPoll {
    data object Continue : JoinApprovalPoll
    data class Joined(val party: WatchPartyState) : JoinApprovalPoll

    /** This member is already in a party some other way. Stop watching, and change nothing. */
    data object Superseded : JoinApprovalPoll

    /** The request expired unanswered or was declined - the server does not say which. */
    data object GaveUp : JoinApprovalPoll
}

/**
 * One tick of the guest's wait for approval.
 *
 * A probe that failed is not an answer - the network blinked - so it continues until the deadline.
 * A party the member was already holding before the probe is left alone: whatever put them there,
 * it was not this request, and dragging them to another lobby would corrupt that party's state.
 */
fun decideJoinApprovalPoll(
    elapsedMs: Long,
    heldLivePartyId: String?,
    probe: Result<WatchPartyState?>,
    timeoutMs: Long = WatchingNowApprovalWatchMs,
): JoinApprovalPoll {
    if (heldLivePartyId != null) return JoinApprovalPoll.Superseded
    val active = probe.getOrNull()?.takeIf { it.status != WatchPartyStatus.ended }
    if (active != null) return JoinApprovalPoll.Joined(active)
    return if (elapsedMs >= timeoutMs) JoinApprovalPoll.GaveUp else JoinApprovalPoll.Continue
}

/**
 * The guest's wait for a join request to be accepted.
 *
 * Nothing on the backend tells a requester it was let in (see the file header), so the guest asks
 * `party_get_active` - scoped to its own profile - until it is a member, the request has expired, or
 * it has ended up in a party some other way. Run on the caller's scope, so [onJoined] navigates on
 * the thread that owns navigation.
 */
suspend fun awaitJoinApproval(onJoined: (WatchPartyState) -> Unit) {
    val startedAt = currentEpochMs()
    while (true) {
        delay(WatchingNowApprovalPollMs)
        val held = WatchPartyRepository.uiState.value.party?.takeIf { it.status != WatchPartyStatus.ended }
        when (
            val poll = decideJoinApprovalPoll(
                elapsedMs = currentEpochMs() - startedAt,
                heldLivePartyId = held?.id,
                probe = WatchPartyRepository.fetchActive(),
            )
        ) {
            JoinApprovalPoll.Continue -> Unit
            is JoinApprovalPoll.Joined -> {
                joinLog.i { "join request accepted party=${poll.party.id.take(8)}" }
                onJoined(poll.party)
                return
            }
            JoinApprovalPoll.Superseded -> {
                joinLog.i { "join request wait superseded by party=${held?.id?.take(8)}" }
                return
            }
            JoinApprovalPoll.GaveUp -> {
                joinLog.i { "join request not accepted within ${WatchingNowApprovalWatchMs}ms" }
                NuvioToastController.show("Your join request wasn't accepted")
                return
            }
        }
    }
}

private val joinLog = Logger.withTag("SocialJoin")

/**
 * Whether a playing host should look for a party it did not create itself.
 *
 * Only while it is publishing joinable presence and holds no live party: that is exactly the state a
 * direct join (or an approval accepted on another surface) turns into "hosting", without telling it.
 */
fun shouldDiscoverPromotedParty(policy: WatchJoinPolicy, heldParty: WatchPartyState?): Boolean =
    policy != WatchJoinPolicy.disabled && (heldParty == null || heldParty.status == WatchPartyStatus.ended)

/**
 * Whether a playing host should refresh its social state on this heartbeat, so a join request reaches
 * it. Requests only exist under approval, and nothing on the backend invalidates the host when one is
 * inserted; the heartbeat is the delivery. Held party or not: a second friend can ask to join a party
 * the first request already created.
 */
fun shouldPollForJoinRequests(policy: WatchJoinPolicy): Boolean = policy == WatchJoinPolicy.approval
