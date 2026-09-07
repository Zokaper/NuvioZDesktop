# Nuvio Z Status

Last updated: 2026-09-07

## Phase 2 follow-up: Seamless desktop player handoff (2026-09-07)

Branch `claude/phase-2-desktop-handoff`.

Implemented the Phase 2 follow-up for desktop-only seamless player startup and exit handoffs:

1. **Part A: Source → Player Startup & Airspace Gating (`StreamDestination.kt`, `PlayerScreenRuntimeUi.kt`, `PlayerEngine.desktop.kt`, `NativePlayerHost.kt`, `NativePlayerController.kt`):**
   - Elevated the existing Phase 2 loading surface (`PlaybackLoadingController` / `PlaybackLoadingHost`) across all 3 playback modes (Classic, Streamlined, Instant).
   - In `StreamDestination.kt`, candidate selection opens the persistent loading session immediately upon user action, displaying candidate facts and artwork before route navigation or debrid link resolution begins.
   - Handed off the active loading token into the player route via `loadingToken?.let(PlaybackLoadingController::handOff)`.
   - Prevented failover attempt 2 reload stutter in `PlayerScreenRuntimeUi.kt`: kept `openingOverlayWanted = true` during fatal playback errors when failovers are active (`args.onFatalPlaybackError != null`), ensuring the loading screen persists seamlessly across automatic candidate failovers without snapping or recreating.
   - **Native Airspace Gate & White Screen Resolution:**
     - The heavyweight Win32 child HWND (`NativePlayerHost`) is kept concealed (`isVisible = false`) across source resolution, player creation, buffering, and retries (attempt N -> N+1).
     - **Resolved White Screen Regression:** Compose's `SwingPanel` wraps the child Canvas in an internal `SwingInteropViewGroup` (`JPanel`) that is opaque and white by default (`RGB(240, 240, 240)` on Windows), placed above Skia, with Compose punching a clear hole via `BlendMode.Clear`. Unconditional `Modifier.fillMaxSize()` prior to first frame occluded Compose with a blank white client area.
     - **Size-Gated Concealment:** `SwingPanel` modifier is now parked at `Modifier.align(Alignment.BottomEnd).requiredSize(1.dp)` while unpromoted (`!isNativeSurfacePromoted()`). It transitions to `Modifier.fillMaxSize()` strictly when `PlaybackHandover.hasFirstFrame` fires and `promoteNativeSurface()` is called.
     - Coupled controller promotion state to Compose via `onSurfacePromotedChanged`, resetting immediately to concealed / 1.dp on `releaseBeforeNavigation` (`T3`), `attach`, and `dispose`.
     - Promotes native surface (`promoteNativeSurface()`) and child container (`NativePlayerBridge.promoteOpeningContainer`) strictly when the active attempt produces a real decoded video frame (`PlaybackHandover.hasFirstFrame`).

2. **Part B: Player → Previous Screen Exit & EDT Stall Elimination (`NativePlayerController.kt`, `NativePlayerHost.kt`, `PlayerExitDiagnostics.kt`, `NetworkQualityPlatform.desktop.kt`):**
   - Instrumented timestamped diagnostics across the entire exit pipeline:
     - `T0`: Escape/back action received (`requestBack` or `systemBack`).
     - `T1`: Navigation pop requested (`rememberGuardedPlayerPopBackStack`).
     - `T2`: Previous Compose destination drawn (`DetailsDestination` / `StreamDestination`).
     - `T3`: Native player surface detached/hidden (`NativePlayerHost.removeNotify` / synchronous EDT conceal).
     - `T4`: Native player teardown completed in background thread.
   - Synchronously concealed native surface on Swing EDT (`host.isVisible = false`, `T3`) at `T0` inside `releaseBeforeNavigation`, guaranteeing `T3` occurs at 0 ms before `T1` (pop) and `T2` (previous destination draw at ~16 ms), completely eliminating the ~1.5s `#0D0D0D` dark gray screen occlusion window.
   - Decoupled navigation pop from native player teardown: native player disposal runs in the background thread `nuvio-player-release` (`T4`).
   - Fixed EDT freeze in `NetworkQualityPlatform.desktop.kt`: offloaded the ~891 ms synchronous Windows PowerShell network probe to a background daemon executor guarded by `AtomicBoolean`, returning cached values or fallback immediately (< 1 ms).
   - Moved snapshot polling off the Swing EDT to `Dispatchers.IO` in `PlayerEngine.desktop.kt` with adaptive polling intervals (50 ms before first frame, 500 ms steady-state).
   - Added native video dimensions querying via JNI (`NativePlayerBridge.videoWidth` and `videoHeight`), populating `snapshot.videoWidth` and `videoHeight` for precise first-frame detection.

3. **Part C: Player → Previous Destination Direct Exit on Intentional Escape / Back (`NuvioNavigator.kt`, `StreamsRepository.kt`, `AppShellComponents.kt`, `PlayerDestination.kt`):**
   - **Root Cause of Exit Loading Loop:** For launches where `StreamRoute` was retained specifically to host the auto-play failure chain (`[DetailRoute, StreamRoute, PlayerRoute]`), pressing Escape/Back popped `PlayerRoute` back to `StreamRoute`. `StreamRoute` resumed with preserved state, evaluated `showLoadingSurface = true`, acquired a new `PlaybackLoadingController` token (`token=2`), and re-executed `StreamsRepository.load`, trapping the user on the loading screen until a second Escape.
   - **Atomic Retained-Route Bypass:** Added `NuvioNavigator.popPlayerExit(expectedRoute, skipRetainedStreamRoute)`. When `skipRetainedStreamRoute = true` and `StreamRoute` immediately precedes `PlayerRoute`, atomically removes both destinations via `backStack.subList(backStack.size - 2, backStack.size).clear()`, returning smoothly to the previous real destination (`DetailRoute`) in a single logical pop without ever composing `StreamRoute`.
   - **Preserved Failover Chains & Manual Source Selection:**
     - Fatal playback errors (`onFatalPlaybackError`) continue calling standard `popBack()`, retaining `StreamRoute` as top destination to advance the automatic failover retry chain.
     - Exposed `StreamsRepository.isManualSourceRequestPending`: clicking "Choose source manually" in the player sets this flag, causing `skipRetainedStreamRoute` to evaluate to `false` and uncovering `StreamRoute`'s source list as requested.
   - **State Cleanup on Intentional Exit:** In `PlayerDestination.kt`, intentional user exit triggers `StreamsRepository.abandonAutoPlay()`, `StreamsRepository.cancelLoading()`, and closes any active `PlaybackLoadingController` session, preventing any stale state resurrection.

