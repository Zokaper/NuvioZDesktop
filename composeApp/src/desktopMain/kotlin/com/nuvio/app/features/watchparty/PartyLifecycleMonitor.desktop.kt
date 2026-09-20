package com.nuvio.app.features.watchparty

import androidx.compose.runtime.Composable

/**
 * Desktop has no Away of its own, and giving it one from window focus would be a bug.
 *
 * ⚠ **Deliberately constant, and `core/sync/AppForegroundMonitor.desktop.kt` is the reason it is
 * not reused here.** That actual answers `Background` the moment the window stops being the active
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
 */
@Composable
internal actual fun rememberPartyPlatformLifecycle(): PartyPlatformLifecycle =
    PartyPlatformLifecycle(appForeground = true, screenLocked = false)
