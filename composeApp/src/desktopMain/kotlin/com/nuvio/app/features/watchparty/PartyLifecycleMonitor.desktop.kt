package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import co.touchlab.kermit.Logger
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Desktop has no Away of its own, and giving it one from window focus would be a bug.
 *
 * ⚠ **`appForeground` is deliberately always true, and `core/sync/AppForegroundMonitor.desktop.kt`
 * is the reason it is not reused here.** That actual answers `Background` the moment the window stops being the active
 * one, which is exactly right for pausing a sync poll and exactly wrong for this: alt-tabbing to a
 * browser, or the player window simply losing focus to a notification, would tell the whole party
 * that this person had walked away from a film that never stopped playing. There is no desktop
 * equivalent of pressing Home - the window keeps rendering and the video keeps running - so the
 * honest answer is that this member is always present.
 *
 * Desktop is still a full participant in Away: it reads the away roster off the host's tick, shows
 * away members as `Away` rather than as buffering or offline, holds the party for them when it is
 * hosting with "Pause for away users" on, and waits out an away mobile guest when it is a guest
 * itself. Only the *production* of a local Away is absent, and every decision that consumes one is
 * the same shared code mobile runs.
 *
 * If desktop ever needs one, the signal is a deliberate act - minimising, or a screensaver or lock
 * - and not focus. That is a new fact to report here, not a new rule in `PartyPresence.kt`.
 *
 * What desktop *does* have is a resume: waking from sleep. See [DesktopPartyLifecycle].
 */
@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle {
    val facts by DesktopPartyLifecycle.facts.collectAsState()
    return facts
}

/**
 * A laptop lid closed mid-party (unhappy paths W1, 2026-11-02).
 *
 * Sleep froze the process with the player and the party clock wherever they were. On wake the video
 * carried on from its old position while the party had moved on, and nothing re-read the party until
 * the SDK's heartbeat found the dead socket and the realtime recovery got round to its refresh. Mobile
 * has this edge for free - every resume bumps [PartyPlatformLifecycle.resumeRevision] and refreshes -
 * and desktop never produced it.
 *
 * Detected as a wall-clock gap between ticks of a short poll ([suspendedGapMs]); the JVM gets no
 * power-event callback without native code. A wake bumps `resumeRevision`, which re-runs the
 * player's readiness reconcile, and refreshes the durable party so the player re-aligns from the
 * authoritative row at once instead of after realtime recovers. The socket is deliberately left to
 * the SDK: `WatchPartySyncTransport` keeps recovery ownership there, and forcing a second reconnect
 * from here would race it.
 */
internal object DesktopPartyLifecycle {
    private const val TICK_MS = 2_000L
    private val log = Logger.withTag("WatchPartyLifecycle")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "nuvio-party-wake-monitor").apply { isDaemon = true }
    }
    private val _facts = MutableStateFlow(PartyPlatformLifecycle(appForeground = true))
    val facts = _facts.asStateFlow()

    @Volatile
    private var started = false
    private var lastTickWallMs: Long? = null

    fun start() {
        if (started) return
        started = true
        executor.scheduleWithFixedDelay({ runCatching { tick() } }, 0L, TICK_MS, TimeUnit.MILLISECONDS)
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        val gap = suspendedGapMs(lastTickWallMs, now, TICK_MS)
        lastTickWallMs = now
        if (gap != null) onWake(gap)
    }

    private fun onWake(gapMs: Long) {
        val partyId = WatchPartyRepository.uiState.value.party?.id
        log.i { "wake gapMs=$gapMs party=${partyId.shortId()}" }
        WatchPartyDiagnostics.transport("desktop-lifecycle", partyId, realtime = "lifecycle",
            detail = "event=wake gapMs=$gapMs")
        _facts.update { it.copy(resumeRevision = it.resumeRevision + 1L) }
        if (partyId != null) scope.launch { WatchPartyRepository.refresh() }
    }
}

/** A tick this much later than scheduled means the process was suspended, not merely busy. */
internal const val DesktopSuspendGapMs = 10_000L

/**
 * How long the process was frozen between two ticks [intervalMs] apart, or null if it was not.
 * A wall clock that went backwards (a time sync) is not a sleep.
 */
internal fun suspendedGapMs(previousWallMs: Long?, nowWallMs: Long, intervalMs: Long): Long? {
    if (previousWallMs == null) return null
    val late = nowWallMs - previousWallMs - intervalMs
    return late.takeIf { it > DesktopSuspendGapMs }
}