4. **Verification & Artifacts:**
   - Pure test suites outside Gradle: 459 tests passed clean across all 6 groups (`scripts/run-pure-suites.sh`).
   - `NativePlayerAirspaceGateTest`: all 4 tests passed (`:composeApp:desktopTest`).
   - `NetworkQualityPlatformDesktopTest`: all 3 tests passed (`:composeApp:desktopTest`).
   - `NativePlayerControllerTeardownTest`: all 23 tests passed (`:composeApp:desktopTest`).
   - `PlayerExitOrderingTest`: all 4 tests passed (`:composeApp:desktopTest`).
   - `PlayerExitNavigationTest`: all 6 tests passed (`:composeApp:desktopTest`), asserting direct pop to preceding destination on intentional exit, `StreamRoute` retention on fatal error, manual source request retention, auto-play state cleanup, loading overlay suppression, non-stream route fallback, and route guard mismatch.
   - Packaged release-style Windows MSI with debug tools:
     `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,299,679 bytes) built with `"-Pnuvio.desktop.debugTools=true"`.

## Phase 2 manual-verification finding: Startup watchdog evidence-of-life deadline (2026-09-06)

Branch `claude/phase-2-playback`.

Following Phase 2 manual/diagnostic verification of playback failover, resolved false-positive timeouts where healthy-but-slow sources were prematurely abandoned at 20s despite showing credible evidence of life (parsed container duration, HTTP probe response, demuxer buffer):

1. **Two-Tier Startup Deadline (`PlaybackStartupWatchdog.kt`):**
   - Retained `NO_PROGRESS_DEADLINE_MS = 20_000L` for completely dead sources with no evidence of life.
   - Added `EVIDENCE_OF_LIFE_DEADLINE_MS = 35_000L` (`SLOW_STARTUP_DEADLINE_MS`) for slow-starting sources that have demonstrated credible evidence of life but have not yet advanced playback position.
   - Preserved `STALL_DEADLINE_MS = 12_000L` and `MAX_STARTUP_MS = 60_000L`.
2. **Evidence-of-Life Signals (`PlaybackStartupWatchdog.kt`, `PlayerScreenRuntimeEffects.kt`):**
   - Candidate handoff or URL existence alone explicitly does not constitute evidence of life.
   - Evidence of life is credited when `durationMs > 0L`, `bufferedPositionMs > 0L`, `progressMs > 0L`, or `hasExternalEvidenceOfLife` (successful HTTP probe `PlaybackProbeVerdict.Pass`).
   - Sticky state in `PlaybackStartupWatchdog.State`: once credible life is established, the 35s deadline protects the startup until first position advance or 35s timeout.
   - Added diagnostic logging for evidence-of-life transitions and watchdog abandon events.
3. **Regression Tests (`PlaybackStartupWatchdogTest.kt`):**
   - Dead sources abandoned at 20s.
   - Slow-but-alive sources with duration or probe evidence not abandoned at 20s.
   - Slow-but-alive sources starting between 22–30s succeed without false failover.
   - Sources with evidence of life that fail to advance by 35s properly abandon.
   - Stall deadline (12s) preserved after initial progress.
   - Strict deadline hierarchy enforced: `STALL_DEADLINE_MS < NO_PROGRESS_DEADLINE_MS < EVIDENCE_OF_LIFE_DEADLINE_MS < MAX_STARTUP_MS`.
4. **Verification & Packages:**
   - Desktop tests passed clean: all 19 tests in `PlaybackStartupWatchdogTest` passed (`:composeApp:desktopTest`).
   - Packaged local diagnostic Windows MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,258,719 bytes) built with `-Pnuvio.desktop.debugTools=true`.

## Phase 2 manual-verification finding: 8K AI upscale & CAM/TS ranking and presentation (2026-09-06)

Branch `claude/phase-2-playback`.

Following Phase 2 manual/watched playback verification, corrected the selector and presentation logic for AI-upscaled releases (particularly nominal 8K streams) and theatrical captures (CAM/TS):

1. **AI Upscale Classification (`ReleaseTags.kt`, `SourceFacts.kt`):**
   - Added conservative `isAiUpscaled` detection for release title tokens (`AI Upscale`, `AI-Upscaled`, `AI Enhanced`, `AI Remastered`, `Topaz`, `Upscaled`, etc.).
   - Exposed on `SourceFacts` (preserving desktop regex cache) and pure-suite neighbour stubs.
2. **Display Capability Propagation (`Platform.kt`, `Platform.desktop.kt`):**
   - Added `platformDisplayMaxHeight()` to detect desktop monitor height via AWT screen devices and passed through `PlaybackSelectionContext` into `SourceRankingPreferences`.
3. **Automatic Ranking (`SourceRanking.kt`):**
   - `resolutionTier`: When display max height is below 8K (< 4320p), 8K and 4K fold into the same resolution tier (tier 5). On displays >= 8K, 8K native retains tier 6.
   - `mediaScore`: Added `AI_UPSCALE_PENALTY` (-8) and `CAM_TS_PENALTY` (-20).
   - Both Instant and Best Available inherit the model; proper 4K releases now comfortably beat nominal 8K AI upscales on 4K-or-lower displays.
4. **CAM/TS Demotion and Restrained Treatment:**
   - CAM/TS releases assigned `THEATRICAL_CAPTURE_TIER = -1`, ensuring any standard release (even SD) wins over CAM/TS, while preserving selectability if CAM is the sole available source.
5. **Restrained UI Presentation (`PlaybackQualitySheet.kt`):**
   - Added `AI Upscale` chip with restrained danger styling (`tokens.colors.danger` at 12% alpha bg, 35% hairline border) without interfering with HDR/DV/Atmos feature chips.
   - Set CAM/TS provenance text to restrained danger color without adding duplicate badges.
6. **Verification & Packages:**
   - Passed regression test Cases A through G (`SourceRankingTest.kt`), `ReleaseTagsTest.kt`, and `PlaybackQualityOptionsTest.kt`.
   - Pure test suites (453 tests) passed clean outside Gradle.
   - `.\gradlew.bat desktopTest` passed clean (BUILD SUCCESSFUL).
   - Packaged Windows MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,250,529 bytes).

Branch `claude/phase-2-playback`.

### UltraReview #1 remediation
All six review findings from UltraReview #1 are integrated on desktop:
1. **Finding 1 (P2P auto-play failover):** Propagated `autoPickedWithFailureChain` for P2P auto-play in `StreamDestination`, kept `StreamRoute` on back stack during active failure chain, updated `lastHandedOffLabel`, and reset `autoPickFailure`.
2. **Finding 2 (P2P external subtitles):** Propagated `stream.externalSubtitles` into `buildP2pPlayerLaunch` so P2P retains external subtitles.
3. **Finding 3 (Manual choice routing):** Routed choose-manually paths in quality sheet and uncached stream dialog through canonical `giveUpToSourceList` to preserve provenance and surface rules.
4. **Finding 4 (Loading escape clock guard):** Added token guard to `PlaybackLoadingSessions.tick` so superseded session escape clock coroutines cannot contaminate subsequent sessions.
5. **Finding 5 (Pure stream label fallback):** Removed `runBlocking` and Compose resource lookup from `StreamModels.kt`, providing pure stream label fallback and passing localized strings at Compose call sites.
6. **Finding 6 (Canonical P2P sentinel helper):** Extracted canonical `p2pSentinelUrl` helper to `StreamModels.kt` and eliminated duplicate definitions.

Also synchronized with mobile's back-press lifecycle fix (`autoPlayStream` null check moved into retry branch in `StreamDestination` so back navigation exits cleanly to details instead of being dropped).

### Product decision: P7 deleted
**P7 (auto source-swap / automatic downshift)** will not ship and is completely removed:
- Detector (`AutoDownshiftDetector.kt`), candidate builder (`AutoDownshiftCandidates`), and 334-line test suite removed.
- Diagnostic swap log (`SwapDiagnosticsLog.kt`, `SwapDiagnosticsLogTest.kt`) and HUD forced-swap controls removed.
- Setting keys (`playback_auto_downshift`), storage actuals, repository state, and settings page UI removed; leftover descriptions cleaned up.
- **Normal ranked-candidate failover remains completely intact**: automatic candidate failover across Classic/Streamlined/Instant, P2P failure chains, fatal playback error handling, manual fallback, and dead-source reporting are all preserved.

### Verification status
- Non-device verification (pure test suites: 459 passed, desktop compilation, desktopTest: 489 passed) passes clean.
- **Manual verification passed on desktop and packaged builds** (2026-09-06) following the two-tier startup watchdog evidence-of-life fix. Exit gate passed; Phase 2 closed.

## Ultra 1 review record (2026-09-05)

Branch `claude/phase-2-playback`. Three PRs exist and only one of them is for merging; the
distinction matters enough to write down.

| PR | What it is | Scope |
| --- | --- | --- |
| [#2](https://github.com/Zokaper/NuvioZDesktop/pull/2) | **The real PR.** Base `codex/upstream-sync-0.1.22-alpha` | 24 commits, 86 files, +7,749/−1,907 |
| [#3](https://github.com/Zokaper/NuvioZDesktop/pull/3) | **Review scaffolding.** `ultra1-audit` over `ultra1-audit-base` | 38 files, +13,009/−0 |
| [nuvio-z#1](https://github.com/Zokaper/nuvio-z/pull/1) | Mobile's `/code-review high` companion | 73 files, +6,110/−1,790 |

⚠ **Run `/code-review ultra 3`, not `ultra 2`, and never the no-argument form.** `Dev` does not
carry the upstream sync - `git merge-base --is-ancestor f0107940 origin/Dev` answers no - so the
no-argument form bundles **383 commits, 518 files, +41,951/−11,390**, most of it vanilla upstream.

### Why the scaffolding exists

`ultra` reads a diff. Against #2 it would have read **23 of the 56** files in the playback
surface, and `PlaybackModeRouter`/`PlaybackModeModels` at their edges rather than whole - which
`ROADMAP.md` predicted in the paragraph under the Ultra 1 line. The 33 it would not have seen
include `StreamAutoPlaySelector` (239 lines), **Classic's picker**, reached from
`StreamsRepository.load` whenever `manualSelection` is false. One of the three modes would have
selected its source through code the review could not read.

#3's diff is therefore the whole playback-mode system. Built as two commits off the phase branch:
`ultra1-audit-base` deletes the 38 files, `ultra1-audit` restores them byte-identically.
`git diff b50c44cd..ultra1-audit` is empty - verified at construction against the phase HEAD of
the moment - so the reviewed tree *is* that branch and only the history differs. ⚠ **Commits made
to `claude/phase-2-playback` afterwards do not reach #3.** Docs commits are harmless, since no
`.md` is among the 38 files; a code change is not. If the branch moves before the run, rebuild the
pair.

⚠ **Head descends from base deliberately.** The obvious construction - deleting the files in a
base cut from the sync branch - produces modify/delete conflicts on the 23 files Phase 2 changed.
An unmergeable PR is not something to find out about with a non-renewable run.

⚠ **This replaces the run on #2 rather than supplementing it.** The cost, taken knowingly: the
agents audit the final code and never see what Phase 2 changed, so the new work is not marked.

Excluded from #3: `StreamCard`, `StreamsTabletLayout`, the `StreamBadge` family, `EpochMs`, the
per-platform storage actuals - 18 files, 2,064 lines that cannot decide which source plays. The
quality sheet, loading screen and `StreamsScreen` are in: they carry selection logic, not just
presentation.

### Scaffolding cleaned up (2026-09-06)

Audit scaffolding PRs #3, #4, and #5 closed; all scaffolding branches (`ultra1-audit*`, `gemini/ultra1-playback-fixes`) pruned from local and remote.

### What the reviewers are reading cold

Two changes from 2026-09-05 have unit coverage and no run on a packaged build:

- `0da891c0` - back was being answered with another play. Two effects in `StreamDestination` wake
  on the same `autoPlayStream`; the abandon and the failover still share that state.
- `b50c44cd` - `ChooseManually` now signals through the repository and pops, because the route it
  must reach has stopped composing while the player is on top. Also logs a throwing
  `NativePlayerController.snapshot()`, which until now was indistinguishable from a source that
  never starts.

## Back was taken, then answered with another play (2026-09-05)

Branch `claude/phase-2-playback`. Reported as "pressing Escape mid-loading or mid-player is
jank - I had to spam it a few times to get out", plus a second report that sounded unrelated:
after escaping an Instant play, switching to Classic and returning to the same title started
the source Instant had picked. One bug, both faces.

**Escape was working. The app was restarting the source behind it.** From the z1.44 debug log:

```
22:03:54.697  PlayerControls action=Back   pos=360902        <- the Escape
22:03:55.116  loading surface token=2 closed (visibleMs=19229)
22:03:56.934  StreamsRepo Found 1 addons  (same title)
22:03:56.938  StreamsRepo Fetching streams ...               <- catalogue request starts
22:03:57.195  loading surface token=3 opened                 <- 257 ms later, already playing
22:03:57.326  attach requested ... initialPositionMs=361499
22:03:58.006  probe total=47595678623 host=store-071...      <- same file as token=2
22:03:58.578  PlayerControls action=Back   pos=0             <- the second Escape
```

⚠ **That fetch never logs a `Got ... streams` line - it was cancelled.** So the source that
relaunched came entirely from state held in `StreamsRepository`, with no catalogue in hand, and
it re-attached at 361499 ms against the 360902 ms the user had just exited at. Every press was
answered by a fresh play of the thing being escaped, which is what "spam it a few times" was.

### What was wrong

On the pop back from the player, two effects in `StreamDestination` wake on the same
`autoPlayStream`: the retry effect, which decides the user left and exits, and the auto-play
effect, which starts whatever is armed. **Whichever ran first decided what happened.**
`userAbandonedPlayback` already existed for exactly this distinction and its own KDoc said
"Read only by the stall backstop" - which was the fault, because the effect that *starts
playback* never consulted it.

The second face is `consumeAutoPlay`, which **retires** the chain into `retiredAutoPlayStream`
rather than dropping it. That is right for a source dying after the first frame and wrong for a
back press: it left the retained chain for `failOverAfterPlaybackStarted` and the live one for
`carriedAutoPlayChain`, which hands a chain back to the next load of the same request token.

### The fix

- `StreamsRepository.abandonAutoPlay()` - drops the live chain, the retired chain, and the
  pending retry signal. Deliberately not `consumeAutoPlay`; the difference is the bug.
- The auto-play effect returns early on `userAbandonedPlayback`. ⚠ Ordering two effects is not
  a fix - the abandon is a fact, and a fact outranks a race.
- `leaveToDetails()` abandons the chain and cancels the in-flight fetch, so "takes you back"
  and "stops what is running" are one action rather than two halves with one written.
- The route's `onDispose` does the same for exits the route does not own - the window closing,
  a deep link - keeping the hand-off exemption that lets the surface outlive the route.

**Verified:** `AutoPlayFailoverTest` passes on both repos - 14 tests, 0 failures - including two
new cases: an abandon leaves nothing for either mechanism, and an abandoned chain is not carried
into the next load of the same title. ⚠ The existing `a reload for the same video keeps a
re-armed chain` still passes, so the legitimate carry is intact and only the abandoned case is
cut.

**Not** verified: not yet watched on a packaged build. The effect race and the route teardown
are precisely what a hot run cannot exercise; z1.45 / mobile build 28 are cut for this.

Desktop source-to-player jank investigation continues on `claude/phase-2-playback`.
The native bridge now builds locally; a fresh run confirmed the loading scale is clamped to 1
by the controls JSON writer. See `nuviozdesktop/STATUS.md` for measurements and verification.

| | |
| --- | --- |
| Active branch | `codex/phase-3-downloads` in both KMP repositories, cut from mobile `main` and desktop `Dev` after Phase 2 merged. Phase 3 implements reliable downloads with lazy source resolution. |
| Version in the files | mobile **`0.4.13-z1`** and desktop **`0.1.22-alpha-z1`**, both with release serial **127**. |
| Released | bridge `0.5.0-beta+126`, published in both KMP repositories on 2026-08-24 |
| Next version | mobile has adopted `0.4.13-z1`. Desktop adopts `<vanilla>-z1` when its own sync to `0.1.22-alpha` lands. Note that the debug channel carries no serial, so a debug install on `0.5.0-beta.25` will not be offered a `0.4.13-z1` debug build - it needs one manual sideload. |
| Verified | the Z backend is deployed and live: 8 migrations applied to `pzbpghmmordvzcfbayoh`, `get_social_capabilities()` now returns both flags **true** - `202609010009_enable_social.sql` enabled them, and the desktop client renders the invite-code field and the Watch Together action, which it only does when `watchPartyEnabled` is set, so the earlier "both flags false" reading in this table predates that migration - direct table reads return 401, the `z-session` function is deployed and rejects every unauthenticated path correctly, and 61 pgTAP assertions pass on matching Postgres 17. Both standalone suites pass (290 tests each); focused Android host and desktop Gradle runs compile the real source sets and pass all 16 next-episode tests. Desktop Watch Together propagation measured about 225 ms in a real two-client session; the buffering-race follow-up compiles and all 1,318 desktop tests pass. Mobile CI `33327792025`, repaired desktop CI `33328140034`, mobile debug publish `33328752860` and desktop debug publish `33615211655` all pass. For the sync rework: `scripts/run-pure-suites.sh` passes all six groups (131, 64, 49, 17, 29, 42) with the new group 6 compiling the shipped sync sources and no stubs, and desktop CI `33627311248` passes the full `:composeApp:desktopTest` run and the Windows MSI build. For the UI rebuild: 1,360/1,360 desktop tests pass locally, and the social tab and the party lobby were both driven in the running app over Compose Hot Reload - the lobby across three live stage transitions. |
| **Not** verified | **Nothing in the Watch Together sync rework has run against a live party.** It compiles and both suites pass, and that is all: the party clock, the tick, the barriers and the wait-for-everyone policy have never had two machines on them. The matrix is cold start; pause and resume ten times, measuring the spread; seek ten times; a real host rebuffer; a real guest rebuffer with the toggle on and off; host migration; the socket killed mid-film; and a `debug-v0.5.0-beta.36` client against a `.35` one, which must degrade to the old five-second behaviour rather than break. Carried forward from `debug-v0.5.0-beta.35` and still open independently of the rework: every host transport action must bump `sequence`, offline Leave/End must permit a new party immediately, and the corrected next-episode transition and the desktop HTML button still need a device/install pass. Mobile is still not wired to the Z backend and has none of this; manual iOS verification remains outstanding. From the UI rebuild: the **in-player Watch Together panel has never been seen** - reaching it needs playback, and its CSS and JS are desktop resources that a Compose reload does not pick up, so it wants a deliberate restart. Nor has any **multi-member lobby state**: a one-person party cannot produce a green `ready` tile, a red `failed` tile with its error text, the alternate-source chip, or the dimming of a disconnected member, and a still cannot judge the resolving ring's animation. All of those fall out of the two-desktop matrix. |
| Next work | Build/install Phase 2 and run its watched playback matrix before release; then run the two-desktop Watch Together matrix against the current debug line with the playback HUD on. Port Watch Together to mobile only after that passes and after mobile reaches `ZSupabaseProvider`/`ZSessionBridge`; stable `0.5.0-beta+126` remains untouched. |
| Debug channel | desktop `debug-v0.5.0-beta.36` carries the sync rework, published 2026-09-02 from `claude/watch-together-sync-7ceki1`; `debug-v0.5.0-beta.35` from `f0aef43f` is the build the current sync report came from. Mobile `debug-v0.5.0-beta.25` was published 2026-08-30. Stale pre-sync desktop `debug-v0.4.14-beta.18` and mobile `debug-v0.4.14-beta.25` are superseded. |

> **The history moved.** Everything before 2026-08-24 is in [`Docs/STATUS-ARCHIVE.md`](Docs/STATUS-ARCHIVE.md) -
> 48 sections, kept whole and in order. This file is the live handoff only: the
> state table above, the work since the last release, and what is still open below.

## Phase 2 closing polish (2026-09-05)

Branch `claude/phase-2-playback`. Three closing polish designs address presentation feedback on the surfaces built in Phase 2, preparing the branch for Ultra 1 review:

1. **Desktop Streamlined Quality Columns:** The wide-window branch of `PlaybackQualitySheet` (`isWide`, ≥768 dp) leads with Best available as a full-width strip — release, `Size`, `Needs` and the connection meter — and lays the alternatives out as **one column per resolution**, each column stacking only the bands that title actually has. ⚠ **Nothing scrolls, and that is the design.** `VideoResolution` has six members and `optionsForBucket` emits at most four bands each, so the offer is bounded and fits a desktop window once it is spent across the width. Panel max width went 920 → 1200 dp (`wideDialogMaxWidth`). A matching skeleton renders on the same footprint before the figures settle.

   This replaced a quality *table* taken on the same day, which the maintainer reported as "literally just a list". Watched on a debug hot run it was: a 480 dp cap that sliced its last row mid-glyph with no scrollbar; a `RELEASE` column carrying `REMUX · DV` identically for 4K Max and 4K High with ~270 dp of dead air beside it; a `FIT` column that drew five visually identical meters on a 755 Mb/s line; a `1440p` row under a `1440p` header; and `Best available` printing `—` for a size the row beneath it printed as 72 GB.

   ⚠ **A collapsed bucket gets the class it would have been.** `Variant.SINGLE` carries no band
   - banding needs two sized sources to compare - and the row used to read "Only option", which
   told the reader nothing about what they would get. `PlaybackQualityOptions.bandFor` derives
   the class from the row's own bitrate against the same absolute boundaries, so a lone 8K
   release at 41 Mb/s reads `Mid (Max)`: a Mid-class file, and the best 8K this title has. The
   one row that keeps a fallback label is a release nobody reported a size for - there is no
   bitrate to band by, and handing an unmeasurable file a class is exactly what banding on sized
   sources alone exists to prevent.

   **What a cell says, and in what order.** Band name, then dynamic range and audio as outlined
   marks, then size and needs, then rip type and host on the last line in muted small caps. That
   order is the fix for "there isn't much differentiating between the cells": down a column
   `BLURAY` repeats four times and `DV / Atmos 7.1` does not, so the old order led with the
   repeating part. `describeProvenance` splits the rip type and host back out of
   `describeRelease`, which had folded the dynamic range into a sentence, so nothing is printed
   twice. `SDR` is drawn (via `PlaybackLoadingFacts.dynamicRangeSlot`, the same earned default
   the loading band uses) but **muted**, never accented - an empty mark row reads as a fact that
   failed to load, and an accented `SDR` spends the panel's one emphasis on the ordinary case.
   Cells sit on `surfaceCard`: `surface`, `surfaceElevated` and `surfaceDialog` are the same
   colour in this theme, so the first attempt tinted the panel over itself and drew nothing.
   The cell Best available resolves to is outlined rather than restated
   (`PlaybackQualityOptions.sourceKey`) - it is routinely the very row beneath the hero, and two
   identical offers side by side read as two files.

   **`High (Max)`.** A resolution whose releases all fall under its Max boundary offers no Max row, so its top row reads "High" — and a lone "High" reads as a middling pick rather than as this title's ceiling at that resolution. `PlaybackQualityOptions.isTopBandBelowMax` marks it and the cell appends the Max word. ⚠ The band word itself is **never** rewritten: the bands are absolute, and relabelling one would be exactly the catalogue-relative naming `Variant` exists to end. `Variant.SINGLE` is excluded — a collapsed bucket has no bands to top — and reads "Only option" instead.
2. **Fixed 5-Slot Loading Metadata Rail:** The loading band across Compose (`PlaybackLoadingScreen`) and desktop JCEF/HTML (`controls.html`, `controls.css`, `controls.js`) now renders a fixed five-slot spec strip: Resolution, Audio/Subs, Range, Audio, and Size. Absent metadata displays an honest em-dash (`—`) rather than phantom guesses; dynamic range safely falls back to `SDR`; the "Choose source manually" escape hatch resides in a reserved 36 dp row above the progress line so its appearance at 5 seconds never shifts the layout under the reader.
3. **Seamless Entrance Motion:** Pop and dip artifacts entering playback are resolved via `PlaybackEntranceMotion` (260 ms coordinated curve: color-alpha scrim, logo, and band arrival) and a desktop navigator fade-through on `entry<StreamRoute>` (220 ms in with 90 ms delay + 90 ms out).

**Verified:** `scripts/run-pure-suites.sh` passes, including the new `PlaybackQualityOptionsTest`
coverage for `bandFor`, `isTopBandBelowMax` and `sourceKey`. `:composeApp:desktopTest` passes for
`PlaybackSourceSelectorTest` (which the pure runner leaves to CI), `PlaybackEntranceMotionTest`
and `PlaybackLoadingStateTest`.

**Not** verified: ⚠ **the columns panel has never been watched against real playback**, and the
one attempt to watch it produced a false alarm worth recording here so the next session does not
chase it twice. It was run under `scripts/dev-desktop.ps1` (default `-Mode hot`), and a hot run
cannot reach a first frame: `hotRunDesktop` does not inherit the
`--add-opens=java.desktop/java.awt=ALL-UNNAMED` that `build.gradle.kts` gives `:composeApp:run`
and the packaged app, so the native view never attaches. What that looks like from the sheet is
indistinguishable from a regression in this work - pick a quality, `PlaybackStartupWatchdog`
abandons each candidate without a frame, the overlay flickers through attempts 1-3, the chain
spends, and `PlayerDestination`'s `onFatalPlaybackError` toasts "No safe automatic source matched"
and pops to the details screen. Nothing on the selection path changed here: the cells hand
`onOptionSelected` the same `PlaybackQualityOption` instances the table did. This needs a debug
MSI, which is the hot-run rule already recorded for the bridge below.

⚠ **A hot run also writes no debug log**, so there is nothing on disk to read afterwards.
`isDebugBuild` on desktop is `System.getProperty("nuvio.debugTools")`, set only by
`-Pnuvio.desktop.debugTools=true` or the debug channel - neither of which `dev-desktop.ps1`
passes.

**Deliberately NOT changed:**
- **Phone Card Grid:** The narrow branch of `PlaybackQualitySheet` (<768 dp) retains its proven touch-card layout and bottom sheet mechanics for phones and small tablets.
- **`entry<PlayerRoute>` No-Transition Rule:** Retains `EnterTransition.None`. Because `PlaybackLoadingHost` draws the identical loading surface across the entire route crossing at `zIndex(18f)`, adding any transition here would create a redundant crossfade between two identical frames.
- **Connection Figure Latch:** The bandwidth measurement figure and verdict remain latched upon initial determination; late background probes never cause figures or column alignments to jump under the reader.

## Source-to-player jank: the hand-over made continuous (2026-09-05)

Branch `claude/phase-2-playback`. The report was "a stutter, then a grey screen, then the loading
screen" between choosing a source and the player. It was **six** separate faults stacked on one
path, each hiding the next, which is why three earlier rounds of plausible-looking fixes changed
nothing the maintainer could see.

**Nothing here was found by reading the code.** Two probes were added first and everything below
was measured; the numbers come from the debug log in `%APPDATA%/Nuvio Z/logs/` and from screen
captures aligned against it by wall clock.

- `EdtStallWatchdog` (`core/debug/EdtStallWatchdog.kt`) - heartbeats the AWT event queue and, when
  a beat waits >34 ms, samples the event thread's stack **while it is still blocked**. Compose
  Desktop composes, lays out and draws on that thread, so this names whatever is holding it.
- A frame-gap probe in `PlaybackLoadingHost` - logs every presented frame gap >34 ms for 2.5 s
  from the tap, each with its offset. Debug builds only.

### What was actually wrong

| # | Fault | Cost | Where |
| --- | --- | --- | --- |
| 1 | The backdrop was scaled **in software, at full source resolution, on the event thread, on every paint** | **2,523 ms** frozen UI | `NativePlayerHost.paintBackdrop` |
| 2 | Eight `Regex` literals compiled *per stream*, from composition, for the whole list | 265 ms | `SourceFacts.kt` |
| 3 | `normalizeLanguageCode` compiled a regex and sorted a 100-entry map per call; `languageLabelResForCode` then normalized all 79 language codes per lookup | 35-131 ms, repeatedly | `LanguageCodes.kt`, `PlayerLanguagePreferences.kt` |
| 4 | `P2pStreamingEngine` class-loading (and an eager `HttpClient`) ran on the UI thread, triggered by `PlayerScreenContent` composing | 311-339 ms | `P2pStreamingEngine.desktop.kt` |
| 5 | The mpv container window class erased with `BLACK_BRUSH`, and a child HWND covers every Compose layer regardless of z-order | the **black** flash | `native/windows/player_bridge.cpp` |
| 6 | The loading screen was the only surface on the path with **no `?: poster` fallback**, so a title with no `background` gave it nothing to draw | **1,560 ms** of flat #0D0D0D | `StreamDestination.kt` |

Fault 6 is the one the whole session circled. Every other surface falls back to the poster
(`StreamsTabletLayout`, and the player overlay's `startingEpisode?.thumbnail ?: background ?:
poster`); the loading session alone passed `launch.background` and fell through to
`nuvio.colors.background`. Captured on screen at 1,560 ms, ending exactly as the JCEF overlay
painted - which is why it read as "grey, *then* the loading screen", and why fixing the canvas and
the native container never touched it. The comment above that argument already claimed it was
identical to what the player is handed.

### The surfaces, and the rule they now all obey

Five things can cover this path: the route's hand-off box, the Compose loading surface, the AWT
canvas, the native container, and the WebView2 controls page. **Every one of them now draws the
same artwork, and none of them appears before it can.**

- The hand-off box paints the backdrop, and is opaque only when there is no artwork at all - so the
  one frame before `AsyncImage` resolves shows the list underneath rather than a grey fill.
- `NativePlayerHost` prepares a cropped, scaled, scrimmed backdrop **once per size on a worker
  thread** (`paint` does no image maths at all), cached across players, and promotion waits for it.
- The container is created parked below the client area and promoted only on `didPaintOpening`,
  which now fires when the page's artwork has *loaded*, not merely when a frame was presented.
- The attach waits for a genuinely full-size canvas: `notifyFirstPaints` used to unlock it at
  `width > 1`, which the 1 dp parked panel satisfied, so the whole native player was built at 2 px
  and then resized - and resizing a live WebView2/mpv container erases it.

### Measured, start of session to end

| | before | after |
| --- | --- | --- |
| UI thread freeze | 2,523 ms | gone |
| `attach requested -> created` | 2,552 ms | ~40 ms |
| Blocking work in the transition | ~900 ms | gone |
| Black flash | present | gone |
| Grey flash | 1,560 ms | ~2 frames |
| Frames presented in 3 s | 5 | 250+ |

### Also fixed here

- **`openingScale` never reached the controls page.** It was written through the nullable-float
  writer, which clamps to 0..1, so the opening overlay always rendered at scale 1 and the logo
  visibly shrank at the takeover. Covered by `NativePlayerControlsJsonTest`.
- **The loading session leaked on every teardown.** Both owners closed it from the `else` branch of
  a `LaunchedEffect`, and an effect is *cancelled* on disposal and never runs its `else`. So the
  surface survived exactly the case its own contract says ends it ("...or when the user leaves"),
  leaving a loading screen over the app that nothing alive could close. Closed now from
  `requestBack()` - the one unambiguous user-leave - and from a `DisposableEffect` on the stream
  route, both exempting a handed-off session so a failover is untouched.
- **The escape hatch toasted a false failure.** `autoPlayStream == null` guarded the whole
  back-press effect rather than just the retry branch, so a back press with no armed stream set
  nothing - including `userAbandonedPlayback`, the one flag that suppresses the dead-end backstop.
  1.5 s later that backstop uncovered the source list with "No safe sources found". Seen in the log
  as `outcome=gave_up uncover=dead_end_backstop` with `attempt=1/3`, i.e. no failover at all.

### Tried and rejected - do not repeat

**Parking the player panel by offset instead of size.** Windows erases a heavyweight component with
its background brush on resize, before Java's `paint()` runs, and that is the last ~2 frames of
grey. Keeping the panel full-size and translating it off screen removes that erase entirely and
**breaks playback**: every attempt fails through to "attempt 3 of 3" and drops the user on the
source list. A native player whose host canvas is outside the window does not start. The two frames
are the price of a player that works; the warning is at the call site in `PlayerEngine.desktop.kt`.

### Still open

1. **~2 frames of grey at the promotion.** The AWT erase above. Would need the native erase
   intercepted (`ignoreRepaint`, or taking over painting) - the same paint path that produces the
   signals unlocking the attach, so getting it wrong stops playback rather than causing a flash.
2. **Escape from the *loading* screen still needs two presses.** From the player it is now one
   press, to details, with no false toast. The first press pops the player; the loading surface is
   one screen across two routes and should exit in one gesture.
3. **A grey moment while the player tears down** on the way back to details.
4. **The 500 ms snapshot poll blocks the UI thread 40-120 ms throughout playback**, once measured at
   1,218 ms: `NativePlayerBridge.isLoading` (a native call) from `PlayerEngine.desktop.kt`'s poll
   loop. Moving it to a worker needs native handle-lifetime protection first - it is not the
   one-liner an earlier handoff suggested.

### Toolchain

This machine can now build the native bridge: **MSVC C++ Build Tools** and the **WebView2 SDK**
(`1.0.4191.47`, in `~/.nuget/packages`) were installed today, and the bridge builds with the full
JBR **SDK** 25 from `~/.gradle/jdks` - Android Studio's JBR has no JNI headers, which is why this
was blocked before. Until today `composeApp/build/native/windows/player_bridge.dll` was dated
**Aug 8** against a Sep 4 source: every local run for a month had been executing a stale bridge,
and the build printed a warning saying so on every build. Delete the DLL to force a rebuild; the
old one is kept beside it as `player_bridge.20260808-backup.dll`.

> **The history moved.** Everything before 2026-08-24 is in [`STATUS-ARCHIVE.md`](STATUS-ARCHIVE.md) -
> 34 sections, kept whole and in order. This file is the live handoff only: the
> state table above, the work since the last release, and what is still open below.

## Phase 2 Playback: the hand-off made seamless, and a source that is actually there (2026-09-05)

Branch `claude/phase-2-playback`, continuing the work below. Three agents have now worked this
branch; **the previous round was left entirely uncommitted** - 18 modified files on desktop and 11
on mobile, with nothing written down anywhere. It is committed now, split by concern: `72029b43`
(the twelve code-review findings) and `ef5e209d` (the desktop native loading band). See the rule
about this added to the new parent `AGENTS.md`.

### What was reported

1. Choosing a source produced a UI stutter, then a black screen, then the loading screen popping
   in. The previous round shortened it but could not remove it.
2. *The Secret Woman*, 4K High: attempt 1 never produced a frame and cost 20 s; attempt 2 played
   the debrid provider's "being prepared" slate and the chain stopped there, satisfied. The
   loading screen also visibly **reloaded** to say "Attempt 2".

### Why the hand-off was not seamless

The pixels were already shared - Phase 2 made both sides render one `PlaybackLoadingState`. **The
lifetime was not.** A route entry stops composing when it is not on top and is re-created by a pop,
so the surface was destroyed and rebuilt at every hand-off and every failover. On desktop that
window contained four further faults, in this order:

| # | What | Where |
| --- | --- | --- |
| 1 | `entry<StreamRoute>` fades out over 160 ms while `entry<PlayerRoute>` had **no desktop spec** and fell through to `NavDisplay`'s much longer default - two crossfades running against each other | `MainAppContent.kt` |
| 2 | the player's root was `Color.Black` under a loading screen painted on `#0D0D0D` | `PlayerEngine.desktop.kt` |
| 3 | the AWT canvas filled `Color.BLACK` and, being heavyweight, painted over every Compose layer the instant the `SwingPanel` was promoted | `NativePlayerHost.kt` |
| 4 | the JCEF overlay then faded its artwork in over 260/520/620 ms - a re-entrance of a screen already at rest | `controls.css` |

