# Nuvio Z Status

Last updated: 2026-09-03

> **The history moved.** Everything before 2026-08-24 is in [`STATUS-ARCHIVE.md`](STATUS-ARCHIVE.md) -
> 34 sections, kept whole and in order. This file is the live handoff only: the
> state table above, the work since the last release, and what is still open below.

## Watch Together: the host picks, then the host starts (2026-09-03)

Publishing the source and starting the party were the same act, and a guest was thrown at the
player the instant the host tapped a release. That is where the limbo came from, and it is also not
the flow anyone wanted: the host had no moment between choosing a release and committing five
people to it. The two are now two presses, and everybody leaves the lobby off one signal.

### The flow

1. **Choose source** (host) — `party_begin_source_selection` bumps the generation and says "the host
   is picking", then the source list opens. It opens as a *manual* launch (`manualSelection = true`
   on the `StreamLaunch`) whatever the host's playback mode is: Instant used to pick a release for
   them and publish it before they had seen one, and Streamlined asked them about their own
   connection instead of showing them the releases.
2. **The pick is staged, not published.** `commitPartySelection` writes it to
   `WatchPartyUiState.stagedHostSource` and pops back to the lobby. Nothing reaches the party, so
   nobody moves. The lobby then reads "Source picked — start when everyone is here" with
   **Start watching** / **Change source**.
3. **Start watching** — begin (bumps the generation again, so re-starting the same source is still a
   new launch) then `party_select_source`. That is the *only* thing that starts a party.
4. One `LaunchedEffect` in the lobby sees `stage == resolving_sources` with a fingerprint and sends
   **everyone, host included**, to `StreamRoute` with `RESOLVE_PLAYBACK`. It used to be two paths -
   the host walked itself there from the button handler while guests were pushed by the snapshot -
   and they drifted apart. The launch latch lives in `WatchPartyRepository.claimSourceLaunch`, not
   in the lobby's composition, which is destroyed when the player goes on top of it: a latch held
   locally was gone by the time somebody backed out, and the effect threw them straight back in.

No server change. The staged pick is deliberately local to the host until Start.

### Why the guest sat on a blank overlay

`isPartyResolvePlayback` decides `AutoPick` to borrow that branch's silent progress overlay, because
a member resolving the host's fingerprint has no question to answer. Everything *else* keyed on
`AutoPick` means "Instant is measuring the connection to choose a source", and none of it is true
here. The one that mattered is the stall backstop, which stands down for an unsettled connection
probe - a probe this route never runs, so its "not settled" was permanent. Every silent return in
`openSelectedStream` (no playable URL, an external handler that refused, a debrid resolve that
failed) therefore rested on the overlay for good, with the exact-match effect already latched.

`isPartyResolveRoute` in `entry<StreamRoute>` now tells the backstop, the connection probe, Instant's
metered dialog and the overlay's "measuring your connection" line that this is not that route.
`giveUpToSourceList` also reports `choosing_fallback`, so a host gated on readiness stops waiting on
somebody who is now reading a list of alternates.

### Verified

Two instances, two profiles (`big z` host, `debug` guest), against production
`pzbpghmmordvzcfbayoh`. Host created the party, guest joined by code, host chose a 2160p BDRemux and
came **back to the lobby**, guest stayed in the lobby reading "waiting for the host to pick a
source". Start put both in the player on the same file; both reported `ready`, `status=playing`,
`durationMs=6519808`.

`:composeApp:desktopTest` passes.

### Local runs: two things this machine cannot build

Neither is caused by the change above; both bite any local run.

- **`WebView2Loader.dll` is missing**, so `player_bridge.dll` will not load at all and every play
  fails with `NoClassDefFoundError: NativePlayerBridge`. `prepareWindowsPlayerRuntime` copies the
  loader only `if (windowsWebView2LoaderDll.exists())`, i.e. only with the WebView2 NuGet package
  installed (`-Pnuvio.webview2.dir`). Any Evergreen copy on the machine works - it is a stable-ABI
  redistributable - dropped into `composeApp/build/native/windows` and
  `composeApp/build/generated/desktop-app-resources/windows/native/windows`.
- **The bridge itself is stale** (no MSVC here), so it has no `seekToExact` entry point and the JNI
  lookup threw `UnsatisfiedLinkError` out of a party barrier - a modal error dialog over a film that
  was otherwise playing perfectly in sync, once per correction.
  `NativePlayerController.seekToExact` now falls back to the keyframe seek and logs once.

### Running two instances

`scratchpad/run-instance.ps1 -AppData <dir> -Tag <name>`. `createDistributable` needs jpackage, which
the Android Studio JBR has none of, and `:composeApp:run` holds the Gradle project lock for the life
of the app, so a second instance started that way waits forever on the first. The script runs `java`
against a dumped runtime classpath (`scratchpad/dump-cp.gradle.kts`, task
`:composeApp:dumpDesktopRuntimeClasspath`) and gives each instance its own `%APPDATA%` -
`DesktopStorage.resolveAppDataDir()` reads it, and two instances sharing one store race on the same
`nuvio_*.properties`. Seed the second from the first so it starts signed in, then switch it to the
other profile in-app.

`scratchpad/grab-window.ps1` screenshots a window without foregrounding it, `click.ps1` clicks into
one, `tile-windows.ps1` puts them side by side. All three call `SetProcessDPIAware` first: this
display scales, and without it the shell is virtualised, so every rectangle is short by the scale
factor and every click lands up and to the left of its target.

## Watch Together and Social desktop UX overhaul (2026-09-02)

Desktop Watch Together now has an additive source-preflight model: explicit lobby/preflight
stages, source generations, exact/alternate source reporting, presentation-safe participant data,
and enabled stream-addon signatures containing manifest ID/version only. New host RPC seams begin
selection and commit/reselect a credential-free fingerprint with an expected generation; older
snapshots continue to decode through defaults. The externally maintained Supabase project must
deploy the documented additive fields and RPC behavior before this UI is released.