**The fix is one move: the surface is owned above the navigator.** `PlaybackLoadingController` holds
one session; `PlaybackLoadingHost` draws it as a sibling of `NavDisplay` at `zIndex(18f)`. The
navigation now happens *underneath* a screen that never stops drawing, so there is nothing left to
animate or re-enter - and a failover becomes a state change, which is what "it should just say
attempt 2 of 3" asks for. `entry<PlayerRoute>` is given an explicit `EnterTransition.None` on
desktop (an `emptyMap()` is not "no animation"), the native canvas and the JCEF overlay are painted
the app's own background, and the JCEF artwork intro is gone.

Motion is now exactly two beats, both defined in `PlaybackLoadingMotion`: a 220 ms entrance when the
source list is replaced (backdrop first, band on an 80 ms stagger) and a 300 ms exit into the first
frame. **Everything between them is zero-duration by construction.**

### Why a placeholder played

`%APPDATA%\Nuvio Z\logs\nuvio-debug-20260905-005434.log`:

```
00:55:02.905  attach created  length=3092   <- [TB(bolt)] MediaFusion 2160p, marked cached
00:55:22.614  abandoning ...: reason=NeverStarted elapsed=20240ms duration=0ms engine=Unknown
00:55:24.418  attach created  length=1395
00:55:31.613  updateControls  pos=10160 duration=120960   <- 2:01, for a feature film
```