The party room now shows artwork and episode identity, a prominent invite code, addon mismatch
warnings, participant cards with role/connection/source state, and a stage-aware host action. Start
opens the configured Classic, Streamlined, or Instant source path directly, without returning
through details. Guests resolve the host fingerprint locally, then fall back through their own
playback mode; cached-link and external-player shortcuts are disabled for party launches. Starting
a party from active playback captures its safe source fingerprint, position, and speed.

Desktop player controls now receive party membership, role/status, control mode, connection state,
and transport permission. A right-side party panel follows control visibility; host-only guests see
transport, seek, speed, and episode navigation disabled while local subtitle/audio/recovery and exit
actions stay available. The old floating player action and standalone detail-page party buttons are
gone; details use the Play overflow and the native player header owns the in-player action.

Social now has a profile header, bounded responsive desktop layout, a two-column Watching Now /
Recent activity dashboard at wide widths, and shared artwork-rich episode/activity chips reused on
Home. Existing inbox, invitations, friend search/management, privacy, offline, error, and empty-state
surfaces remain integrated below it.

**Verified:** `:composeApp:compileKotlinDesktop` succeeds; targeted desktop tests for addon privacy,
fingerprint/readiness behavior, disconnect blocking, and launch gating pass; native controls
JavaScript passes `node --check`. A complete `:composeApp:desktopTest` run was attempted but its
test worker did not terminate, so the targeted suite was run cleanly after stopping only that stale
worker.

## A guest was seeked to the end of its own film and pinned there (2026-09-02)

Second run, after the flapping fixes. Sync held; the seeks landed exactly - `seek landed
targetMs=1708767 landedMs=1708767 tookMs=2` and the same for 2616248 and 4855987. Then, while the
host scrubbed around, **the guest's player jumped to the end of the movie and stayed there.** The
guest was on another machine, so its log is not here; the host's is
`nuvio-debug-20260902-204023.log` and it settles two of the three faults on its own.

**1. Every party position was clamped into `0..durationMs - 1`.** Seven call sites did it, and the
clamp is the bug: it turns "the party is somewhere this copy does not reach" into "go to the last
frame", and the last frame is where a player stops. Worse, it is self-confirming - the drift
correction then compares a position clamped to the end against a target clamped to the same end,
measures no error, and agrees forever. A guest whose file is a different cut, or whose duration has
not settled on a stream that is still opening, was therefore parked on the credits with the
correction loop insisting it was in sync. A position past the end is not a position; it is now
refused and logged (`position unreachable targetMs=... durationMs=... overMs=...`), and the player
holds where it is under a banner.

**2. Nothing ever compared the members' durations.** `arePartyDurationsCompatible` has been in
`WatchPartyModels.kt` since the feature landed, with a test and **no caller**, and
`resolvedDurationMs` is reported by every member and read by nobody. Sources are resolved
independently by design, so a member on a different cut is an ordinary outcome and was completely
unflagged until a seek dumped them at the end. The player now compares its own duration against the
host's resolved one and says so.

**3. A seek resumed a paused party.** `partyBarrierPlan` returned `playAfter = true` unconditionally
for play *and* seek - the field was a constant - and `executePartyBarrier` ignored it and played
anyway. So scrubbing a paused film started it again on every member, which is what looking for a
scene does over and over. In the log: `pause src=user positionMs=7841` at 20:41:52.2, `issue
kind=seek posMs=1708767` at 20:41:53.9, and `play` at 20:41:54.2. `PartyCommand` now carries
`playAfter` on the wire (defaulting true, so a command from a build without it behaves as that build
did), a paused seek skips the barrier lead and the overshoot it was carrying, and the executor obeys
it.

**Also, from the same log:** the host played and then paused 268ms later - `gate released ... by=allReady`
at 20:41:35.326, `play src=gate`, then `waiting for f662ef78` at 20:41:35.608. The guest had reported
`buffering` for the ten seconds it spent resolving its own source, which is what the readiness gate
is for and is not a stall; left in the window it became a hold the instant the gate opened.
`WatchPartySync.resetStallWatch()` now clears that evidence when the party starts playing.

**And two bounds that were missing:** the desktop `skipInterval` handler seeked to a marker's end
time unclamped, while the shared overlay's own handler has always clamped - marker data from a
different cut routinely runs past the file. And `submitPartySeek` now bounds every position it
broadcasts by the sender's own duration, because a scrub bar, a skip marker and a ten second step off
the end all pass through it and one overshoot is sent to every member.

**Verified:** `:composeApp:desktopTest` passes, no failures. New coverage for a paused seek not
resuming, a paused seek ignoring the delivery overshoot, the wire default for a command without the
field, and the field surviving a round trip.

**Not** verified: no run against these changes, and the guest half of the diagnosis is inference -
the clamp and the missing duration check are proved by reading, the resume-on-seek by the host's log,
but *which* of them put that particular guest on the last frame is not. The guest's
`nuvio-debug-*.log` from that session would say: look for `position unreachable`, or for a
`barrier kind=seek` whose `seekToMs` equals its `durationMs - 1`.

## The guest flapped because four separate things below the sync layer were wrong (2026-09-02)

The first two-instance run against the rebuilt timing plane. The timing plane itself is fine - the
guest locked the host clock in three exchanges, `tickAgeMs` held around 130ms and `offsetMs` stayed
within +-2ms all session - and the guest still paused and resumed continuously and never converged.
Four faults, none of them in the arithmetic. Host log `nuvio-debug-20260902-184136.log`, guest
`nuvio-debug-20260902-184143.log`.

**1. On desktop the user's play/pause never reached the party at all.** The previous entry says the
button and the spacebar "both arrive at `prepareTogglePlaybackForNativeFallback`". They do not.
`controls.js` sends `setPlaybackState` (button, `toggle.pointerdown`) and `setPlaybackStateQuiet`
(surface click, spacebar), and `handlePlayerControlsEvent` consumes both with `shouldPlay = value >=
0.5` and returns `true` - so `handlePlayerEvent` returns before `toPlayerControlsAction` is ever
consulted, and neither string is in that map anyway. `PlayerControlsAction.TogglePlayback` is not
produced for play/pause on desktop, so routing *the action* through the party fixed a path the
platform does not take. The log shows it exactly: `18:43:14.185 (NativePlayerControls) pause` with no
`issue`, no `command`, and `18:43:18.434 play`, equally silent. Guests learned about both only from
the timeline's `status`, with no barrier and no shared instant, and obeyed by seeking to the host's
frozen position and pausing. The event path now submits to the party first and falls back to
`shouldPlay` only when the party declines it.