**Cache detection was not the fault, and mostly already worked.** `parseDebridCacheMarker` read the
cached marker correctly. Two other things were true:

- `PlaybackSourceSelector.isDebridBacked` did not recognise AIOStreams. It hands back a plain
  `https://` link to its own proxy, so a candidate through it had no `debridService`, no
  `clientResolve` and was not an `isDirectDebridStream` - `isUncachedDebrid` therefore never
  applied and an **unknown** cache state was auto-played. `isAioStreams` is now on that list.
- Nothing ever checked what the URL actually *returned*. A stale cached marker was
  indistinguishable from a true one, and nothing logged the response to a URL handed to the
  engine - which is why attempt 1's twenty seconds are, in that log, unexplainable after the fact.

So: **one `Range: bytes=0-1` before any frame is attached** (`PlaybackSourceProbe`). Status, content
type, and the served total against the release's claim. A rejected source never opens the player, so
the chain steps with nothing on screen changing but the attempt number. Every unknown passes, and a
failed or timed-out probe passes - it must never block a working play. `PlaybackDurationPlausibility`
is the backstop for what the probe cannot judge, and is deliberately conservative: both a
fifth-of-expected ratio **and** an absolute duration under ten minutes.

### Also fixed

- `PlaybackAttemptLog`'s give-up line read `streamsUiState.autoPlayStream` *after* the chain had
  moved on, so it printed `addon=unknown cached=unknown` on exactly the lines that needed them.
  It reads `lastHandedOffFacts` now.