Adjacent, and how the pause got taken in the first place: it lands 43ms after
`(SyncManager) Foreground sync started`, i.e. the window had just been activated. The bare video
surface toggled playback on **any** click, including the click that raises the window - so
alt-tabbing back to Nuvio by clicking it paused the film. The surface toggle now ignores the first
click after a blur/focus pair, bounded to 400ms so a focus the page never hears about cannot leave
the suppression latched; the play button and the spacebar are untouched.

**2. Corrective seeks landed seconds short, so the guest could not converge.** Both native bridges
seek `absolute+keyframes` under `hr-seek=no`, which lands on the nearest *earlier* keyframe - eight
or nine seconds early on this release. Three consecutive corrections aimed at 20988, 23488 and 25991
and landed at 18185 every time; the landing points across the session cluster at 9843 / 18185 /
27027 / 32766. Worse, `WatchPartyPausedAlignToleranceMs` is 100ms, which a keyframe seek can never
satisfy, so a paused guest re-issued the same seek on every tick forever - `seekTo 34034` at
18:43:35.760, 35.790 and 37.791. `PlayerEngineController.seekToExact` is new and issues
`absolute+exact`; Watch Together is its only caller, so scrubbing and the ten second skips keep the
cheap keyframe seek on cold network sources.

**3. Drift was measured against a position that is stale across a seek.** mpv's `time-pos` does not
advance while a seek is in flight and reads *backwards* to an older sample - `drift localMs` goes
17017 -> 9843 and 27561 -> 27027 in the log. Nothing tracked an outstanding seek, so each pass half a
second later measured the pre-seek position, called it a fresh gap, and seeked again further ahead:
seven corrective seeks and twenty engine seeks in two minutes. `PendingPartySeek` now records the
target and a deadline; every correction path stands down while one is outstanding and waits for the
landing, and the record expires on its own rather than needing to be cleared.

**4. A guest's own corrective hold tripped the host's stall guard.** `WatchPartySeekRecoveryLeadMs`
and `WatchPartyGuestBufferingGraceMs` were both 1500ms, and during the hold the guest reported
`buffering` - for exactly the grace. So the guard fired on every single correction and cleared
~300ms later: `waiting for d3397924` / `stalled guests recovered, resuming` at 58.490/58.806,
01.023/01.340, 05.529/05.597 and 27.610/27.638. **That pair is the flapping.** Four fixes: a client
held by the party no longer publishes a peer status at all, the grace is 2500ms and is now documented
as having to outlast the longest self-inflicted hold, a hold ends only once the guest has been
*playing* again for 400ms rather than merely no longer buffering, and a host may take at most three
holds in thirty seconds with five seconds between them before it plays on and says so.

**And the standing 200ms error was the deadband.** A proportional nudge against one hard band settles
*at* the band, which is why the log sits at `driftMs=197..199 action=NONE` for seconds at a time.
Nudging now starts at 120ms and continues until the gap is back under 60ms, the nudge window is 2.5s
rather than a 5s that the +-10% cap made unreachable, and the seek threshold is 1s now that a seek
lands where it was aimed.

Every pause the host takes now names its origin - `pause src=user|stall-guard` - and the guest's peer
status and the host's acceptance of it are both logged on change. A pause with no origin is what cost
this session most of its time.

**Verified:** `:composeApp:desktopTest` passes, no failures - including the six watchparty suites
(barrier 15, timeline 15, clock 9, models 8, pending-seek 2, protocol 2). New coverage for the drift
hysteresis converging below the entry band, the nudge window actually being able to close the band it
owns, the grace outlasting a corrective hold, a hold ending only on a settled `playing`, a hold being
abandoned after 30s, the hold budget's cooldown and limit, and the pending-seek landing and timeout.
`WatchedItemsStoreTest` did not flake in this run.

**Not** verified: no two-instance run against these changes yet. macOS carries the same
`seekToMillisecondsExact`, but its `positionMs` returns an optimistic cached value written at seek
time, so the landing check passes instantly there rather than measuring - the guard is correct but
inert on that platform until that cache is driven from `time-pos`.

**Test it from the debug MSI, not from `desktopRun`.** The exact seek is in `player_bridge.cpp`, and
this machine has no MSVC C++ toolchain, so the local bridge cannot be rebuilt - `desktopRun` would
load the DLL sitting in `build/native/windows`, which is from 2026-08-17. That skip was silent:
`buildWindowsPlayerBridge` carried `onlyIf { !output.exists() }`, so **any** change to the bridge has
been invisible to every local run since. It now prints a warning naming the stale DLL when the source
is newer. The debug workflow builds from a clean checkout, so debug build 37 has the real bridge.

**Next:** two instances of the debug MSI, with **separate data directories**, because both instances
currently share `%APPDATA%\Nuvio Z Debug` and race on the same `nuvio_*.properties`. Launch the second
from a shell with `APPDATA` moved - `DesktopStorage.resolveAppDataDir()` reads it:

```powershell
# Seed the guest's store from the host's so it starts signed in, then launch it there.
# The host launches normally from the Start menu.
Copy-Item "$env:APPDATA\Nuvio Z Debug" "C:\Temp\NuvioZGuest\Nuvio Z Debug" -Recurse -Force
$env:APPDATA = "C:\Temp\NuvioZGuest"
& "C:\Program Files\Nuvio Z Debug\Nuvio Z Debug.exe"
```

The guest then writes its log to `C:\Temp\NuvioZGuest\Nuvio Z Debug\logs\` and cannot race the host
over a settings file. Switch it to the second profile in-app so the two are different members.

What the logs have to show: an `issue`/`command` pair for every host play/pause and no
`NativePlayerControls pause` without one; `seek landed` within 250ms of its target and no repeated
seek to the same position; no `waiting for` / `recovered` pair unless a stream genuinely stalls; and
`driftMs` settling under 120ms with `action=NONE` dominating. The HUD's `errMs` is the same number
live.

One thing the logs cannot settle: the activating-click suppression is armed by the WebView's own
`blur`/`focus` pair, and if the embedded controls never see those events it is inert rather than
wrong. Check it by hand - click away to the other window, click back onto the *video*, and the film
must keep playing; clicking the play button or pressing space must still work immediately.

## Watch Together was five seconds behind, and the play button was why (2026-09-02)

The first real two-device run worked: party created, both clients joined, each resolved its own
source, both waited, playback started. It was also about five seconds out of sync in both senses -
a pause took that long to reach the other member, and the two streams sat that far apart.

The guest-side debug log settles what it was, and it is not what it looked like. The channel was
healthy the whole time:

    realtime party=44f8bcca state=connected
    clock offsetMs=-369 bestRttMs=284

So realtime was up, the server clock offset was measured, and the round trip was 284ms. But across
the whole session the party sequence goes 0, 1, 2 and then never moves again - while the status
keeps changing, `playing` to `paused` to `playing` to `paused`. A status change with no sequence
bump can only come from `party_heartbeat`, which does `status = coalesce(p_status, status)`.

**The host was pressing pause and no command was being sent.** On desktop the play/pause button and
the spacebar both arrive at `prepareTogglePlaybackForNativeFallback`, which flipped `shouldPlay` and
returned - the native controls layer performs the transport itself. `togglePlayback` is the only
function that ever called `submitPartyPlayPause`, and nothing on desktop calls it. The host's pause
therefore reached the party only when its next five second heartbeat happened to carry the new
status: 0-5s, mean 2.5s. That is the whole report. `prepareSeekByForNativeFallback` had the same
omission, so a host skipping forward moved only itself.

Three further faults the same log shows, all of which made it worse:

- **A stalled host published a deliberate pause.** The heartbeat mapped "neither playing nor
  loading" to `paused`, so a starved source told every member to pause and seek to a frozen
  position. It now reports the *intent*: `shouldPlay` and not playing is `buffering`, not `paused`.
- **A guest seeked on every host stutter.** The `buffering` branch realigned whenever it was more
  than 500ms out, and `expectedPartyPositionMs` freezes for any non-playing status, so that test
  passed almost every time. It now holds position and waits.
- **The drift policy could not converge.** A fixed 1.03x recovered 300ms over its ten second hold
  against a band admitting 2,500ms, so every drift that mattered escalated to a seek - and the log
  shows exactly that, drift climbing 192, 2152, 7249, 8219 rather than closing. The nudge is now
  proportional to the gap and capped at +-10%, the band reaches 4s, the blocking hold is gone, and
  a corrective seek leads by the resume cost so it does not land where the party already was.

Also landed: the broadcast now carries the playback state so a guest applies it without a second
round trip, refreshes are coalesced so a burst cannot build a queue whose depth is the latency, and
a status change publishes within a round trip instead of at the next tick.

Server side, `202609020001_party_broadcast_state.sql` puts `sequence`, `status`, `position_ms`,
`playback_speed`, `state_updated_at` and a `server_time` stamp in the payload - projected field by
field so no `invite_code_hash` can reach the wire - and splits the member trigger so a bare
`last_seen_at` bump broadcasts nothing. That bump was 83% of all party broadcast traffic measured
against the live project: 2,336 of 2,800 messages, arriving in bursts of four to six per second.

**Verified:** `:composeApp:desktopTest` passes apart from the known `WatchedItemsStoreTest`
concurrency flake, which fails at HEAD on its own and is untouched by this work. New coverage for
the drift bands, the proportional nudge and its cap, and the seek lead applying only when behind.

**Not** verified: the migration has not been run - Docker was unavailable, so `scripts/test-db.sh`
could not reset a local stack - and it is not pushed, so the broadcast payload path is inert until
it is. No two-device run has happened against these changes.

**Next:** run `scripts/test-db.sh`, push the migration, then a two-device run in both directions.
`ageMs` on the new broadcast line is the propagation latency; a `command` line on the host log is
the thing whose absence caused this.

## The social surface now talks to Nuvio Z's own backend (2026-09-01)

Nuvio Z has its own Supabase project for social and Watch Together. Accounts, profiles and all base
user data stay on the **official** Nuvio backend, so a Z install remains cross-compatible with
vanilla Nuvio. The social schema previously foreign-keyed `public.profiles`, which lives in
NuvioMedia's project and which we have no administrative relationship to; the feature was only
deployable by them. See **The Two Backends** in the canonical `AGENTS.md` in `nuvio-z` - the rule
that matters is that nothing ever deploys to theirs.

Desktop is wired first, deliberately, so one real exchange proves the shape before mobile copies it.

- `ZSupabaseProvider` is a second Supabase client. It installs no fallback-endpoint retry: the
  official client has one because playback and sign-in are fatal to lose, whereas the social surface
  degrades to hidden.
- `ZSessionBridge` performs the token exchange, caches the session per profile, and re-exchanges once
  when a Z token is rejected. The session is never loaded from disk, because it is derived from
  whichever official session is live.
- `SocialRepository` and `WatchPartyRepository` now talk exclusively to the Z client. The official
  client keeps playback and sign-in.
- Realtime is gated on a live Z session: both `social:` and `party:` are private channels authorized
  by RLS on `realtime.messages`, so the socket must carry the Z token rather than the publishable key.
- `ktor-client-core` is now a `commonMain` dependency. The exchange must set `Authorization` to the
  *official* token, which a Supabase client would otherwise overwrite with its own.

Endpoints come from the ignored `local.properties` as `NUVIO_Z_SUPABASE_URL` and
`NUVIO_Z_SUPABASE_PUBLISHABLE_KEY`. Blank leaves `ZSupabaseConfig.isConfigured` false and every social
surface hidden, so a build without them is valid rather than broken.

Not yet verified: no client has completed a real exchange against the live project.

## Social and Watch Together ported from `nuvio-z` (2026-09-01)

This repository now carries the shared `features/social` and `features/watchparty` packages, a
`DesktopStorage`-backed `SocialStorage` actual, `Realtime` installed on the Supabase client, the
`WatchPartyLobbyRoute` and lobby screen, Home social rows, the details and player Watch Together
entries, and the watched-activity publish/remove hooks. Both backend capabilities default off, so an
undeployed or older server disables every new surface cleanly.

Three divergences from mobile are deliberate. Social is added *beside* the Downloads sidebar entry
rather than replacing it, because the desktop sidebar has no slot pressure and Downloads is already
`AppFeaturePolicy`-gated here; the mobile Library Downloads shortcut is therefore not ported. The
Watch Together entry is inserted into both of this repository's mutually exclusive detail layouts.
`iosApp/` is vestigial here (there is no iOS workflow in this repository) and its Swift tab enum was
left untouched, so `NativeNavigationTab.Social` has no native counterpart on this side.

The full plan is checked in as `Docs/SOCIAL-WATCH-TOGETHER-PLAN.md` in `nuvio-z`. Backend migrations
are still undeployed and unexecuted, so nothing here has been exercised against a live server.

## Apple Silicon debug DMG is published on the debug line (2026-08-31)

`desktop-debug-release.yml` now treats Windows x64 and macOS arm64 as one debug publication:
metadata is resolved once, both packages must build and pass their platform checks, and only then
is the single `debug-v*` prerelease created. A failed Mac build therefore cannot burn a debug tag
or leave a Windows-only release under a version that claims to cover both platforms.

The Mac job uses GitHub's native `macos-15` arm64 runner. It runs the release-selection tests, compiles the
Objective-C++ WebKit/AppKit player bridge, bundles the arm64 libmpv closure and TorrServer, builds
an unsigned debug DMG, mounts it, checks the debug bundle identity and binary architectures,
rejects build-machine library paths, and keeps the packaged app alive for a short launch smoke
test. `AppUpdateAssetSelectionTest` also covers selecting the arm64 DMG from a prerelease that
contains both Windows and Mac assets; the focused desktop test passes locally and on the hosted
Apple Silicon runner (6 tests). The full macOS suite also reached 1,299 passes; two Windows-oriented
download fault-injection cases timed out because their deliberately broken local sources completed
normally on macOS, so those unrelated E2E harness cases are not part of the package gate.

The first dispatch exposed a repository-level blocker before either platform compiled: GitHub
reported that this repository had exceeded its LFS bandwidth budget. The already-tracked runtime
payloads are now mirrored in the private draft `desktop-runtime-v1` release, which is invisible to
both application update feeds. Each workflow job downloads only its architecture and verifies a
pinned SHA-256 before extraction, so CI no longer depends on mutable or exhausted LFS bandwidth.

Hosted run `33377789548` passed both Windows and macOS jobs and published prerelease
`debug-v0.5.0-beta.20` from exact commit `02883ff2`. During the hosted checks, the first DMG exposed
an absolute build-machine install name in `libplayer_bridge.dylib`; the linker now emits the portable
`@rpath/libplayer_bridge.dylib` identity, and the rebuilt package passed the mounted-app dependency
check. The final DMG is 217,701,079 bytes with SHA-256
`d86a078cc8fb6d12e9b395ca9d83a6f839b61fc7661cec9c28953f3650e97ca6`.

This confirms native compilation, packaging, DMG integrity, arm64 architecture, portable player
linkage, bundled TorrServer and startup on a hosted Mac. A real Apple Silicon Mac remains the gate
for interactive WebKit/libmpv playback, seeking, audio/subtitle tracks, fullscreen, next episode
and P2P. The DMG is intentionally unsigned, so Gatekeeper requires **Privacy & Security → Open
Anyway** once; signing/notarisation remains a later distribution task.

## Desktop native player controls restored after the 0.1.20-alpha sync (2026-08-31)

The refreshed native desktop player page removed its lock-controls markup, but merge `e649ff75`
retained a `lockedLabel` dereference and an `isLocked` condition in `controls.js`. The first
`render()` therefore threw before the page could send `controlsReady`. With the native bridge never
receiving that handshake, playback commands appeared dead, the timeline stayed at `00:00`, and the
opening artwork/logo state was never rendered. The two stale lock references are removed while
preserving upstream's newer player UI. `NativePlayerControlsPageTest` now checks that every element
requested with `getElementById` exists in `controls.html`, covering this merge failure directly.
The focused Gradle desktop test passes and `controls.js` passes Node's syntax check. The full
desktop suite was also started, but its unrelated end-to-end harness remained silent for several
minutes and was stopped rather than reported as a pass.

## NuvioDesktop 0.1.20-alpha is synced (2026-08-24)

Merge `e649ff75` brings in named upstream release `0.1.20-alpha` (`b32dd57b`) while preserving
Nuvio Z's playback watchdog, network-quality settlement, next-episode chain and desktop player
controls. The full local desktop test task passed. Build-only run `32781339968` then compiled the
final hosted tree, built the Windows MSI, verified it and uploaded it.

Upstream's new Sentry integration originally made its credentials mandatory in the release
workflow, but this fork has none. Commits `5338b72a` and `c1dfe4b4` make those values optional and
skip only the source-bundle upload when absent; the app and MSI still build normally with an
explicit warning. Current drift against `upstream/Dev` is **193 ahead / 162 behind**, patch surface
**144**, conflict surface **44**.

## The numbering bridge is published (2026-08-24)

Stable `0.5.0-beta+126` is live. Release run `32777297995` built and verified the Windows MSI,
published its checksum file, and tagged the exact release commit `ee193661`. The bridge ranks above
`0.4.14-beta` for old updaters and carries the serial-aware updater required before adopting the
synced vanilla version as `<vanilla>-z1`. Full desktop CI run `32775960554` passed the desktop suite
and MSI packaging at the same commit.


## Connection figure deadline race fixed and confirmed (2026-08-24)

The five-second deadline and the real probe used to write one settle nonce. When the deadline won,
the sheet published its stored link-type guess; the probe then landed and replaced it under the
reader. The new import-free `ConnectionProbeSettlement` separates the two answers: the deadline
settles Instant's bounded automatic decision, while only probe completion settles the figure shown
in the quality sheet. Monotonic nonces prevent a late older re-test from regressing the current ask.
Three ordering cases are in the pure suite. Both repos pass **290 pure tests, 0 failures**. The
reporting handset confirmed `.24` shows one fixed 541 Mb/s value, compared with 497 Mb/s from Ookla;
the former late replacement is gone. Debug packages carrying the fix are mobile
`debug-v0.4.14-beta.24` (run `32735072649`) and desktop `debug-v0.4.14-beta.17` (run `32735073128`).


## KMP About names the vanilla base (2026-08-24)

Settings → About now derives the vanilla base from the Z version name through the import-free
`core/build/NuvioZVersion.kt` policy. `0.6.0-z2` and debug `0.6.0-z2.3` both name Nuvio `0.6.0`;
the bridge and pre-scheme versions show no base rather than guessing. Three tests cover the rule,
and `scripts/run-pure-suites.sh` passes **287 tests, 0 failures**. The focused local Gradle attempt
exceeded its bounded runtime and was stopped; Compose wiring remains a CI gate.

## Pending / Follow-up

### NEXT: make a download behave like a Netflix download

**This is the current priority, and it is the standard to hold the work to.** A
download in this app should be as boring and as certain as one in Netflix: you
start it, you can reorder it, pause it, resume it, close the app, lose the
network, come back tomorrow - and it either finishes or tells you plainly why it
cannot. No row that stops moving. No state only a restart can leave. Nothing that
needs the user to know what a debrid link is.

The harness in `NuvioZDesktop`
(`composeApp/src/desktopTest/.../DesktopDownloadQueueE2ETest.kt` and
`FaultyMediaServer.kt`) is where that gets proven. It now covers the local,
deterministic parts of items 1-3 below: queue controls under load and across a
repository reload, provider failures and controls during them, byte identity
across re-mint, and provider readiness immediately before transfer. The harness
was extended first and reproduced every production fault fixed in this pass.
The real-account and real connectivity-transition work in item 4 remains.

**1. The queue controls, under load - covered locally.** Every one of these
cancels a running transfer, and cancelling is what the stranding bug came out of.

- Reorder while transferring: move to top, up, down, to bottom; the promoted item
  starts at once and the preempted one keeps its `.part` file and resumes from
  where it stopped rather than restarting.
- Pause and resume, by hand, mid-transfer and mid-retry-backoff. A user pause is
  sticky - it must survive a queue nudge, a reclaim sweep and an app restart, and
  must never be undone by the recovery paths.
- Cancel and delete mid-transfer, including the last item and the only running
  one; files and `.part` files actually go.
- Reorder, pause and resume *while a fault is in flight* - during the re-mint
  round trip, during a backoff, in the window where a cancelled transfer is
  reporting its last word. That window is exactly where the fixed bug lived, and
  the other three controls reach it the same way the reclaim sweep did.
- Close and reopen: a queue that was mid-transfer comes back in the same order,
  from the same bytes, with user pauses still paused. `loadFromDiskLocked` has
  never been exercised against a queue in a real intermediate state.

**2. Provider failures - covered locally except a real connectivity observer
transition.** `FaultyMediaServer` and the re-mint stand-in now fail on demand:

- a link that expires *mid-transfer* rather than before it starts, at 20% and
  again at 90%;
- re-minting that fails once, then succeeds; that fails every time (the download
  must end `Failed` with a message a human can act on, not retry forever);
- a re-minted link that points at a *different or truncated* file - `If-Range`
  and the overrun/short checks should catch it rather than silently corrupting
  the `.part` file;
- the provider timing out or hanging rather than answering - re-mint runs off the
  lock while holding a slot, and nothing bounds it today;
- 429 and 5xx from the provider, and the whole account failing (every call 401)
  while a season batch is in flight;
- the network dropping entirely and coming back, which on desktop only
  `NetworkStatusRepository` reports.

**3. Cached-on-the-debrid, checked immediately before transfer - implemented and
covered through the provider seam.** This was the weakest link behind "download
queued" placeholders reaching the disk.

Today readiness is whatever the *addon* claimed at selection time
(`SourceFacts.isDebridReady` from `aio.debridCached` / `clientResolve.isCached`),
consulted once in `PresetSourceSelector` and only when `preferCachedSources` is
on. Nothing ever asks the provider directly, and nothing re-checks between
planning a season and reaching episode 9 an hour later. The placeholder check
(`isImplausiblySmallForMedia`) is the only real defence and it is *post-hoc* - it
downloads the wrong file first, then retries on a 1-to-10-minute backoff.

The queue now bypasses the resolver's fifteen-minute success cache and asks the
provider again **before every debrid transfer starts**. Not-cached sources wait
without touching the media URL, provider uncertainty retries with a visible
reason, dead accounts fail plainly, and a placeholder that arrives after a
successful check is still rejected. Cached, not cached, cached-then-evicted,
provider unsure, and post-check placeholder outcomes all have harness cases.

**4. Prove it against a real account - still pending.** The local server cannot imitate provider
quirks, which is where every fault so far has come from. Run the same queue
against TorBox with `NUVIO_DOWNLOAD_TEST_URLS`, and run a real season batch left
going long enough to cross the fifteen-minute link window - that is the only
thing that exercises re-minting for real, and it has still never been done.

Whatever this turns up: fix it in `nuvio-z` and mirror to `NuvioZDesktop`, keep
the harness green in CI on both, and record here what was covered and what was
found. A fault reproduced in the harness is worth more than a fix argued for in a
commit message.

### Preset/discovery work: code complete, release not cut

All five planned pieces have landed. `4ba89f7`/`59fa2ecb` carried the first
three; `55e8ccb` (nuvio-z) and `d74779f2` (NuvioZDesktop), both on
`claude/status-md-continuation-tkc41p`, carry the last two. What is done:

- Per-preset `sizePreference`: `Balanced`/`Quality` take the largest source that
  still fits the cap, `Saver` keeps taking the smallest. This reversed the old
  behaviour, which sorted size ascending and so picked the *smallest* under the
  cap.
- Per-preset `preferCachedSources` (default on). `SourceFacts.isDebridReady` is
  now its own tie-break below every quality key, so cached never costs a
  resolution tier, and an uncached debrid winner is sent to review instead of
  started.
- `PresetDownloadDialog` no longer awaits preparation or blocks dismissal.
- A Preparing section in `DownloadsScreen.kt`, above review, driven by batches
  with any entry still `DISCOVERING`/`RESOLVING`: artwork, title, a
  "Finding sources · 4 of 13" count, a progress bar and per-episode state. A
  batch is held *out* of the review section while it is still preparing, so the
  user is not asked to review a list that is still growing.
- `DownloadsLiveStatusPlatform.onBatchesChanged(batches)` with all four actuals
  (android and ios in both repositories, desktop in `NuvioZDesktop`), and an
  ongoing low-priority Android notification while any batch is preparing. It is
  called from every batch mutation as well as from `publishLocked`: preparation
  moves through `saveBatch`/`updateBatchEntry`, which never touch the item list,
  so hanging it off item changes alone would show nothing for the whole
  discovery pass.
- The unreachable in-dialog review branch is gone from `PresetDownloadDialog`,
  along with the `batch`/`error`/`approveUnknown` state and the `onQueued` and
  `onChooseManually` parameters behind it.

Remaining:

1. **Smoke-test preparation on-device.** Start a season batch and confirm the
   Preparing section fills in episode by episode, that the ongoing notification
   appears and clears, and that the batch moves to review or straight to the
   queue when discovery finishes.
2. **Check the desktop in-app update path.** `0.1.20-alpha` is installed on a
   Windows machine and launches with a responsive main window and no matching
   Application event-log crash. The actual `0.1.19-alpha` to `0.1.20-alpha`
   in-app update path has not been exercised.

### Latest release: CI verified, runtime testing pending

Two changes shipped in `0.3.8` / `0.1.21-alpha`. The merged release branches
passed Android host tests/debug assembly in run `30944119268` and desktop tests/
Windows MSI assembly in run `30944124462`. Publish runs `30944744977` and
`30944920882` then built and published the signed APKs and verified MSI. They
have not been runtime-smoke-tested. On 2026-08-04 the release was explicitly
approved without an Android device; device verification remains a post-release
follow-up.

The former `claude/status-md-continuation-tkc41p` branches are merged. The code
below is released from `main` / `Dev`.

#### (a) The two missing preset controls

`4ba89f7`/`59fa2ecb` added `preferCachedSources` and `sizePreference` to
`DownloadPreset` and wired them into `PresetSourceSelector`, but **never added
editor UI**, so they were stuck at their built-in defaults and the user could not
reach them. Added to `PresetSettingsCard` in `DownloadsSettingsScreen.kt`:

- a row that toggles `sizePreference` between `LARGEST_UNDER_CAP` and `SMALLEST`;
- a `Prefer cached sources` switch for `preferCachedSources`.

Four new strings in both `strings.xml` files:
`download_preset_size_preference`, `download_preset_size_largest`,
`download_preset_size_smallest`, `download_preset_prefer_cached`.

Both fields are already `@Serializable` on `DownloadPreset` and go through
`DownloadsRepository.updatePreset`, so persistence needed no change.

#### (b) Series page and Downloads page disagreeing (reported bug)

**Symptom.** Delete everything from the Downloads tab, then open the series page:
episodes still show download states - some "downloading", some "downloaded".

**Cause.** `buildTitleDownloadState` (`DownloadPresence.kt`) layers batch entries
underneath persisted items, items winning. The old `publishLocked` only synced an
entry when a matching item still existed (`?: return@map entry`), so deleting a
download left its batch entry frozen at `DOWNLOADING`/`COMPLETED`/`QUEUED`
forever. With the item gone the detail screen fell through to that stale entry.
The Downloads tab looked correct because it renders items, not entries.

**Fix as written.** A new pure `reconcileBatches(batches, items)` in
`DownloadBatches.kt`, called from both `publishLocked` and `loadFromDiskLocked`:

- an entry with a matching item follows that item's status, as before;
- an entry in an *item-backed* state whose item is gone becomes `CANCELLED`,
  which `toPresence()` already maps to `DownloadPresence.None`;
- a batch whose entries are now all `CANCELLED` is dropped entirely;
- `isItemBacked` covers `QUEUED`, `DOWNLOADING`, `PAUSED`, `COMPLETED` and
  **deliberately excludes `FAILED`**, because discovery failures and queueing
  failures land there with no item ever created, and those entries must stay in
  review so the user can still pick a source by hand. The trade-off: deleting a
  *failed* download leaves the episode reading as failed until the batch is
  dismissed. Left as-is on purpose; revisit only with a way to tell the two
  failures apart.

Calling it from `loadFromDiskLocked` is what heals **installs that are already
broken**, including the reporter's device - it reconciles on the next launch
rather than waiting for the next queue change. That path also had to widen its
persist condition to `normalized != stored.items || reconciledBatches !=
stored.batches`.

`DownloadBatchReconcileTest` (8 tests) covers the delete cases, the `FAILED`
carve-out, idempotence, and the empty-batch case. It ran successfully in both
CI suites above.

#### Next steps, in order

1. **Smoke-test the bug fix when a device is available**, because this is a
   persistence fix and no test touches real storage: queue a season, let some
   episodes finish, delete everything from the Downloads tab, reopen the series
   page and confirm every episode reads as not downloaded; then force-stop,
   relaunch, and confirm it still does.
2. **Exercise the desktop updater** from the installed `0.1.20-alpha` to
   `0.1.21-alpha`; merely launching `0.1.20-alpha` did not verify replacement.


- No Gradle task can configure in this sandbox: `dl.google.com` is denied by
  the egress policy, so the Android Gradle Plugin never resolves. CI is the only
  compiler available here, which makes each fix a full release-run round trip.
  Run `.\gradlew.bat :composeApp:testAndroidHostTest` locally to get the host
  suite, including the new `DownloadPresenceTest`, actually executed.
- The download transfer/queue rework **compiles** - CI built and published
  `0.3.6` from it - but its behaviour is still unverified. Only the two new
  pure-logic files have executing tests (see Verification); the repository, the
  three platform downloaders and the screen have never been run. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` locally to execute the host
  suite, which CI's assemble-only release job never runs.