- The stall backstop logged `uncover=dead_end_backstop` six seconds after the user pressed Back -
  a false entry in the one log that exists to explain why the source list appeared.
- The loading surface's exit is gated on a **decoded frame** (`videoWidth`/`videoHeight`, or real
  advancing playback), not on `isLoading` going false, which the engine drops before it has decoded
  anything. `firstFrameReached` is a second flag rather than a redefinition of
  `initialLoadCompleted`, which the seek, subtitle and watchdog paths all read and mean the weaker
  thing by.

### Verified

| | |
| --- | --- |
| Pure suites, desktop | **417** (from 397) |
| Pure suites, mobile | **365** (from 345) |
| `:composeApp:compileKotlinDesktop` | clean |
| `:androidApp:compileFullDebugKotlin` | clean |
| `NativePlayerControlsPageTest` | passes |
| **Watched run** | **not done - still the exit gate** |

New pure files, both wired into `scripts/run-pure-suites.sh`: `PlaybackLoadingSession.kt` (group 1,
it reads `SourceFacts`) and `PlaybackSourceProbe.kt` (group 2).
`scripts/pure-suite-stubs/Neighbours.kt` gained `SourceFacts.isAioStreams` - the stub had drifted
again, and per the script's own doctrine a failing compile is the alarm and the stub gets fixed.

### Still open

- **Nothing here has been watched.** The whole point is a transition, and a transition cannot be
  verified by a test or a compiler. It needs a debug MSI - Compose Hot Reload cannot attach the
  native player bridge, so the player route opens to an empty surface that looks exactly like the
  bug being fixed.
- **The remaining desktop hand-over gap is now measurable but has not been measured.** `controls.js`
  reports `didPaintOpening` and `NativePlayerController` logs `afterAttachMs=`. Read that figure on
  the first real run before deciding whether anything more is needed there; WebView2 is already
  warmed at process start, so there may be nothing left to win.
- The probe adds one round trip to every automatic play. It runs under a loading screen that is
  already up, so it should be invisible - but it is a real cost and worth watching on a slow
  connection.
- Mobile still has no debug build on the post-sync base.

## Phase 2 Playback: implementation complete, watched exit gate open (2026-09-04)

Branch `claude/phase-2-playback`, cut from the Phase 1 sync branch - **not** from trunk, which is
362 commits behind. Full handoff: `../HANDOFF-phase-2-playback.md`.

**Stages 0-4 and 6 are complete in both repos.** The code review and automated verification pass
are complete. Stage 5's installed playback matrix remains the release exit gate; Compose Hot
Reload cannot exercise the native player bridge on this machine.