- Smoke-test the reworked transfers on-device with a deliberately small file:
  pause/resume mid-transfer, resume after the source URL has expired (must not
  report a completed download at the partial size), process death mid-transfer,
  background/foreground on iOS, and a season batch to confirm E01 starts first and
  that "Download next" preempts.
- The unwatched-season download work has **not** been compiled or tested in this
  environment either: the sandbox blocks `dl.google.com`, so the Android Gradle
  Plugin cannot be resolved and no Gradle task can configure. Run
  `.\gradlew.bat :composeApp:testAndroidHostTest` and an `assembleFullDebug`
  locally before trusting it.
- Smoke-test the unwatched season download on-device: open a partly watched
  season, use the season download menu, and confirm only the current episode
  onwards is queued.
- Smoke-test the downloads redesign on-device: confirm the Downloads tab appears
  in the classic, adaptive and tablet nav bars; queue one small episode and check
  that the episode card ring, the tab's “Downloading now” row, and pause/resume
  stay in sync; confirm the “Downloaded” section appears on the entry once the
  transfer completes and disappears after deleting.
- `onBatchesChanged` is a no-op on iOS and desktop. The iOS bridge publishes one
  live item to Swift and a second payload needs matching Swift work; desktop has
  no notification surface at all. Both show preparation in the Downloads tab.
- A batch cannot be cancelled while it is preparing, on any platform. See the
  Work Log entry for why the obvious button would lie.
- The iOS Downloads tab currently falls back to the `arrow.down.circle.fill` SF
  Symbol. Add a `NuvioTabDownloads` xcasset to match the other tab icons.
- Existing profiles get the new meta-screen “Downloaded” section appended last in
  their saved section order, because `normalizePreferences` sorts unknown keys to
  the end. New profiles get it right after Actions.
- The local workspace directory is still named `stremio-z`; renaming it is
  deferred.
- Run the full host suite again after the next substantial code change.
- Test a real transfer end-to-end, including pause/resume, process death,
  network constraints, and cap-crossing approval, using a deliberately small
  file.
- Review lifecycle/cleanup for prepared batches dismissed from the review
  dialog so cancelled all-ready batches do not remain as hidden persisted
  records.
- Trakt functionality requires local client credentials and has not been
  reconfigured for this personal build.
- iOS parity gaps in the preset download feature, all in platform seams:
  `freeStorageBytes()` returns `-1` so low-space warnings and
  storage-triggered review never fire; `allowMeteredNetwork` is ignored
  because the iOS session hardcodes cellular access; downloads pause on
  app background because iOS uses a foreground `NSURLSession`.
- `DownloadsStorage.ios.kt` no longer profile-scopes its payload key,
  unlike every other iOS storage and unlike the desktop fork. Decide
  whether that de-scoping was intended.