### The finding that matters most

**The `0.1.22-alpha` sync silently disabled desktop's whole playback recovery path.** The App.kt
dissolution recorded above moved `MainAppContent`'s `onFatalPlaybackError`/`onPlaybackStarted`
handler nowhere: `PlayerDestination` stopped passing them, while `PlayerScreen` still declared
both. Nothing was deleted, everything compiled, and the deletion check the sync brief mandates
could not see it - a lambda simply stopped being passed.

Three things were dead in production until this phase:

- `PlaybackStartupWatchdog` arms only when `onFatalPlaybackError != null`, so **it never ran**;
- the post-playback-started failover chain never advanced;
- `consumeFailoverRetry()` always answered false, so every return from the player read as a back
  press.

`nuvio-z` kept its copy, which is exactly why the loading loop was reported on desktop only.
**Worth a rule for the next sync: a lambda that stops being passed is invisible to both the
conflict list and the deletion sweep.** Grep the callers of anything the dissolution moved.

### What landed

- **One loading surface** from chosen source to first frame, rendered by both the route overlay
  and the player's opening overlay from one state object. Three loading surfaces and four
  indeterminate motions became one.
- **Every duration-derived position bounded** through pure `PlaybackPosition`. The watchdog's
  baseline ignored the fraction-only resume path, so a dead source could be declared Started -
  bugs 1 and 2 shared that root.
- **`AddonStreamGroup.error` stops being discarded**, in the list and as the failure reason.
- **Content-identity gate**, auto modes only, a partition rather than a filter.
- **All 13 ways into the source list named and logged**, with `hasSilentUncover` making a
  reasonless uncover a failing test.
- **The route audit is closed**: download launches cannot enter auto playback, P2P consent no
  longer destroys an untried failure chain, rejected external-player launches advance or uncover
  honestly, and process restoration cannot preserve a phantom in-flight debrid resolve.
- **Desktop party resolution now outranks local downloads**, so joining a host session resolves
  the host fingerprint rather than silently playing a different on-device file.
- **P7 automatic source-swap was deleted**, including its setting/storage/sync key, detector,
  candidates, forced-swap HUD controls and swap log. It had been held since `0.4.9`, had never run
  on a device, and Phase 2 confirmed that null direct URLs discarded every unresolved alternative.
  Passive network measurement and manual in-player source switching remain.

### Verified, and not

Suites green: pure 389 desktop / 337 mobile; Android host 1,312; desktop 1,518. All runs have zero
failures, errors or skips. Desktop compiled the native bridge and real desktop source set; its one
reported configuration-cache problem is the existing non-serializable bridge `Exec` task, and
Gradle discarded that cache entry after the successful run.

⚠ **Nothing here has been exercised against real playback.** Hot reload cannot reach a first
frame on this machine - the native bridge fails to attach (`java.desktop does not "opens
java.awt"`), so the player route opens to an empty surface that looks exactly like a hang. That
is a second, separate reason for the debug-MSI rule already recorded below. The watched matrix is
still the exit gate: Classic manual selection, Streamlined selection, Instant failover, P2P
consent/decline, external-player reject, debrid resolution, next episode, and back navigation.

Desktop debug build: **published & packaged** via GitHub Actions [Run 33905579208](https://github.com/Zokaper/NuvioZDesktop/actions/runs/33905579208).
Release tag [`debug-v0.1.22-alpha-z1.41`](https://github.com/Zokaper/NuvioZDesktop/releases/tag/debug-v0.1.22-alpha-z1.41)
carries verified packages for Windows x64 (`Nuvio-Z-Debug-Windows-x64-0.1.22-alpha-z1.41.msi`) and Apple Silicon macOS
(`Nuvio-Z-Debug-macOS-arm64-0.1.22-alpha-z1.41.dmg`). Installed locally for watched testing.

## Phase 1 Upstream Sync: 0.1.22-alpha-z1 (2026-09-04)

Completed Phase 1 upstream sync from `0.1.20-alpha` (`b32dd57b`) to upstream tag
`refs/tags/upstream/0.1.22-alpha` (`5aca4f3f829a7a9ee259ce4c97631c9ff0b18a1c`), following the
merge-never-rebase doctrine.

### The merge and conflict resolutions

- **Conflict volume**: 357 commits behind upstream, 65-file conflict surface, 39 files in conflict.
  All 39 conflicts resolved and recorded in `rerere`.
- **App.kt dissolution**: Upstream dissolved `App.kt` from 4,533 lines to 108 lines. Ported Z logic
  into upstream's modular architecture:
  - `MainAppContent.kt`: Navigation shell, root tab switching, stream launcher with download preferences
    and `runtimeMinutes`, `onWhatsNewClick`, `onRunSetupAgainClick`, and 7-tab title dispatch.
  - `StreamDestination.kt`: Playback resolution pipeline, fallback routing, Watch Together lobby and
    session transitions.
  - `AppGate.kt`, `AppShellComponents.kt`, `MainTabsDestination.kt`: Root tabs, Social and Downloads
    navigation, and profile switcher hooks.
- **Native player & HTML controls**: Preserved Z modifications in `player_bridge.mm`, `player_bridge.cpp`,
  `NativePlayerBridge.kt`, `NativePlayerController.kt`, and the HTML controls card (`controls.html`,
  `controls.js`, `controls.css`).
- **Silent deletions restored**: Restored `AddonSubtitleStartupPolicy.kt`, its unit test,
  `AddonSubtitleStartupMode` enum, storage keys, and `PlayerScreenRuntimeEffects.kt` hook.
- **Toolchain**: Updated `scripts/run-pure-suites.sh` to Kotlin 2.4.10, dynamic serialization-plugin
  versioning, and Git Bash/JBR path normalization.
- **Version adoption**: Adopted `0.1.22-alpha-z1` with `RELEASE_SERIAL=127` in
  `composeApp/Configuration/DesktopReleaseSerial.properties` and `DesktopVersion.properties`.

### Verified

- Pure suites: **336 tests OK** across all 6 groups (126 + 64 + 49 + 17 + 29 + 51).
- Desktop test suite: `:composeApp:desktopTest` **passed** (`BUILD SUCCESSFUL in 14m 39s`).
- Upstream drift: 0 behind tag `0.1.22-alpha`, conflict surface reduced from 65 to 0.
- Desktop debug build: **published & verified** via GitHub Actions [Run 33873568488](https://github.com/Zokaper/NuvioZDesktop/actions/runs/33873568488).
  Release tag [`debug-v0.1.22-alpha-z1.40`](https://github.com/Zokaper/NuvioZDesktop/releases/tag/debug-v0.1.22-alpha-z1.40)
  carries verified packages for Windows x64 (`Nuvio-Z-Debug-Windows-x64-0.1.22-alpha-z1.40.msi`) and Apple Silicon macOS
  (`Nuvio-Z-Debug-macOS-arm64-0.1.22-alpha-z1.40.dmg`).

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