- Desktop CI cannot be verified from a sandbox that blocks `dl.google.com`;
  the Android Gradle Plugin will not resolve there.
- `0.3.6` (versionCode 105) is released from `main` and is the first build to
  carry the download transfer/queue rework. `assembleFullRelease` succeeded, so
  the merged redesign and rework compile together; nothing in the rework has
  been exercised on a device yet.
- Queue reordering has a known rough edge: the needs-attention section is
  filtered out of the queue list, so a Move up/down that would swap with an
  attention item looks like it did nothing. "Download next" is unaffected.
- `Zokaper/nuvio-z` is public, which the unauthenticated updater requires.
  `0.3.7` (versionCode 106) is the current release; `0.3.6`, `0.3.5`, `0.3.4`
  and `0.3.3` precede it. All carry signed APKs for all four ABIs.
- CI release signing is stable: `0.3.3`, `0.3.4` and `0.3.5` all carry signer
  certificate SHA-256
  `2325A3399F9BBF5ECE1391EBE6B5A0E0F016058520FB1597B1CF30CF6184787C`.
  A locally built APK signed with a different keystore cannot be updated over
  by these releases, and Android reports only "App not installed". The installed
  build's version identifies which key it carries, because `0.3.3` and later
  exist only as CI output.
- The earlier "App not installed" in-app update failure is **resolved**: the
  in-app update from `0.3.5` to `0.3.6` succeeded on the Samsung device. It was
  the signing-key mismatch rather than Auto Blocker - once the installed build
  came from CI, later CI-signed releases update over it cleanly. A locally built
  APK still cannot be updated over by a CI release, so a local build has to be
  uninstalled first.
- `NuvioZDesktop` desktop releases are now Windows-only. Every macOS job failed
  at "Configure desktop runtime" because the repository holds none of the Apple
  signing and notarisation secrets it requires, so the target choice was
  narrowed to `windows`; the macOS job is still in the workflow behind a guard
  that can no longer match. Restoring macOS means adding the secrets and
  putting the options back.
- Compiling the desktop mirror for the first time found that the redesign added
  a `downloads` parameter to the `publishNativeTabTitles` expect and updated the
  Android and iOS actuals but not the desktop one. Fixed in `NuvioZDesktop`.
  A Windows build of the pre-redesign commit compiles, which is what identified
  the redesign mirror rather than the transfer rework as the source.
- The desktop Windows job now runs `compileKotlinDesktop` as its own step
  without `--stacktrace`, because packaging with it buried the compiler's `e:`
  lines under roughly 250 lines of Gradle internals.
- `NuvioZDesktop` compiles and produces a verified MSI in CI. `0.1.20-alpha` is
  the current release and `0.1.19-alpha` (2026-08-03) precedes it, each carrying
  one Windows x64 MSI and a `SHA256SUMS.txt`. `0.1.20-alpha` is installed and
  launches on Windows; the in-app replacement flow is still untested.
