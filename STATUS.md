# Nuvio Z Status

Last updated: 2026-09-22

## Phase 7 closeout: desktop release hardening (2026-09-22)

**Complete.** Desktop retains `0.1.23-alpha-z6` / release serial `131`, stable upgrade UUID
`7b1f2c94-53ad-4c1e-9f6a-2d8e0b45c7f1`, MSI ProductVersion `2.0.<serial>`, the existing updater
repository and tag lineage. It was deliberately not renumbered to match mobile.

`Build Desktop Release` now separates real artifact-producing `build-only`/`dry-run` from draft and
publish, requires `Dev` for promotion, refuses an incomplete Windows/macOS stable set, clears stale
outputs, verifies exact artifact names/counts and checksums, requires notes and an unused tag, and
checks that the version bump is the final application change. Signing/notarization hooks are ready;
the current live unsigned-Mac path remains available only with an explicit publish acknowledgement.
The stable updater continues to reject prereleases/debug tags and now fails closed on malformed
serials. Downgrade/rollback remains a forward release with a greater serial.

Verified: pure suites **8/8, 778 tests**; `:composeApp:desktopTest` **2427 / 0 failures**; local debug
MSI `Nuvio-Z-Debug-Windows-x64-0.1.23-alpha-z6.61.msi` at ProductVersion `1.45.61`; local stable MSI
`Nuvio-Z-Windows-x64-0.1.23-alpha-z6.msi` at ProductVersion `2.0.131`; build-only CI run
`35666931626` produced and verified the Windows x64 MSI plus arm64 and x86_64 DMGs and consolidated
checksums. Its publish job was skipped. No stable release or tag was created. Canonical policy and
procedures are in `nuvio-z/Docs/RELEASES.md`.

## Phase 6 closed; one mobile fix desktop still lacks (2026-09-21)

**The canonical write-up is `nuvio-z/STATUS.md`**, "Phase 6 closeout". Phase 6 closed as DONE WITH
NON-BLOCKING QA DEBT. The desktop -> mobile parity audit found nothing desktop has that mobile lacks.
No desktop code changed.

**One fix went the other way and is not here.** `PlayerScreenRuntimeUi` picks the in-player social
card's notification with a filter that skips join requests, but the card's Accept / Decline / Join
handler acts on the *first* unread notification that has any actions. With a join request pending,
Accept on a friend request's card therefore lets the requester into the party. Mobile fixed this by
resolving both through `inPlayerSocialCardNotification` and acting by id. Bring that across on the
next desktop touch, by merge rather than by copy.

## Mobile UI pass, stages 5-10: shared lobby and Social phone layouts (2026-09-21)

**The canonical write-up is `nuvio-z/STATUS.md`** under the same heading. Six mobile commits arrived
here by cherry-pick (`d79c88d6`, `d098792d`, `7692602a`, `91a0ff21`, `e296258f`, `93537c00`), all
clean. `c757619e` adds `WatchPartyLobbyRenderHarness` and moves `SocialRenderHarness` onto the real
`SocialFeed`, with phone scenes. **Desktop is visually unchanged:** every desktop Social scene is
byte-identical before and after, and the lobby's two-pane and tablet branches are the old code moved
verbatim into `PartyLobbyContent`. `:composeApp:desktopTest` 2425 / 0 failures.

## Away return: the Android half, fixed on evidence and verified on the phone (2026-09-21)

**The canonical write-up is `nuvio-z/STATUS.md`** under the same heading, with the adb capture that
diagnosed it. Cherry-picked from mobile `94505408`. Published here as
**`debug-v0.1.23-alpha-z6.61`** (MSI ProductVersion 1.45.61) against Android
**`0.4.13-z1.40`** - debug channel only, the release line and stable updater untouched.

**This changes nothing this app runs.** `PartyLifecycleMonitor.android.kt` is carried here and not
built; the shared rule in `PartyPresence.kt` gains a `resumed` parameter that only the Android
adapter passes. It is in this repo so the shared files stay converged.

Worth knowing on this side anyway, because the desktop is the other end of every one of these
parties: a guest whose phone was locked could sit `away=true` on the roster indefinitely - the
`USER_PRESENT` broadcast is dropped to a cached process, and the `ON_START` keyguard re-read that
was meant to cover that fires during the dismiss animation and reads the lock as still up. The
return now hangs on `ON_RESUME`, which cannot be dropped and cannot be misread. On a host with
"Pause when someone is away" enabled, that member was holding the party for everyone.

**Verified on hardware** (the mobile write-up has the log): two lock/unlock cycles, two returns,
`away roster []` both times - so this host saw them come back.

**The starve-recovery policy from z6.59/z1.38 was confirmed on the same run**, which is what it was
cut for: `action=TEMPORARY_SPEED ... starveRecovery=true`, the gap closing 806 -> 108ms, no seek and
no loop.

**Verified here.** 8/8 pure groups; `:composeApp:desktopTest` - see the run below.

## The z1.38/z6.59 run: the unlock race, and the seek that was eating the buffer (2026-09-21)

**The canonical write-up is `nuvio-z/STATUS.md`** under the same heading. Both findings are in
shared code and arrived here by cherry-pick from `mobile/claude/phase-6-convergence-linear`
(`2e86bdb8`); every Kotlin file auto-merged, which is the converged state doing its job.

Published here as **`debug-v0.1.23-alpha-z6.60`** (MSI ProductVersion 1.45.60) against Android
**`0.4.13-z1.39`**. Debug channel only - `-Pnuvio.desktop.debugChannel=true`, GitHub prerelease,
its own upgrade UUID and data directory. **The release line and the stable updater were not
touched.** Only `DEBUG_BUILD=60` moved to cut it.

**The one that is desktop-facing is the second.** The buffer -> seek -> buffer loop was watched from
this side: a Windows host with an Android guest on a struggling source, the host taking a couple of
seconds to react and the loop never breaking. It is not a detection limit. A guest seeks once it is
`WatchPartySeekThresholdMs` (1s) behind, but the host - this app - only holds the party after
`WatchPartyGuestBufferingGraceMs` (2.5s) of *continuous* buffering, so every rebuffer in between
forced the guest into a seek that discarded its freshly rebuilt buffer while this host was still
deciding whether to wait. The host's hold never fired to break the loop because no single rebuffer
lasted long enough.

The fix is a bounded recent-starvation recovery policy in `DriftTracker`, described in full in the
mobile write-up. **The host's 2.5s hold is untouched**, and the new 3s seek override sits
deliberately just above it so the two mechanisms hand the problem over instead of both standing
down - `theStarveOverrideStaysAboveTheHostsBufferingGrace` asserts that ordering.

The first finding (an unlock that cleared Away only sometimes, because `ON_START` re-read the
keyguard that `USER_PRESENT` had just refused to) is Android lifecycle, so it changes nothing this
app runs - `PartyLifecycleMonitor.android.kt` is carried here but not built. It is in this repo so
the shared files stay byte-identical.

**Verified here.** 8/8 pure groups; `:composeApp:desktopTest` - see the run recorded below.

**Not verified.** No desktop host has watched a guest recover from a rebuffer with this policy in
place, and the loop has not been re-run on hardware. See the list in `nuvio-z/STATUS.md`.

## Phase 6 Away hardware run: the source-change bypass was the desktop's (2026-09-21)

**The canonical write-up is `nuvio-z/STATUS.md`** under the 2026-09-21 heading: three defects found
on the `0.4.13-z1.37` / `z6.58` run, two of them Android lifecycle and one of them this repo's.
Published here as **`debug-v0.1.23-alpha-z6.59`** (MSI ProductVersion 1.45.59) against Android
**`0.4.13-z1.38`**, from `claude/heartbeat-session-renewal`. Debug channel only -
`-Pnuvio.desktop.debugChannel=true`, GitHub prerelease, "Nuvio Z Debug" with its own upgrade UUID
and data directory. **The release line and the stable updater were not touched.** The only change
in the cut itself is `DEBUG_BUILD=59`; no fix logic moved.

The one that is desktop-facing: a **host changing source from the native/HTML player controls told
nobody.** `"selectSource"` called `switchToSource(stream)`, which is the internal switch that
deliberately says nothing to the party; the Compose panel calls `switchToUserSelectedSource(stream)`,
which publishes and advances `sourceGeneration`. The host loaded and played its new source and every
guest stayed on the old one, still syncing its timeline against it. Since the desktop draws the
sources panel with the native controls, this was the shipped behaviour of Change Source for every
desktop host.

The native consent continuation had the same bypass for a P2P pick, and the Compose path had the
inverse defect - it published before the consent dialog, so cancelling moved the party onto a source
nobody started. Both now publish exactly when the pick takes effect.

The two Android-side defects (a screen-lock return that never cleared Away, and an away flag that
outlived its party and made every peer publish say `away=true`) are shared code and are in this repo
too. `PartyPresence.kt`, `PartyLifecycleMonitor.android.kt` and `PlayerWatchPartyEffect.kt` are
byte-identical with mobile.

**Verified here.** 8/8 pure groups; `:composeApp:desktopTest` **2397 tests, 0 failures**.
`PlayerSourcePickRoutingTest` lives in `desktopTest` here and in `androidHostTest` on mobile - the
one deliberate divergence in this change, since neither repo can run the other's source set.

**Not verified.** No desktop host has changed source with `z6.59`, through either panel, and no
P2P consent dialog has been accepted or cancelled in a live party. That is what this package and
Android `z1.38` were cut for; the hardware list is in `nuvio-z/STATUS.md`.

## Phase 6 Away lifecycle cut for hardware - z6.58 (2026-09-20)

`f7bec859` on `claude/heartbeat-session-renewal`, the desktop half of the Away/lifecycle work.
**The canonical write-up is `nuvio-z/STATUS.md`** under the Away heading, with the log grep lines
for the device run and the list of what a device can still contradict. Ledger: **S10** in
`nuvio-z/Docs/Z-FEATURES.md`.

Published here as **`debug-v0.1.23-alpha-z6.58`** against Android **`0.4.13-z1.37`**. Debug channel
only - `-Pnuvio.desktop.debugChannel=true`, GitHub prerelease, "Nuvio Z Debug" with its own upgrade
UUID and data directory. **The release line and the stable updater were not touched.** The only
change in this cut is `DEBUG_BUILD=58`; no Away logic moved.

**Unverified on hardware.** Nothing in this chunk has run on a phone or against a real desktop
party yet; that is what these two builds are for.

## Phase 6 Watch Together playback/source stabilization - DONE WITH NON-BLOCKING QA DEBT (2026-09-20)

Closed, and published on this side as **`debug-v0.1.23-alpha-z6.57`** against Android
`0.4.13-z1.36`. **The canonical write-up is `nuvio-z/STATUS.md`** under the same heading; it lists
the whole chunk - engine-native starvation and readiness, the watchdog's viability rule, the seek
positive-readiness barrier and its four named exits, timeline-safe host/guest source failover, the
source-resolution UX states, and explicit host `EquivalentMedia` manual source authority - together
with the ledger row, **S9** in `nuvio-z/Docs/Z-FEATURES.md`.

Hardware-verified in both directions on 2026-09-20. **Every observed barrier resume was
`reason=all-ready`**; the 12 s ceiling never fired and **stays at 12 s**. Desktop-host waits were
3.7 s, 3.9 s and 8.5 s.

**Non-blocking QA debt:** a natural host source failure, a natural guest compatible fallback and a
natural guest incompatible / no-compatible-fallback case are all still unverified on hardware. They
cannot be forced reliably and are trial by fire from normal usage. Not blockers.

## Phase 6 hardware run passed; manual host source pick now advances the party (2026-09-20)

`ccac89fe` on `claude/heartbeat-session-renewal`, cherry-picked from mobile `2b81383d9` on
`claude/phase-6-convergence-linear`. **The canonical write-up is `nuvio-z/STATUS.md`** - this entry
carries only the desktop-specific half.

The run was captured on desktop `debug-v0.1.23-alpha-z6.56` (installed 1.45.56) against Android
`0.4.13-z1.35`. Both seek directions held the readiness barrier and resumed together. **Every
resume on this side was `reason=all-ready`; the 12 s ceiling never fired.** Desktop-host waits were
3.7 s, 3.9 s and 8.5 s - the 8.5 s is 71% of the budget, which is the evidence for leaving the
ceiling where it is. Desktop debug logs for the run are in
`%APPDATA%\Nuvio Z Debug\logs\nuvio-debug-20260920-16*.log`.

The change itself: an explicit **host** pick from the sources panel now narrows the duplicate test
to `PartySameReleaseTiers`, so a deliberately chosen `EquivalentMedia` look-alike advances the
authoritative party source instead of being refused as a duplicate. Re-picking the party's own
release is still refused, automatic paths keep the full test, and a guest's pick keeps it too.

Verified here: `:composeApp:desktopTest` **2348 tests, 0 failures** (full suite, results directory
cleared and `--rerun-tasks`). The three known flaky suites - `WatchedItemsStoreTest`,
`DesktopDownloadQueueE2ETest` and `NativePlayerControllerTeardownTest` - all passed on this run.

**Mirror note.** `git merge mobile/<branch>` was attempted first, per `AGENTS.md`, and **aborted**:
the merge base is far enough back that it pulled in the whole fork gap, conflicting in 19 files
including `AppUpdater.kt` and `MetaDetailsScreen.kt`, both on the never-copy list. Every Phase 6
shared commit before this one crossed as a separate commit for the same reason (`8044f0b1`/
`7ff3a02b`, `95ebc494`/`84c3ec5c`), so this one was cherry-picked with `-x`. After it, the two
repos' copies of `PartySourceSwitch.kt` and the new test are byte-identical and
`PlayerScreenRuntimeSourceActions.kt` differs only by the two blank lines it already differed by.
**Merging these branches is not currently viable and wants an upstream reconciliation first.**

## Official Desktop Release — 0.1.23-alpha-z2 (2026-09-17)

### Nuvio Z Desktop official release

- **Released**: `0.1.23-alpha-z2`
- **Tag**: `0.1.23-alpha-z2+127`
- **Release serial**: `127`
- **Release source**: `879dabfca3489eca27714bed1be28c4e79554a40`
- **Release branch**: `release/0.1.23-alpha-z2`
- **Publish workflow**: GitHub Actions run `35154508494`
- **Public release**: https://github.com/Zokaper/NuvioZDesktop/releases/tag/0.1.23-alpha-z2%2B127
- **Release status**: public, `draft=false`, `prerelease=false`
- **Upstream base**: Nuvio `0.1.23-alpha`

### Platforms shipped

- **Windows x64**: Succeeded (`.msi`)
- **macOS arm64**: Succeeded (`.dmg`)
- **macOS x86_64**: Succeeded (`.dmg`)
- **Linux**: Intentionally skipped (packaging exists upstream/in repo; physical Nuvio Z Linux validation still owed)

### Artifacts & Checksums

- **Windows artifact**: `Nuvio-Z-Windows-x64-0.1.23-alpha-z2.msi`
  - SHA256: `8bf81695d548b95c1c44f42a18540b10dac2972006ec8f85b14bf8f02e99f475`
- **macOS arm64 artifact**: `Nuvio-Z-macOS-arm64-0.1.23-alpha-z2.dmg`
  - SHA256: `79d9bf6c00415cb2daa87c6c5905e47d7204b8beb93c565cb0391155137e9667`
- **macOS x86_64 artifact**: `Nuvio-Z-macOS-x86_64-0.1.23-alpha-z2.dmg`
  - SHA256: `6605e65fba6faf5786b92c5c19c09177be179c2bf01b270a56e6ae45dbdbdc8d`

### Known release engineering debt

- **macOS signing / notarization**: Both macOS DMGs were built through the existing unsigned-DMG path. Signing and notarization was intentionally disabled in the workflow and remains known release-engineering debt until an Apple Developer account is configured.

### Automated release gate

- `desktopTest`: 2,158 passed, 0 failures, 0 errors, 0 skipped
- `scripts/run-pure-suites.sh`: 676 tests, 8/8 green
- `compileKotlinDesktop`: green
- Windows MSI packaging + verification successful
- Both macOS DMG packaging + verification successful
- Final GitHub release creation successful

### Current mode & next steps

- Upstream base is Nuvio `0.1.23-alpha`.
- Desktop release is now complete.
- Desktop moves into real-world feedback / maintenance mode.
- Phase 6 (Social to mobile) is the next planned phase.
- Phase 6 has NOT started yet.

## Hotfix Desktop Release - 0.1.23-alpha-z6 (2026-09-17)

- **Contents** (owner-reported: z5 update downloaded, app closed, no installer):
  1. Every release MSI of one base version had ProductVersion `1.1.23` (z3/z4/z5), so jpackage gave them one
     ProductCode and Windows entered maintenance mode instead of upgrading. Release MSIs now use
     `2.0.<RELEASE_SERIAL>` (major 2 clears the 1.5.0 shipped by `0.5.0-beta`); debug/macOS/Linux unchanged.
  2. Windows MSI updates run unattended: `resources/updater/run-hidden.js` starts `windows-msi-update.ps1`,
     which waits for the JVM + launcher, runs `msiexec /passive` elevated (one UAC prompt), reports a failure
     code in a message box, and relaunches the app either way.
  3. `INSTALLDIR` is passed from `jpackage.app-path`, so a custom-folder install is upgraded in place
     (the jpackage MSI does not remember its folder). Drive-root installs fall back to the default.
- **Caveat**: z3-z5 installs run the old updater for this one update, so they get the normal wizard; a
  custom-folder user must browse back to their folder. The silent path applies from z6 onward and was verified
  with a fake MSI and a logged INSTALLDIR run, not yet through a real UAC-approved upgrade.

## Hotfix Desktop Release - 0.1.23-alpha-z5 (2026-09-17)

- **Released**: tag `0.1.23-alpha-z5+130`, serial `130`, source `d7a05537` on `release/0.1.23-alpha-z5` (Dev at the same commit)
- **Workflows**: dry-run `35235607051`, publish `35235793671` (windows-macos); public, `draft=false`, `prerelease=false`
- **Public release**: https://github.com/Zokaper/NuvioZDesktop/releases/tag/0.1.23-alpha-z5%2B130
- **Gates**: desktopTest 2,230 run, 1 failure = the known `WatchedItemsStoreTest.kt:45` flake; pure suites 8/8, 695 tests
- **Contents** (owner-reported bugs, one session):
  1. Watching Now: members of one Watch Together party showed as separate cards, each "Ask to join"-able.
     Backend migration `202609170001_watching_now_party_grouping.sql` (deployed) adds `party_id`,
     `party_host_profile_id`, `party_member_count` to each entry and makes `social_join_watching` answer
     `disabled` for a request aimed at a non-host member (promoting a guest's presence built a second party).
     Client `groupWatchingNowByParty` collapses a party into the host's entry; no join offered if the host
     is not on the viewer's list. Verified against the live party `44ec84f5` (3 members, 3 presence sessions).
  2. Classic: Escape from a hand-picked play sometimes landed on "Finding source…" over the list. The
     player saves a binge group mid-play; the list re-fetch on return became an auto-play request and the
     abandon flag blocked the play that would have cleared the overlay. `userAbandonedPlayback` now makes
     the request manual, and `manualPlaybackStarting` is reset on return. **Physical QA owed.**
  3. Social screen: the 1440dp dashboard cap (commit 190a0c6d) left side margins on 1920px monitors.
     Cap removed; column caps raised to 3 (Watching Now) / 4 (activity). Render harness checked at 1920.
  4. Player: toasts when members join or leave the party (`partyMembershipNotice`). **Physical QA owed.**
  5. Wizard Sources step: no Next on the question page (explicit "Do it later"); Next greyed on the
     recommended/manual paths until a source installs; Back there returns to the question.
  6. Template (`templates` branch `6279dee3`, live for all versions): renamed "AIOStreams Z", OpenSubtitles
     preset dropped. Re-run replacement matches both names. AIOStreams uuid/password are now persisted per
     install (`AioStreamsCredentialStorage`) so future template changes can be pushed to installs made from
     z5 on; installs made earlier have no stored password and need the step re-run.

## Official Desktop Release - 0.1.23-alpha-z4 (2026-09-17)

- **Released**: `0.1.23-alpha-z4`, tag `0.1.23-alpha-z4+129`, release serial `129`
- **Release source**: `731a98e1e3989aa4d06f8df4d2abf719c5fb0546` on `release/0.1.23-alpha-z4` (Dev fast-forwarded to it)
- **Publish workflow**: GitHub Actions run `35222361359` (mode `publish`, target `windows-macos`), after dry-run `35222251832`
- **Public release**: https://github.com/Zokaper/NuvioZDesktop/releases/tag/0.1.23-alpha-z4%2B129 (`draft=false`, `prerelease=false`)
- **Artifacts**: Windows x64 MSI, macOS arm64 DMG, macOS x86_64 DMG, `SHA256SUMS.txt` (macOS still unsigned)
- **Contents**:
  1. Native Compose AIOStreams + TorBox Sources setup (`POST /api/v1/user`, no WebView), from
     `claude/experiment-native-sources-onboarding`. Physically tested by the owner on debug build
     `debug-v0.1.23-alpha-z3.51` (local MSI, not published).
  2. Login screen stuck after sign-in: `SupabaseProvider.client` could build two clients under concurrent
     first reads; now double-checked locking + `@Volatile` (`SupabaseProviderConcurrencyTest`).
  3. Wizard handle save failed with "No active social profile" - the wizard runs before `MainAppContent`
     activates `SocialRepository`; the wizard now passes its profile id and records the identity on success.
  4. Wizard colour step listed supporter-only palettes that `setTheme` refuses; now `availableAppThemes`,
     and Custom opens `CustomThemeEditor`.
  5. TorBox API key hint links to `https://torbox.app/settings?section=account`.
- **Gates**: compileKotlinDesktop green; desktopTest 2,213 passed, 0 failures, 0 errors, 0 skipped (fresh XML);
  pure suites 8/8, 695 tests.
- **Physical QA owed**: fixes 3-5 were not exercised in a running app before release.
- The main checkout's uncommitted `SupabaseProvider.kt` diff and untracked `SupabaseProviderConcurrencyTest.kt`
  are now identical in intent to commit `27cc26a8` on Dev; discard them before pulling.

## Official Desktop Release - 0.1.23-alpha-z3 (2026-09-17)

- **Released**: `0.1.23-alpha-z3`, tag `0.1.23-alpha-z3+128`, release serial `128`
- **Release source**: `cde8e4b21e07b1582caeaf8edba69e378d3e1ce8` on `release/0.1.23-alpha-z3`
- **Publish workflow**: GitHub Actions run `35198929798` (mode `publish`, target `windows-macos`), after dry-run `35198705893`
- **Public release**: https://github.com/Zokaper/NuvioZDesktop/releases/tag/0.1.23-alpha-z3%2B128 (`draft=false`, `prerelease=false`)
- **Artifacts**: Windows x64 MSI, macOS arm64 DMG, macOS x86_64 DMG, `SHA256SUMS.txt` (macOS still unsigned)
- **Contents**: the three post-release fixes below. Physically checked on debug build `debug-v0.1.23-alpha-z2.51`
  before release (Watching Now join; wizard). The first-source failure seen in that run was a dead TorBox link
  abandoned by the startup watchdog before any party existed, not a regression.

## Post-release stabilization for z3 (2026-09-17)

On branch **`claude/post-release-z3-stabilization`**, cut from `Dev` at `eb8357c3` (z2 release + its
docs commit). No version bump, no release. Three bugs from physical use of `0.1.23-alpha-z2`.

1. **Setup Wizard buttons needed several presses (macOS).** Root cause:
   `nuvioConsumePointerEvents()` on the wizard root consumed *every* change on the `Final` pass;
   Compose's tap detector cancels a click when a move between press and release is consumed on
   `Final`, and ancestors see `Final` first. Any pointer travel inside a click - normal on a trackpad -
   lost it. Not window activation. The modifier now consumes only presses and releases (the fall-through
   protection comes from being a hit target). Also fixes the same loss on `PlaybackLoadingScreen`'s back
   button. `SetupWizardClickTest` (desktopTest, real pointer pipeline via `ImageComposeScene`) failed 4/5
   before the fix. **Physical macOS check still owed.**
2. **Watching Now join stuck on "Matching <host>'s source…".** `PlayerDestination` retained the party
   realization only on a byte-equal descriptor, while every other identity check is tiered; a guest's
   catalogue describes the same release differently, so the realizer stayed `Resolving` forever
   (production row for the physical run: `ready` + `source_match=exact`). Now
   `partyRealizationCompletedByLaunch` (exact tiers, or an alternate chosen after FallbackRequired/Failed).
   Also: the realizer's `source_ready` may no longer overwrite a player's `ready`
   (`realizerReadinessMayPublish`) - the host log showed `ready -> source_ready -> ready`.
3. **Join made the film pause/play/pause/play.** Host log 2026-09-17 08:52-08:53: start-barrier pause at
   the promoted party, `gate` play on durable `ready`, then two stall-guard pause/play cycles for the
   same guest's cold-start rebuffers. Fix, all in the existing barrier: the start release now also waits
   for fresh peer `paused`/`playing` from members in the player (bounded, `WatchPartyStartPlaybackReadyMaxWaitMs`);
   the release resumes only if the host's captured pre-barrier intent was playing (a paused host stays
   paused); members starting up get `WatchPartyStartupStallGraceMs` from the stall guard; members who
   left/disconnected are dropped from holds; a guest opening into a non-playing party starts parked.
   `WatchPartyJoinBarrierTest` (pure suite) replays the log.

Gates: compileKotlinDesktop green; pure suites 8/8, 695 tests; desktopTest 2,184 passed, 0 failures, 0 errors, 0 skipped (BUILD SUCCESSFUL, stale results cleared first).
Physical QA owed: macOS wizard clicks (trackpad), and a two-client Watching Now join with the host
playing and with the host paused.

## Playback: Loading screen language summary & Initial preferred audio track selection (2026-09-16)

On branch **`codex/upstream-sync-0.1.23-alpha`**.

### What landed

1. **Honest Loading Screen Language Summary**:
   - Resolved misleading `Russian +2 / —` summary on multi-audio streams (e.g. `Bugonia...-RUTRACKER.mkv`) where English is present and preferred:
     - `PlaybackLoadingState`, `PlaybackLoadingFacts.facts`, `languagePairLabel`, and `rememberLanguageNamer` now thread `preferredAudioLanguage`.
     - Multi-audio streams with user's preferred language present display the preferred language first: e.g. `English +2`.
     - Multi-audio streams where preferred language is absent or unmatched display neutral count: `Multi · ${codes.size}` (e.g. `Multi · 3`).
     - Single detected language displays single language honestly: e.g. `Russian`.
     - Unstated multi-audio continues to display `MULTi / —`.
   - In `LanguageCodes.kt`:
     - Added delimiter-aware phrase matching in `containsReleaseToken` so multi-word tokens like `ru audio`, `dual audio`, and `latin spanish` match across release delimiters (`.`, `-`, `_`, space).
     - Confirmed and pinned tests ensuring release groups like `-RUTRACKER` or `-RUTOR` do not match `ru`, while legitimate evidence (`RU.audio`, `RU-Audio`, `RUS`, `Russian`) matches correctly.
2. **Deterministic Player Initial Preferred Audio Track Selection**:
   - In `PlayerLanguagePreferences.kt`, added `resolvePreferredAudioTrackIndex(tracks, preferredLanguages)`:
     - Outer loop iterates by user preference priority order (primary, then secondary), ensuring primary preferred languages are not bypassed by container track ordering.
     - Normalizes ISO-639-2 codes from container/mpv (e.g. Matroska `eng` / `rus`) against preference targets (`en` / `ru`).
     - Falls back to matching against `track.label` if `track.language` is unstated or blank.
   - In `NativePlayerController.kt`:
     - Wired `resolvePreferredAudioTrackIndex` into `applyAudioLanguagePreferences`.
     - Added concise, token-free diagnostic logging: configured preference targets, available track languages/labels, selected track, or why deferred.
   - In `PlayerScreenRuntimeAudioPreferences.kt`:
     - Guarded `preferredAudioSelectionApplied`: if `audioTracks` is empty, calls controller but does not mark selection applied, enabling selection when tracks arrive later.
     - In `refreshAudioTracksIfChanged()`: no longer blocks on `playbackSnapshot.isLoading` when `currentTracks.isNotEmpty()`, selecting preferred audio immediately when demuxer is ready before first frame plays.
     - Preserves explicit user manual selections at all times.
3. **Test Coverage & Gates**:
   - Added `PlayerInitialAudioSelectionTest` (10 unit tests covering ISO-639-2 normalization, preference priority ordering, label fallback, empty-track deferral, and manual override protection).
   - Expanded `PlaybackLoadingStateTest` (+5 unit tests covering neutral multi counts, preferred-first display, and single language).
   - Expanded `LanguageCodesTest` (+2 unit tests covering `-RUTRACKER` / `-RUTOR` exclusions and `RU.audio` delimiter variants).
   - Pure suites: 8/8 green, 676 tests (up from 669).
   - Full desktop test suite: 2,158 tests, 0 failures, 0 errors, 0 skipped (up from 2,141 baseline).

### Gates

| Gate | Result |
| --- | --- |
| `:composeApp:compileKotlinDesktop` | **BUILD SUCCESSFUL** |
| `:composeApp:compileTestKotlinDesktop` | **BUILD SUCCESSFUL** |
| `:composeApp:desktopTest` | **BUILD SUCCESSFUL - 2,158 tests, 0 failures, 0 errors, 0 skipped** (up from 2,141 baseline) |
| `scripts/run-pure-suites.sh` | **8/8 green, 676 tests** (278 / 107 / 70 / 17 / 29 / 115 / 63 / 3) |

## Setup Wizard: Sources onboarding step & Replay Wizard restoration (2026-09-16)

On branch **`codex/upstream-sync-0.1.23-alpha`**.

### What landed

1. **Sources Step in Setup Wizard**:
   - `SetupStep.Sources` is now an unconditional step in all wizard mode flows (Classic, Streamlined, Instant).
   - Positioned between `Language` and `SocialOptIn` (Step 5 in a full 10-step flow; Step 4 in Classic/social-off 8-step flow).
   - If stream-capable addons already exist (detected via `List<ManagedAddon>.firstEnabledStreamAddonName()`), a green configured banner displays: `"Sources configured: <Addon Name>"`.
   - Recommended card features **AIOStreams + TorBox** with a direct launch button (`uriHandler.openUri(aioStreamsSetupUrl())`) directing users to the hosted instance (`https://aiostreamsfortheweebsstable.midnightignite.me`) with the stable raw GitHub template (`nuvio-z-torbox-v1.json`, v1.1.0) pre-configured.
   - Users choose source and subtitle languages and enter their TorBox API key on AIOStreams; Nuvio Z never collects or stores third-party API keys directly.
   - Manual manifest installation field provided for existing manifests / post-setup AIOStreams manifests.
   - Advancing via "Next" or "Back" is unblocked at all times (optional skip).
2. **Revision 9 Wizard Semantics**:
   - `SETUP_WIZARD_REVISION = 9`.
   - `SETUP_WIZARD_AUTOMATIC_REQUIRED_REVISION = 8`.
   - Fresh installs always receive the full 10-step Revision 9 flow.
   - Existing users who completed Revision 8 are **not** forced to re-onboard automatically, but can re-run on demand.
3. **Replay Setup Wizard Restored in Settings**:
   - Restored missing `onRunSetupAgainClick` plumbing dropped during upstream sync modularization:
     - Threaded from `AppGate` through `MainAppContent`, `AppTabActions` (`AppShellComponents.kt`), and `SettingsRootDestination` (`SettingsDestinations.kt`) to `SettingsScreen`.
     - In `AppGate.kt`, introduced `setupWizardOnDemandEpoch` and keyed the on-demand `SetupWizardScreen` with `key(setupWizardOnDemandEpoch)`, ensuring replaying the wizard resets cleanly to the Welcome step.
     - Indexed in `SettingsSearch` under Look & Feel as "Replay Setup Wizard" with searchable keywords covering look, sources, and playback choices.
4. **Off-screen Render Harness & Test Coverage**:
   - `SetupWizardRenderHarness` updated and verified across desktop window sizes (1280x820, 2560x1440, 3840x2160) and modes (Streamlined, Instant, Classic). Off-screen PNGs saved to `composeApp/build/setup-wizard-render/`.
   - Added `AioStreamsSetupTest` (8 unit tests covering deep-link URL encoding, stream resource filtering, install handling).
   - Added `SettingsReplayWizardTest` (2 desktop Compose tests verifying search indexing and callback invocation).
   - Expanded `SetupWizardStepsTest` (+11 tests covering step presence, ordering, and revision 8 vs 9 gating).

### Gates

| Gate | Result |
| --- | --- |
| `:composeApp:compileKotlinDesktop` | **BUILD SUCCESSFUL** |
| `:composeApp:compileTestKotlinDesktop` | **BUILD SUCCESSFUL** |
| `:composeApp:desktopTest` | **BUILD SUCCESSFUL - 2,141 tests, 0 failures, 0 errors, 0 skipped** (up from 2,124 baseline) |
| `scripts/run-pure-suites.sh` | **8/8 green, 669 tests** (271 / 107 / 70 / 17 / 29 / 115 / 63 / 3) |
| `SetupWizardRenderHarness` | **Passed, PNGs rendered cleanly** at 1280x820, 2560x1440, 3840x2160 |

### Deferred QA / Manual Verification
- External browser launch on Windows hardware: verify `openUri()` opens the user's default browser to the preloaded AIOStreams template page.
- End-to-end manifest install via AIOStreams generated link into desktop SQLite storage on live device.

## Desktop upstream sync - NuvioDesktop 0.1.23-alpha (2026-09-16)

The canonical desktop line is now **`codex/upstream-sync-0.1.23-alpha`**, branched from
`claude/desktop-consolidation` at `5137fc3b`. It merges named upstream release
`0.1.23-alpha` (`af4803399e77480ca7db75284b89a049cc6fe040`) with history intact. The previous
vanilla base was `0.1.22-alpha` (`5aca4f3f829a7a9ee259ce4c97631c9ff0b18a1c`): **68 upstream
commits** were introduced. Moving `upstream/Dev` was deliberately not merged; it was 148 commits
beyond the tag at audit time.

### Audit and merge result

- Upstream changed 144 files (+8,201/-3,168) across original-audio/subtitle handling, generic
  skeleton/loading UI, Home/startup performance, Android downloads, desktop controls/windowing,
  custom themes, Linux updating, localisations, dependencies and version files.
- The three-way merge reported **18 conflicts**: `.gitignore`; six download repository/platform
  files; `App.kt`; `StreamDestination.kt`; `Skeleton.kt`; `DesktopDetailHero.kt`; `HomeScreen.kt`;
  `PlayerControls.kt`; `PlayerScreenRuntimeSourceActions.kt`; `PlayerScreenRuntimeUi.kt`; desktop
  `Main.kt`; `controls.html`; and `libs.versions.toml`.
- Upstream's custom themes, shared skeletons, stable Home lazy keys, IMDB detail restoration,
  desktop maximize-bound handling, native control icons/shortcuts, Linux package-aware updater,
  Compose 1.12 alignment, original-audio selection and subtitle identity/rendering fixes survive.
- Z's UI zoom, Social rows, Watch Together hooks, playback modes/ranking/failure chains,
  next-episode control, post-open probing, premature-EOF recovery, language inference,
  built-in-subtitle preference and unified playback preferences survive.
- Upstream did **not** independently replace Z's EOF recovery, source probing/ranking, failover,
  playback-mode next-episode routing, playback loading rail/artwork, or setup revision 8.
- Upstream's original-audio work now strengthens the player half of Z's unified language model;
  no Z playback patch became obsolete enough to delete. Its old reuse-last-link path remains
  deleted because it bypasses Z's mode/ranking model.
- Upstream's new Android transfer scheduler was not layered beside Z's existing background
  scheduler and stricter resumable-transfer contract. The duplicate scheduler/worker files and
  their tests were omitted; Z's queue, validators, stall watchdog, partial-file guarantees and
  system-pause recovery remain the single owner. The upstream NetworkOnMainThreadException case is
  already avoided because Z starts network work on its I/O transfer coroutine.
- `DesktopVersion.properties` was mechanically advanced to `0.1.23-alpha-z1`, version code 40.
  `RELEASE_SERIAL=127` and debug build 49 were not bumped; this is not release preparation.
  The stale desktop-copy iOS version file was intentionally kept at its Z value.
- The inherited Linux updater tests assumed a Linux host path while running in the multiplatform
  desktop suite. Their expectations now use `File.absolutePath`; production updater behavior is
  unchanged and the class passes on Windows.

### Verification

| Gate | Result |
| --- | --- |
| `:composeApp:compileKotlinDesktop` | **BUILD SUCCESSFUL** |
| focused conflict regression set | **BUILD SUCCESSFUL** - audio/subtitle, EOF, probe/failover, next episode, Home, native controls and the full desktop download E2E class |
| clean `:composeApp:desktopTest` | **BUILD SUCCESSFUL in 8m 57s - 2,124 tests, 0 failures, 0 errors, 0 skipped**, 258 fresh XML files (`cleanDesktopTest` ran first) |
| `scripts/run-pure-suites.sh` | **8/8 green, 669 tests** (271 / 107 / 64 / 17 / 29 / 115 / 63 / 3) |
| `:composeApp:compileAndroidMain` | Reaches compilation and fails with the **same 15 pre-existing errors** in `AddonPlatform.android.kt`, `MainAppContent.kt`, `HomePosterHoverPreview.kt` and `PlayerScreenContent.kt`; no new download/settings/player actual error |

No release, tag, MSI, backend change or Phase 6 work was performed. Remaining release-prep risk is
manual: the 12-item hardware checklist in `HANDOFF-desktop-consolidation.md` still applies, now with
an added smoke pass for upstream's original-audio selection, custom theme UI, Home skeletons,
fullscreen/maximize behavior and native control shortcuts.

## Desktop consolidation - one branch, one gate, one MSI (2026-09-16)

⚠ **Automated gates green on the consolidated branch. Nothing here has been on hardware.**
Desktop feature development through the current roadmap work is complete enough to move on; what
remains is QA debt, not an open development phase. The next roadmap task is **Phase 6 - Social to
mobile**, which this pass deliberately does not start.

**Canonical desktop branch: `claude/desktop-consolidation`.**

### What was actually wrong

Two branches had been treated as "the newest work" at different times, and **neither contained the
other**. `claude/social-wt-ux-pass` and `claude/playback-eof-probe-fixes` both forked from `2d8be68d`
(UX pass Stage 8, 1,957 tests). That is exactly what the 2,016-vs-1,990 test counts were reporting:
+59 tests on one line and +33 on the other, from the same base. Picking either branch as "the latest"
would have silently dropped a completed, friend-verified feature set.

| Line | Head | Carried |
| --- | --- | --- |
| `claude/playback-eof-probe-fixes` | `8f0e5650` | `01524bb1` premature-EOF guard + probe-after-mpv-opens (the AIOStreams **Wrong IP** fix) + native-player failover in all three bridges; `ad6feb22` source-language inference and "Prefer built-in subtitles"; `cb0be213`/`8f0e5650` the quality-panel subtitle chip, added and reverted the same day |
| `claude/social-wt-ux-pass` | `42148c22` | `cb8088e8` the four 2026-09-15 two-client hardware fixes; `42148c22` the unified playback-preferences pass |

Merged as `748b9632`. Four conflicts, all at the seam where both lines touched language:

- **`PlayerSettingsRepository.kt`** - `rankableAudioLanguage` / `rankableSecondaryAudioLanguage` stay
  **deleted**. `42148c22` replaced them with `resolveRankableLanguages`, which *resolves* the
  `device`/`original` sentinels the old accessors merely stripped; restoring them would reinstate the
  inert-`REQUIRE` default that pass exists to close. `primarySubtitleTarget` survives - it resolves
  subtitle sentinels, not audio, and "Prefer built-in subtitles" is its only caller.
- **`PlaybackSelectionContextFactory.kt`** - gains `preferredEmbeddedSubtitleLanguage`, **passed and
  not derived**: whether a pick is automatic is the caller's fact.
- **`StreamDestination.kt`** - both language values kept, because they answer different questions under
  deliberately different rules. `requestedContentLanguage` is `peek`-only and refuses to read a
  production country as a language, because it is *displayed*. `contentOriginalLanguage` is only ever
  *ranked* with, keeps `resolveContentLanguage`'s country fallback, and is **still seeded null rather
  than from its neighbour** - each half keeps exactly the behaviour its own branch verified. No hybrid
  was invented during a consolidation pass.
- **`PlayerEpisodeQualityChooser.kt`** - both imports.

**Merge integrity was checked mechanically, not by eye.** Every substantive line either branch added
against `2d8be68d` was confirmed present in the merge result: **zero missing**, both directions.

### Gates, all from `claude/desktop-consolidation`

Gradle daemons were stopped and `composeApp/build/test-results/desktopTest/` deleted first. The whole
gate was **run twice**: once on the merge commit `748b9632`, and again on the final HEAD `2da1675e`
after the duplicate-key cleanup, so the figures below belong to the commit this branch actually points
at rather than to an earlier tree. Both runs gave the same 2,050 / 0.

| Gate | Result |
| --- | --- |
| `:composeApp:compileKotlinDesktop` | **BUILD SUCCESSFUL** |
| `:composeApp:desktopTest` | **BUILD SUCCESSFUL in 17m 33s - 2,050 tests, 0 failures, 0 errors**, 251 result files, every one written by that invocation |
| `scripts/run-pure-suites.sh` | all eight groups green - 271 / 107 / 64 / 17 / 29 / 115 / 63 / 3 = **669** |
| backend `scripts/test-db.sh` | **12 files / 286 tests PASS** |

2,050 is the union it should be: 2,016 + 1,990 - 1,957 = 2,049. **None of the three known flakies
reproduced** - `NativePlayerControllerTeardownTest.failedOrdinaryDisposeBlocksTerminalNavigation`
passed, as did the download-queue E2E case.

**Mobile source sets - environment limits, not source failures.**

- `:composeApp:compileKotlinIosSimulatorArm64` is **SKIPPED** on a Windows host. It needs macOS. This
  says nothing either way about the iOS actual.
- `:composeApp:compileAndroidMain` **fails, exactly as it did before this work**: 15 errors in four
  files nothing here touches - `AddonPlatform.android.kt` (`addonHttpClient`) and desktop-only Compose
  pointer APIs used from `commonMain` (`MainAppContent.kt`, `HomePosterHoverPreview.kt`,
  `PlayerScreenContent.kt`). The signal worth having is the negative one: **`PlayerSettingsStorage.android.kt`
  was analysed and reported clean**, and it is a file *both* branches changed. The shared settings code
  compiles for Android; the module does not, for reasons that predate all of this.

### The MSI

One build, from the canonical branch at **`2da1675e`** with a clean tree.

`composeApp/build/compose/release-msis/Nuvio-Z-Debug-Windows-x64-0.1.22-alpha-z1.49.msi`
259,594,065 bytes; SHA-256 `fc3a6a8859bed68a2d66c2744325277fbd7e8c642934ee2c2ff26f183da79599`.

- `-Pnuvio.desktop.debugTools=true`, confirmed as `-Dnuvio.debugTools=true` in `packageReleaseMsi.args.txt`.
- Built on the **JBR SDK** (`.gradle/jdks/jetbrains_s_r_o_-25-amd64-windows.2`), which is the one with
  `jni.h`; the Android Studio JBR cannot build the native bridge.
- `player_bridge.dll` was **deleted before the build** and rebuilt at 14:18, nine minutes before the MSI
  was packaged, so the `external`/`default` track flags and the failover changes are genuinely in it
  rather than carried over from an older artifact.
- `DEBUG_BUILD` 48 -> 49 so this does not overwrite the MSI built from `cb8088e8`, which shares every
  other part of its name.

⚠ **Not published.** It was not uploaded anywhere and no release tag was cut, so neither in-app updater
can see it. It exists on this machine only.

⚠ **An earlier MSI build from the merge commit `748b9632` was started and deliberately killed.** The
duplicate-key cleanup landed after it, and an MSI whose source does not match the branch HEAD is exactly
the sort of artifact that gets misread later. The one above is from HEAD.

### What is in this build

Phase 5 onboarding redesign; Social + Watch Together stabilization; the Social/Watch Together UX pass;
the later Direct Join / Next Episode / party-lifecycle hardware fixes; the friend-machine playback
fixes (premature EOF, AIOStreams Wrong IP, native-player failover); source-language inference and
"Prefer built-in subtitles"; and the unified playback-preferences pass.

**The friend's previously reproducible playback failures passed on his dedicated test build.** That
build was `01524bb1` - three commits before the built-in-subtitle work and on the far side of the merge
from the preferences pass. So that result stands for the EOF and Wrong IP fixes *as they were then*,
and re-confirming them across the merge is on the checklist rather than assumed.

### What is not proven

⚠ **Broad physical QA remains deferred.** Nothing on the consolidated branch has been run on hardware.
The short, targeted checklist for this MSI - only the seams that changed, not the historical matrix -
is the workspace-root `HANDOFF-desktop-consolidation.md`. Specifically still unproven: the Playback
settings page has **no render harness** and has been compiled and never drawn; setup revision 8's
language step has been rendered but never walked; and the four fixes in `cb8088e8` were authored from
a previous session's two-client run and committed by a later session that only proved they compile.

## Playback preferences - one model, actually enforced (2026-09-16)

⚠ **Automated gate green; none of it has been on hardware.** The checklist is §Verification 4 of the
approved plan (Claude plan `you-re-planning-one-final-frolicking-giraffe.md`), and it needs a **debug
MSI** - `dev-desktop.ps1 hot` cannot attach the native player, and every claim there needs a first frame.

**The root cause this pass closes.** `preferredAudioLanguage` ships as the sentinel `device`, and the
source picker's `rankableAudioLanguage` stripped `device`/`default`/`original` to null one call before
ranking read it. So `LanguageStrictness.REQUIRE` - the shipped default - **did nothing at all** for any
profile that had never opened the language dialog, and "original audio, subtitles in my language" was
expressible in the player and invisible to the picker.

| Stage | What landed |
| --- | --- |
| 1 | `features/playback/PlaybackLanguageResolution.kt` (new, pure, import-free): `resolveRankableLanguages` resolves `device` to the OS locale and `original` to the title's own language, and `default`/`none`/`forced` to no opinion. `rankableAudioLanguage` / `rankableSecondaryAudioLanguage` deleted. `PlaybackSelectionContext` gains the two subtitle fields. |
| 2 | `SourceRanking.subtitleLanguageBonus` - a **separate** comparator key between `languageScore` and `mediaScore`, 2/1/0, **promotes and never demotes** (a name that says nothing and a name that says the wrong thing score the same). Nulled under `LanguageStrictness.OFF` with the audio pair. |
| 3 | `features/playback/PlaybackSelectionContextFactory.kt` (new): one `playbackSelectionContextOf`, used by all three builders. The in-player next-episode sheet had been setting 6 of 13 fields, so episode 2 was picked without the ceiling or the language rule episode 1 honoured. `StreamDestination`'s `remember` key list also gains `playbackAudioPreference`, which was missing. |
| 4 | Default strictness `REQUIRE` -> `PREFER`, and a one-shot migration (`playback_language_migrated_v1`, all three actuals + `syncKeys`) writes the resolved device code over the sentinel. It runs inside `loadFromDisk`, which `ProfileSettingsSync.ensureRepositoriesLoaded()` calls before any import. The subtitle preference is **not** migrated: `none` is a deliberate answer. |
| 5 | One **Language** section at the top of Playback - four rows, never greyed on mode, because they feed the player's own track selection in every mode including Classic. The subtitle pair moved off the Subtitles page, which keeps a pointer row. `SettingsSearch` rewired: its two subtitle rows used to land on the page the setting had left. |
| 6 | `SetupStep.Language` in **all three** mode branches, `SETUP_WIZARD_REVISION` 7 -> 8, two rows opening the same `LanguageSelectionDialog` Settings opens. New `DiagramLanguage()` band. |
| 7 | `playbackModeSelectorSeen` deleted (field, setter, storage, loader, three write sites); the key stays in `syncKeys` as a **tombstone** so an older client's payload still clears the orphaned local value. `stream_reuse_last_link_*` deleted from all three actuals. `intro_submit_enabled` now exports and is in `syncKeys` (it was import-only, so a remote payload could set it and nothing could clear it). `subtitleStyle.stripSdh` and `playbackMeteredCapHeight` given controls - both were read by shipped code and writable from nowhere. |
| 8 | Already implemented. The six `streamAutoPlay*` rows on the Advanced page were already greyed with a reason line for non-Classic modes (`AdvancedSettingsPage.kt:748`). No new code; the plan's assumption was stale. |

**Gate.** `scripts/run-pure-suites.sh`: all eight groups green. `:composeApp:compileKotlinDesktop` green.
Full `:composeApp:desktopTest`: **BUILD SUCCESSFUL in 14m51s, 1,990 tests, 0 failures, 0 errors** (245
result files, all written by that run). None of the three known flakies reproduced.

**New tests.** `PlaybackLanguageResolutionTest` (sentinels, the anime case, the migration rule),
`PlaybackSelectionContextFactoryTest` (every field reaches the context; the five the next-episode sheet
used to drop are pinned by name), `SourceRankingTest` +4 (promotes, never demotes, never outranks
resolution or audio language, nulled under `OFF`), `PlaybackSourceSelectorTest` +2 (`REQUIRE` with a
resolved `device` language now partitions where it used to no-op), `SetupWizardStepsTest` +3 and eight
existing assertions updated for the tenth step.

**Rendered.** `SetupWizardRenderHarness` draws the new step in all three mode branches at 1280x820,
2560x1440 and 3840x2160 (`build/setup-wizard-render/desktop-*-language-*.png`); the bands render at 420
and 1100. Reading them caught one real defect: the two subtitle rows were titled "Preferred Language" /
"Secondary Preferred Language", which read correctly under a *Subtitles* heading and read as nonsense
beside "Preferred Audio Language". Retitled to "Preferred Subtitle Language" / "Secondary Subtitle
Language". ⚠ **The Playback settings page has no render harness** - only setup and social do - so the new
Language section and the metered-cap row have been compiled and never drawn. They are on the hardware list.

**Logged, deliberately not built here** (all three are in the plan's out-of-scope list):

- **No HDR display-capability gate.** `windows/player_bridge.cpp` has zero HDR code; macOS detects EDR
  only inside the player, after a video is decoding, and exposes nothing to Kotlin. A gate needs a DXGI /
  `DISPLAYCONFIG_GET_ADVANCED_COLOR_INFO` probe, a JNI surface and three actuals. The resolution-shaped
  default in `PlaybackQualityOptions` stays exactly as written.
- **`PlayerEngine.desktop.kt:45` drops per-stream `externalSubtitles`** - accepted and never forwarded to
  `NativePlayerSurface`. A real defect, but player plumbing rather than preferences.
- **`applySubtitlePreferences` is a desktop no-op** (`PlayerEngine.kt:51-58`, overridden only on Android).
  Harmless today: `refreshTracks()` in `PlayerScreenRuntimeEffects.kt` is what re-runs selection.

**Not started, deliberately:** the final desktop consolidation and Phase 6 mobile. The two pure files are
import-free so the mobile port is a copy; see the shared `nuvio-z/STATUS.md` entry.

## Inherited: four Watch Together / social hardware fixes (2026-09-15, committed 2026-09-16)

⚠ **Written by the previous session from its own two-client run; this session only found them
uncommitted on disk, confirmed they compile and that their tests pass, and committed them.** Nobody has
re-verified them on hardware since.

- `PartyPlaybackStatusInputs.partyStarted` - a guest's gate reads every non-playing party as
  `WAITING_FOR_HOST`, so a mid-film pause (the guest's own included) said the host had not started.
  Set from the durable row the first time it reads playing.
- `WatchPartyLobbyExit.lobbyOwedOnPlayerExit` + `PlayerDestination.afterPop` - a party started from the
  player's own Watch Together panel had no lobby under it, so Escape went home and left the party running
  with nothing on screen to leave or end it from.
- `SocialScreen` pagination - the load-more effect was keyed on `isLoadingMore`, which the request sets
  the moment it starts, so the effect cancelled its own request, surfaced the cancellation as an error
  and relaunched forever. `SocialRepository.refresh` also no longer reports a `CancellationException` as
  a failure.
- `controls.css` - the party banner pill enlarged (46px min-height, 15px text, its own backdrop rather
  than the theme border token).

`DEBUG_BUILD` moved 46 -> 48 in the same round.

## Playback: language inference + "Prefer built-in subtitles" (2026-09-15, `claude/playback-eof-probe-fixes`)

⚠ **Automated gate green; not hardware-verified.** The final desktop cleanup/consolidation pass has not started.

**Language slot.** Root cause: the band printed `SourceFacts.languages` verbatim, which is only ever a
positive release-name claim, and English is the unmarked case. English releases showed nothing, and
tagged releases showed their tag. On top of that, `VOSTFR`/`LEGENDADO`/`ENG.SUBS`/`MultiSubs` counted
as *audio*, and `subtitleLanguages` fell back to the Nuvio-parsed audio languages. The fix:
`releaseLanguageEvidenceIn` splits audio from subtitle evidence and adds `DUBBED`/`HC` markers.
`SourceFacts` gains `hasStructuredLanguages`, `releaseSubtitleLanguages`, `claimsMultiSubtitles`,
`claimsDubbedAudio` and `isHardSubbed`. `SourceLanguageInference` resolves in this order: structured
field, release name, the title's original language (`MetaDetailsRepository.peek`, read synchronously
so it never changes on screen, and skipped when `DUBBED` or `MULTi` contradicts it), then unknown.
Subtitles never fall back to the title's language.

**Prefer built-in subtitles** (`playback_prefer_embedded_subtitles`, off by default, synced like its siblings, Source preferences section):
- Active only for Streamlined/Instant automatic picks: `automaticEmbeddedSubtitleLanguage` returns null for Classic, manual picks and downloads, and the player gates on `PlayerLaunch.autoPickedWithFailureChain`. A source picked in the player turns it off.
- Before open: `SourceRanking.embeddedSubtitleScore` gives +2 for a release-name subtitle claim in the primary subtitle language and +1 for `MultiSubs`, inside `mediaScore`. It never affects the resolution or language tier. Hard-subbed releases score 0. Nothing is fetched or probed.
- After open: `verifyEmbeddedSubtitles` reads mpv's `track-list`. All three bridges now emit `external` and `default`. A non-forced container track in the target language wins over sidecar and addon tracks in that language, with `default` breaking ties. Any other outcome logs `embedded_subtitles outcome=… embedded=… hinted=…` once per source and falls through to the unchanged auto-selection. There is no source retry. Forced-only mode never enters this path.

Gate: `compileKotlinDesktop` green; focused 11 classes / 164 tests green; full `desktopTest` with results
cleared first **2,016 tests, 0 failures, BUILD SUCCESSFUL**; `run-pure-suites.sh` all 8 groups OK (group 1 now 251).
Android/iOS storage actuals: **not gateable in this repo** - `:composeApp:compileAndroidMain` fails
with the same 15 pre-existing errors at `01524bb1` and at this commit (desktop-only Compose APIs in
`commonMain`, plus `addonHttpClient`), and `compileKotlinIosSimulatorArm64` is SKIPPED on a Windows
host. No new error comes from this change; the iOS actual mirrors its neighbour and needs macOS.

**The quality panel deliberately says nothing about subtitles.** A chip was added and then
removed the same day, on hardware evidence: for *The Punisher* only one 1080p release named
subtitles, while the recommended 4K release carried them and said nothing - so the chip's
*absence* read as "this source has none", which is the asymmetry the language inference above
exists to avoid. A release name can only ever confirm, never deny. The ranking hint and the
post-open verification are unaffected; they never needed the chip. What survives from it is
`PlaybackQualityRenderHarness` (PNGs in `composeApp/build/playback-quality-render/`) and a chip
row that drops a mark it cannot fit rather than clipping it mid-word.

Debug MSI from `64dacbe6` - **stale**: it carries the chip that was removed afterwards.
`composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
(259,577,632 bytes; SHA-256 `2cee289596b9318572b271732523b4622ed83c87852c54b93adcbf8137ff97d3`),
built with `-Pnuvio.desktop.debugTools=true` (confirmed in `packageReleaseMsi.args.txt`) on the JBR
SDK. `player_bridge.dll` was deleted first so the `external`/`default` track flags are really in it.

Hardware checklist (debug MSI): 1) Streamlined + preference on, MKV with an English track → built-in
track selected, log `outcome=confirmed`; 2) a source with no built-in subs → addon subtitle as before;
3) AIOStreams automatic pick still opens (no Wrong IP); 4) band shows `English / —` for an untagged
English film opened from details, `Japanese` for an untagged anime; 5) Classic unchanged.

## Social + Watch Together UX pass — Stage 8: full gate, review, debug MSI (2026-09-15)

⚠ **Automated gate green; nothing in stages 3-7 has been exercised on hardware.** The two-client checklist
is in `PLAN-social-watch-together-ux-pass.md` §10.

- `compileKotlinDesktop` green. Full `desktopTest` was run with `test-results/desktopTest/` deleted first:
  **1,957 tests, 0 failures, BUILD SUCCESSFUL**, on the final code (`7be7a056`).
- `scripts/run-pure-suites.sh`: all eight groups green.
- Backend `scripts/test-db.sh` on `claude/join-request-lifecycle`: 12 files / 286 tests PASS.
- `/code-review high` over `claude/phase-5-onboarding...claude/social-wt-ux-pass` returned 7 findings, all
  confirmed against the code and fixed in `7be7a056`:
  1. Cancel while Sending dropped the send's answer; the server still made the membership or request.
     Now `AbandonSend` + `ReleaseOrphanedSend` leave or cancel it as its owner.
  2. A replacing send cancelled the previous one and then failed `joinInFlight`. It now waits for the
     previous send, which releases its own orphaned answer.
  3. The boundary cancel could go out on the next profile's Z token. The shell now awaits
     `SocialRepository.awaitIdentityBoundary()` before `WatchPartyRepository.setActiveProfile` / `restore`.
  4. `reconcileAbandoned` forgot entries whose cleanup failed.
  5. Overlapping `setPolicy` writes rolled back to the wrong policy.
  6. `setInOwnPlayer` was a flag; a player replacing another left it false. It is now a count.
  7. `partyInvitedProfileIds` survived across parties.
  New reducer tests cover 1 and 2.
- Debug MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
  (259,512,095 bytes; SHA-256 `f573def7509839c4976342153582973b2c8821ac78f89136e0ab939b59834578`), built
  with `-Pnuvio.desktop.debugTools=true` (confirmed in `packageMsi.args.txt`) on the JBR SDK.
- Still open: the teardown-test race (see Stages 5-6); iOS actuals never compiled; §11.11 friends-rail clip
  not reproducible in the harness; the gallery's header shows a button under the top-right close glyph,
  so check that against the real window chrome in the MSI.

## Social + Watch Together UX pass — Stage 7: party status pill (2026-09-15)

⚠ **Compiled, tested and rendered - not hardware-verified.** Every status row needs the two-client run
in the plan's §10.

`watchPartyBanner` / `bannerText()` / `WatchPartyPlayerStatus` are gone. `rememberPartyStatusLine`
(`player/PlayerPartyStatus.kt`) gathers `PartyPlaybackStatusInputs` from the existing signals and runs
Stage 1's `projectPartyPlaybackStatus` through `debouncePartyStatus` on a 250ms clock. The clock keeps
running while a line is shown or pending, so a line whose condition vanished with the party still clears.
`PlayerControlsState.partyStatus` (`PartyStatusBridgeState`) replaces `partyBannerVisible/Text`. The
commands and labels are chosen in Kotlin (`partyStatusBridgeState`), so the page can only send commands
the runtime answers.

| piece | where |
| --- | --- |
| Episode handoff | while the party no longer matches this playback, the party this player last followed stays the status party as long as `PartySourceRealizer` is still working for it |
| Stall hold | host: `partyAutoPausedForGuests`; guests: `WatchPartySyncState.tickHold` (the newest non-playing tick's `hold`); self in the list → "Everyone's waiting while you buffer" |
| Tick `hold` | `WatchPartySync.publishTick(hold = …)` from both host publish sites |
| Paused by | `announcePartyActor` no longer toasts `pause`; it records `partyLastPauseActor` (someone else only), which a `play` or a generation change clears. It is shown only 500ms after the command, while the party is paused, and when no tick `hold` is present |
| Actions | `wtChooseSource` → `openSourcesPanel()`; `wtStartAnyway` → the same force start as pressing play; `wtDontWait` → `stopWaitingForStalledGuests()`: turns the switch off, **releases the current hold** (otherwise nothing would ever resume it), and the switch comes back on at the next generation; Let in / Decline / Cancel reuse the Stage 5 commands |
| Page | `.party-banner` pill: tone dot (waiting pulses, warning amber, error red), up to two avatars, text, a secondary and a primary button. Only the buttons take the pointer. When the chrome hides, the pill compacts to 85% and the buttons go; any pointer movement brings the chrome and the buttons back. The secondary button is hidden under 680px |

Gallery: `PlayerUiGallery` writes `status-*` pages (14 rows, each also in a `-compact` variant) from the real
projector. Screenshotted: incoming at 1280 and 600, incoming compact, source-not-found, and waiting-sources
at 1280 and 600×500.

Gate: `compileKotlinDesktop` green; pure suites green (group 7 now 61 tests and compiles
`PartyPlaybackStatus.kt`); `NativePlayerControlsJsonTest` / `NativePlayerControlsPageTest` /
`PlayerUiGallery` green.

## Social + Watch Together UX pass — Stages 5-6: panel state bridge + native panel (2026-09-15)

⚠ **Compiled, tested and rendered - not hardware-verified.** Commit `e9b44565` covers both stages and is
still labelled WIP; it has not been split.

- `WatchTogetherBridgeState` + `watchTogetherBridgeState(panel, …)` replace `PartyRoomViewState`,
  `PlayerPartyMember` and `PlayerPartyInviteTarget`. The header action only toggles `partyRoomOpen`;
  the panel does not close when a party goes away; it auto-opens on `guestPostEndChoice`. There is a 20s
  promotion timeout, and promotion failures go to the panel's StartFailed state instead of a toast.
- Join policy: `SocialPresenceSession.setPolicy(policy)` (optimistic, rollback) replaces `cyclePolicy`;
  the header pill is removed.
- `controls.html/css/js` panel rewritten for every state. `data-wt-command` buttons are delegated on
  `#partyPanel`. The lock toast reads "Only X can pause or seek".
- Gate: pure suites green; social + watchparty + player `desktopTest` 491/491.
- `NativePlayerControllerTeardownTest.failedOrdinaryDisposeBlocksTerminalNavigation` is **not a Stage 5-6
  regression**. It failed 2 of 4 runs with the class run alone, and neither that test nor the
  release/dispose code changed in these stages (the controller diff is JSON only). Cause: the test's fake
  dispose counts its latch down *before* throwing, so `releaseBeforeNavigation` can run before
  `recordTerminalDisposeFailure` sets `terminalReleaseFailure`. Release then takes the worker path and
  reports success. This is a latent race in the release path, left for a separately approved fix.

## Social + Watch Together UX pass — Stage 4: outgoing join request store, boundaries, dock (2026-09-15)

⚠ **Compiled, tested and rendered - not hardware-verified.** Needs the two-client run in the plan's §10
("Ask to join end to end" and "Identity boundaries while a request is pending").

`OutgoingJoinRequestStore` (new, process-scoped) replaces `joinApprovalWatch` + `awaitJoinApproval`
(deleted). It serializes events into Stage 1's `reduceOutgoingJoinRequest` and executes the effects.
`liveToken` moves at every boundary *before* the boundary is queued, and every async result is checked
against it when produced and again when reduced, so nothing that races a boundary can navigate.

| piece | where |
| --- | --- |
| Send | Home and Social Join / Ask to join → `store.ask(item)` → `joinWatchingNow`; `AwaitApproval` now carries `request_id` + `expires_at` |
| Delivery | `social:<me>` invalidation with `reason = join_request` → immediate status read; 3s poll floor; `social_join_request_status` / `social_cancel_join_request` via `SocialRepository` |
| Outcomes | Declined / Expired / TargetStopped (settled online Watching Now only) / Superseded / Failed |
| Accept | browsing: 3s countdown → `lobbyRequests` → shell installs the party and navigates; in own player (`BindSocialPresenceEffect` → `setInOwnPlayer`): waits for Join / Not now; Not now departs |
| Hand-off | `PartyJoinHandoff`: lobby hero "Joining Seraph" (opening state and both lobby layouts), loading screen "Joining Seraph's party", cleared at first frame |
| Dock | `WatchTogetherDock` above `PlaybackLoadingHost`, bottom-right, 340dp; 44dp avatar + ring bubble under 720dp, expands on hover; hidden over Stream/Player routes (the player mirror is Stage 6-7) |

**Identity boundaries.** `SocialRepository.activate` awaits `onIdentityBoundary(previous)` first, before
presence clear and `closeRealtime()`; `shutdownSocialLayer` awaits it before
`WatchPartyRepository.setActiveProfile(null)`; the Watch Party capability going false triggers it;
`LocalAccountDataCleaner.wipe` calls `onAccountWipe()`. The cancel goes out **as the previous profile**
(`cancelJoinRequestAs`, deliberately not through `socialCall`), bounded to 5s; `already_accepted` departs
via `WatchPartyRepository.departMembershipAs(profile, party)`.

**Sign-out ordering, checked:** `AuthRepository.signOut` revokes the official session, then wipes, then
the profile goes null. Nothing invalidates the Z token on that path, so the boundary cancel can still
authenticate while that token is unexpired; if it cannot, the request expires server-side within two
minutes. Abandoned request ids are persisted per profile (`SocialStorage.*AbandonedJoinRequests`, all three
platforms) and `reconcileAbandoned` runs after `restore()` on activation: accepted → leave that party,
pending → cancel, older than 10 minutes → forget.

Deviation: the wipe path attempts the server cancel best-effort rather than local-only - it costs nothing
when the network is gone, and it is the only chance to cancel on sign-out.

Gate: `compileTestKotlinDesktop` BUILD SUCCESSFUL; social + watchparty + presence + party-room
`desktopTest` BUILD SUCCESSFUL (319); `SocialRenderHarness` (dock states at 1280 and 420) green; pure
suites all eight groups green. ⚠ iOS `SocialStorage` / `socialUtcOffsetMs` actuals not compiled here.

## Social + Watch Together UX pass — Stage 2 deployed, Stage 3: Recently Watched + Watching Now UI (2026-09-15)

⚠ **Rendered, not hardware-verified.** Branch `claude/social-wt-ux-pass`; plan and ledger in workspace-root
`PLAN-social-watch-together-ux-pass.md`.

**Stage 2 (backend `5eab132`, deployed with approval to `pzbpghmmordvzcfbayoh`):**
`social_join_request_status`, `social_cancel_join_request`, a `cancelled` request status, invalidation
triggers on `watch_join_requests` for both sides, and `expires_at` on `approval_required`. pgTAP 12 files /
286 green locally; objects, grants and triggers checked on production afterwards. Nothing on desktop
calls the new RPCs yet (Stage 4); the only live effect is that a host hears about a new request at
once instead of on its ~20s heartbeat.

**Stage 3:**

| surface | change |
| --- | --- |
| Friends' activity (Home) | `FriendActivityRow`: no surface at rest, 44x66 mini-poster (poster → background → monogram), avatar stack, names · relative time, title, `S2 E4–E9 · 6 episodes`. Grouped by title via `groupFriendActivity`, keyed by `contentId`, ≤12, **See all** opens the Social tab. Row 260/280/300dp by window; two lines under 720dp. Heading `titleSmall` |
| Recently Watched (Social) | Today / Yesterday / This week / Earlier overlines (local days via new `socialUtcOffsetMs` expect/actual), 1-3 columns, **automatic paging** near the end with an inline spinner |
| Watching Now | identity first (28dp avatar + play-state dot, `Playing`/`Paused`), progress bar on the artwork, tonal button from `watchingNowJoinAffordance` (Requested ring → Cancel on hover), stacked artwork under a 340dp card. Same-title cards adjacent (`orderWatchingNowForDisplay`), never merged; keyed per session. Empty state is one muted line with the corrected copy |

Two §11 items diagnosed in `SocialRenderHarness` before touching them:

- **§11.13 Home shelf's first card cut off - root cause found and fixed.** `LazyRow` anchors to the first
  visible key, so an item arriving in front of an unscrolled shelf landed scrolled 216px off the left edge
  (`aShelfAtTheStartStaysAtTheStartWhenSomethingNewArrivesInFront` failed with exactly that, then passed).
  `SocialHomeShelf` now keeps an unscrolled shelf at its start; a scrolled one is left alone.
- **§11.11 friends rail clipping at 2000px - not reproduced.** The harness now renders the real
  `SocialFriendsPanel` in the real rail modifiers (it was an empty box); at 1920 and 2560 nothing clips.
  No fix made. If it recurs, capture the window's exact size and UI scale.

Harness fixtures per the plan: one friend + movie, a binge split across runs, three friends on one title,
two friends on one episode, broken avatar/artwork URLs, long names and titles; rendered at 420x900,
1280x820, 1920x1080, 2560x1440 and 3840x2160. The harness also provides the screen's `LocalContentColor`
now (friend names rendered black without it - a harness defect, not an app one). ⚠ Broken-URL fallbacks
cannot show in a frame-0 render (Coil has not errored yet); judge them in the app.

⚠ The iOS `actual` for `socialUtcOffsetMs` is written but not compiled here.

Gate: social + home `desktopTest` packages BUILD SUCCESSFUL (152 tests); pure suites all eight groups green
(217 / 107 / 61 / 17 / 29 / 115 / **56** / 3).

## Social + Watch Together UX pass — Stage 1: pure models (2026-09-15)

Branch `claude/social-wt-ux-pass` (off `claude/phase-5-onboarding`). Plan and ledger: workspace-root
`PLAN-social-watch-together-ux-pass.md`. **Nothing user-visible yet** — every file here is pure and is
wired in by later stages.

| file | what |
| --- | --- |
| `social/FriendActivityGrouping.kt` | `groupFriendActivity` (runs merged by `contentId`), names / context labels, relative time, local-day buckets, an import-free PostgREST timestamp parser |
| `social/OutgoingJoinRequest.kt` | `reduceOutgoingJoinRequest` returning effects, `decideJoinRequestPoll`, `boundaryCancelFollowUp`. Every async event carries a `JoinRequestBinding(ownerProfileId, token)`; a mismatch is dropped |
| `social/WatchingNowJoinAffordance.kt` | Join / Ask to join / Requested / Joining… / In your party / none |
| `watchparty/PartyPlaybackStatus.kt` | `projectPartyPlaybackStatus` (the §5 priority table) + `debouncePartyStatus` (700ms appear, 1200ms minimum, same identity in place) |
| `player/WatchTogetherPanelState.kt` | `projectWatchTogetherPanel`, connection chip, `partyLeaveSuccessor` (mirrors `party_live_successor`) |
| `PartyTick.hold` | optional `"hold"` array on the tick, sent only during a hold; protocol stays 2 |

Deviations from the plan, all to keep these files executable by the pure suites: the store will live in
its own `OutgoingJoinRequestStore.kt` (the plan put it beside the reducer); `PartyPromotionFailure`
moved to `PartySessionContracts.kt`, and `WatchPartyParticipant.displayName` / `WatchPartyState.effectiveStage`
moved to `WatchPartyPresentation.kt` (same package, no call site changed). The status projector checks
Offline before Reconnecting when both hold.

Gate: `compileKotlinDesktop` + targeted `desktopTest` (9 classes, 105 tests, BUILD SUCCESSFUL); pure
suites all eight groups green (217 / 107 / 61 / 17 / 29 / **115** / **55** / 3). Full `desktopTest` is
owed at Stage 8.

## Stabilization pass — Direct Join role flip and Next-episode mode routing (2026-09-15)

⚠ **Not hardware-verified.** Two defects from repeated testing of the Stage 16 build; full record and
re-run cells 17a/17b in workspace-root `PLAN-social-watch-together-stabilization.md`, Stage 17.

| defect | root cause | fix |
| --- | --- | --- |
| Guest pressing Direct Join ended up host | Read from production: the host promoted a *different* presence session 23 s after the guest's join built party `e7d7fc48`, before its heartbeat had adopted that party. `party_promote_presence_internal` created a second party, `watch_party_single_membership` departed the host from the first, and host succession gave it to the guest (`authority_epoch` 2). Separately, `social_join_watching` answers `already_joined` with *any* live membership, which the client opened unchecked. | `decidePartyPromotionPreflight`: promotion probes `party_get_active` and adopts the party built from this playback, refuses over any other live party, never displaces. `joinWatchingNow`: opens only a party the target is in, departs an unheld stray membership once and retries once, ignores duplicate presses. |
| Next episode auto-selected in every mode | `c28493cc` sent desktop Streamlined's in-player Next episode to `AUTO_PICK` because the Compose sheet can't draw over the native player | Router is mode-only again (Classic list / Streamlined quality / Instant auto). Desktop draws Streamlined's quality rows in the native episode list (`PlayerEpisodeQualityChooser.kt`), same options and selector as the sheet. Autoplay-next countdown unchanged. |

No backend change. Recommended server hardening (needs deploy authorization): refuse rather than
displace in `party_promote_presence_internal`; scope `already_joined` to the receiver's party.

Gate, results dir deleted first, run alone: `desktopTest` **BUILD SUCCESSFUL in 12m 34s - 236
classes, 1,875 tests, 0 failures, 0 errors** (1,864 → 1,875 is exactly +8 join/promotion, +4 chooser,
−1 router); pure suites all eight groups green (217 / 107 / 61 / 17 / 29 / 99 / 3 / 3). No MSI built.

## Stabilization pass — second hardware run: six defects fixed in code, re-run owed (2026-09-15)

⚠ **Nothing here is hardware-verified.** A second two-client run passed most of the matrix and failed
six cells; all six are fixed in code with pure coverage. Full record, root causes and the re-run list
(16a-16g): workspace-root `PLAN-social-watch-together-stabilization.md`, Stage 16.

| bug | root cause | fix |
| --- | --- | --- |
| 1 Escape orphaned a live party | desktop Escape is the system back, which was a plain `pop()`; the departure dialog also popped on a failed RPC | `dispatchNavigationBack` routes the lobby through its own departure question and fails closed; the lobby closes only on an accepted departure or a gone party (`WatchPartyLobbyExit.kt`) |
| 2 Guests did not follow Next episode | the guest handoff loaded *episode* streams and waited on the *sources* catalogue; mid-transition guests owned their own autoplay-next; the host's in-flight advance could pull it back | `partyEpisodeCatalogueFor`, title-based `ownsNextEpisodeChoice`, `pendingPublishedContentGeneration` |
| 3 Next episode showed Classic's list to a Streamlined host | desktop Streamlined deliberately mapped to `SOURCE_LIST` because the Compose sheet can't draw over the native player | desktop Streamlined → `AUTO_PICK` within Streamlined preferences, the autoplay-next selector |
| 4 Next-episode loading screen plain text | three call sites nulled the logo whenever an episode was starting — in every mode, party or not | `playerOpeningPresentation` |
| 5 End party never reached guests | `party_close_ended` sets `left_at` on every member, so every guest RPC raises `party_membership_required` and the ended snapshot is unreadable; only a player effect reacted to "ended" | membership refusal → `party_get_active` confirmation → local conclusion; `observePartySnapshot` owns the terminal transition on every route; source route aborts |
| 6 Ask to join / Join did nothing | Home's button unwired (`onStartParty = {}`), failures silent, no signal to an accepted guest, no signal to a promoted host | Home wired (parameter now required); every outcome surfaced; guest polls `party_get_active` for the request's life; host delivers on its presence heartbeat and adopts in place, host only |

No backend change and no deploy. Backend definitions were read from production with
`pg_get_functiondef` (read-only) and match the migrations. ⚠ Joining latency is bounded by the 20 s
presence heartbeat; a `watch_join_requests` trigger would remove it but needs a deploy authorization.

Gate, each invocation alone, results dir deleted first: `desktopTest` **BUILD SUCCESSFUL, 235
classes, 1,864 tests, 0 failures** (+37, exactly the cases added); pure suites all eight groups green.
**No MSI built yet** — the hardware re-run needs a new one.

## Stabilization pass — first hardware run: Watching Now works, two defects found and fixed (2026-09-12)

⚠ **The pass is still open.** One physical run happened; it passed the thing that mattered most and
failed two others, both now fixed. The rest of the two-client matrix is untouched. Ledger:
workspace-root `PLAN-social-watch-together-stabilization.md`, Stage 14.

**PASSED, do not reopen.** Watching Now publishes end to end on hardware — A played, B saw the right
title and episode, Ask First produced **Ask to join**. Presence encoder, backend write, friend
refresh/realtime and the Social UI are all confirmed. The old "presence never publishes" bug is
closed.

**Failure A — presence never cleared on leaving playback.** A left the player; B kept seeing A at
**PAUSED** until the row aged out. `BindSocialPresenceEffect`'s `onDispose` launched the clear on
`runtime.scope`, which is a `rememberCoroutineScope()` — the departure that runs the dispose is the
same departure that cancels it, so the RPC was cancelled before it landed and the last state the
backend held was the Paused publish from before the exit.
⚠ **This is not a TTL bug even though it presents as one.** Shortening `SocialPresenceStaleMs` would
have hidden a clear that never ran and started evicting genuinely paused friends. Fixed in
`3413fb2c`: `SocialPresenceSession.detachAndClear` owns a process-scoped clear that outlives the
composition, and declines if another session attached meanwhile, because presence is keyed by
*device* and a late clear would delete a relaunched player's row. Pause, episode handoff and party
promotion all still keep presence; only leaving detaches.

**Failure B — the Social redesign broke in a real window**, in three ways, all fixed in `190a0c6d`.
- **Width collapse.** The column count came from `maxWidth - rail` (three columns) and was spent
  inside a `LazyColumn` capped at `widthIn(max = 600.dp)` — 177dp a card, a ~60dp text column, and
  `PAUSED` / `Ask to join` set one character per line. Same mistake one layer up:
  `fillMaxSize().widthIn(max = 1440.dp)` capped nothing, because filling sets the minimum width to
  the parent's. `SocialFeedMetrics.kt` now derives width, columns, card widths and artwork widths in
  one place, read by both the screen and the harness.
- **Recently Watched demoted past legibility.** 260x76 with 92dp of artwork left ~140dp for a title
  (`The D…`). Now 300x88, Watching Now 344x132 — still well under Continue Watching, because the
  hierarchy is the height and the small 16:9 still, not a starved title.
- **Home titles black on a dark card.** `Surface` defaults content colour to `contentColorFor(color)`
  and `surface.copy(alpha = …)` matches no role, so it fell through to `LocalContentColor.current` —
  which Social provides and Home does not. The card names its own content colour now.

⚠ **The render harness passed the build hardware failed.** It drew the feed with no rail and no width
cap — constraints production never applies. **A harness that composes its subject differently from
production is worse than no harness**, which is the same lesson this repo already learned one layer
down. It now composes the real constraint stack, derives metrics from its own `BoxWithConstraints`
(the window size is *not* the width the feed divides — `NuvioTheme` scales density), and carries
`homeCardsDoNotInheritTheHostContentColour`, which was verified to fail when the fix is removed.

Gate at `190a0c6d`, each invocation alone, `test-results/desktopTest/` deleted first:
`compileKotlinDesktop` clean; `desktopTest` **BUILD SUCCESSFUL, 231 classes, 1,827 tests, 0
failures**; all eight pure groups green. 1,813 → 1,827 is exactly the 14 cases added.

**Replacement build for physical QA**, from `240f08f7`, tree clean, local only — nothing published,
tagged or released:

```
composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi
258,983,712 bytes
SHA-256  D1450FBF1AF28CA7D2A66423FBC3882280E50C9ECCA44027AB4C4928CF9F268E
```

⚠ **Same filename as the superseded build** (`D8D88B36…`, 258,971,424 bytes) because the version did
not change. Check the hash before testing; the old file would reproduce both fixed defects.

**Next:** the physical matrix continues — presence teardown first, then click-through, promotion,
source routing, next-episode handoff, artwork, status, attribution, the in-player panel and the
Phase 4 regressions. None of those are passed; green code is not a physical result.

---

## Desktop Social + Watch Together stabilization pass — Stages 0-11 code complete (2026-09-11)

⚠ **Code complete, automated green, nothing run on hardware.** Stages 0-11 of the pass are
implemented; Stage 12 (visual verification) and Stage 14 (the packaged two-client matrix) are
**NOT STARTED**, and the pass is not closed. Persistent ledger, which now exists:
workspace-root `PLAN-social-watch-together-stabilization.md`.

Branch `claude/phase-5-onboarding`, commits `c1c104fd` → `3a923a06`. Backend:
`claude/party-preflight-and-lifecycle`, commit `7d15dde`, **deployed**.

**What was actually wrong, in one line each.**

- **Stage 1, backend.** Both presence sanitizers treated jsonb `'null'` as a value rather than as
  absent. Hardened and deployed; looking for the second one found a **third**,
  `sanitize_party_content`, which had never been diagnosed because it raises no chosen label at all.
- **Stage 2, click-through.** **Social was not the bug.** `AppTabHost` hides Home with `.alpha(0f)`,
  which is a *draw* modifier - Home stayed full-size and in the pointer hit path. Social is merely
  the tab that leaves an uncovered region: it centres content under `widthIn(max = 1440.dp)`, so a
  window wider than that has live Home gutters. Fixed at the tab-ownership layer.
  ⚠ **Not an onboarding bug**; the wizard was not touched.
- **Stage 3, status.** Durable readiness outranked live telemetry unconditionally. Only the
  *generation* separates a stale `fetching` from the one `party_change_content_v2` legitimately sets.
- **Stage 4, source routing.** The lobby hardcoded `manualSelection`, which is the first thing
  `PlaybackModeRouter` tests, so every host got Classic's list. No new router input was needed.
- **Stage 5, artwork.** The party wire carries identity, not presentation. Hydrated locally rather
  than widened.
- **Stage 6, promotion.** ⚠ **Could not have worked before `c1c104fd`**: the backend builds the party
  from `watch_presence`, and no row was ever being written. Four silent failures now report.
- **Stage 7, next episode.** `changeContent` existed with **zero callers**. Host publish converges on
  the one local apply; the guest reuses the source handoff's catalogue, matcher, readiness and
  barrier. Countdown is host-only.
- **Stage 8, attribution.** ⚠ **The actor data was never wrong** - `1ec3ae6f` fixed it at 13:30 and
  the note reporting it was written at 15:49. The notice went to a Compose overlay, which desktop
  cannot draw over the native video surface, so nobody read it.
- **Stages 9-11, UI.** Social had Continue Watching's literal card metrics; the avatar fell back only
  on a *blank* URL; the player panel scrolled as a whole and went to a negative height on a short
  window.

**Verified at `5cfe33d6`:** `:composeApp:desktopTest` — **BUILD SUCCESSFUL in 17m 30s, 229 classes,
1,813 tests, 0 failures** (baseline on `1c5ee9ea` was 1,783), all three known flakies passing;
`scripts/run-pure-suites.sh` all eight groups green; backend `scripts/test-db.sh` 11 files / 253
tests green. New pure coverage: `WatchPartyPresentationProjectorTest` (4→8),
`PlaybackModeRouterTest` (+2), `PartyLaunchArtworkTest` (6), `PartyContentSwitchTest` (13),
`SocialRenderHarness` (1) and the backend's 18 sanitizer cases.

⚠⚠ **A collided Gradle run nearly produced a false green, and it is worth knowing how.**
`composeApp/build/test-results/` is **not cleared** when a run fails to compile, so a run that died
on `Daemon compilation failed` — two concurrent invocations, the `AGENTS.md` rule 3 trap — left the
*previous* run's XML in place, and tallying it reported a complete green suite for code that had
never been built. **Confirm the run's own `BUILD SUCCESSFUL` line before trusting a test count**, and
when recovering delete `composeApp/build/test-results/desktopTest/` as well as the classpath
snapshot.

**Packaged build for the physical matrix**, local only, nothing published or tagged:
`Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`, 258,971,424 bytes, SHA-256
`D8D88B369954A1FA45B5F081C3CE972196231DFA4E04881A36762F8EE5F953C7`.

**Owed, and it is the whole remaining risk:** the physical matrix in the ledger. Nothing in stages
2 and 4-11 has been looked at by a human.

## Stabilization pass — the Stage 0 record (2026-09-11)

Named pre-Phase-6 release gate, **not** a numbered phase; Phases 6–9 are not renumbered. Persistent
ledger: workspace-root `PLAN-social-watch-together-stabilization.md`. Opened from a two-user friend
test on the Phase 5 MSI that found eight Social/Watch Together defects. Branch
`claude/phase-5-onboarding`, first commit `c1c104fd`.

**Finding D (Watching Now showed nobody) is fixed, and it was not the social gate.** Planning
proposed the Phase 5 gate as a shared seam for two findings; the maintainer's test facts killed it
(master toggle ON and Share Watching Now ON on both clients, one actively playing). The real cause
was found in the database rather than by reasoning, and then proven against the live project.

⚠ **A JSON null is not an absent key.** `social_publish_presence` sanitizes with
`sanitize_source_descriptor_v2(p_entry->'source_fingerprint')`, whose null guard is
`if p_value is null then return null` — an **SQL** NULL test. An absent key reads as SQL NULL and
returns cleanly; an explicit `"source_fingerprint": null` reads as jsonb `'null'`, which is *not*
SQL NULL, so the guard misses it, `jsonb_typeof` answers `'null'` rather than `'object'`, and the
publish aborts with `invalid_source_descriptor` (22023). kotlinx defaults `explicitNulls` to
**true**, so from `d7cf2bf7` (2026-09-08, which added the descriptor to the presence payload) every
publish made **outside** a party failed. Fix: `SocialRepository`'s encoder sets
`explicitNulls = false`. **No backend or schema change.**

⚠ **There were two of these, not one.** `sanitize_party_track_intent` fails the same way on the
same input — verified live, it raises `invalid_track_intent` on jsonb `'null'` — and
`SocialPresencePublish.trackIntent` is *always* null on this path. So the presence payload carried
two independent kill-switches, and fixing only the descriptor would have changed nothing. One
encoder setting closes both, which is the argument for fixing it there rather than field by field.

⚠ **The second half of the bug was silence.** `publishCurrent` discarded the publish `Result`, so a
rejected RPC was indistinguishable from a healthy one, and the readiness guard
(`isEnded || isLoading || durationMs <= 0`) returned without saying so. Both now report through a
`SocialPresence` log tag. Keep them; "no row in `watch_presence`" is otherwise indistinguishable
from "never asked".

**The evidence, for anyone re-treading this.** `watch_presence` has **no reaper** — the 90-second
window is a read-time filter — so rows survive and the table is a usable audit log:

- Friend test reconstructed from `social_activity_events`: seraph S1E1 at 18:50, zokaper S1E1 at
  19:41, **both S1E2 at 20:26:17, 0.3 s apart** — the party. `created_at ≈ watched_at` throughout,
  so these are organic writes, not an outbox backfill.
- Across that whole window, **zero** `watch_presence` rows from either client. Newest presence:
  seraph 09-09, zokaper 09-03. Every row ever written has a NULL `source_fingerprint`.
- Watched-activity publishing from the *same* sessions worked repeatedly, which exonerates auth,
  the profile, the friendship and `share_watching_now` — and, since `SocialWatchedActivity` is
  gated on `SocialFeatureGate.isEnabled`, independently **proves the social gate was ON**.
- `watch_parties` holds a sanitized descriptor for the 19:43 party, so the sanitizer accepts the
  real descriptor; only the null case fails.

**Verified:** `:composeApp:compileKotlinDesktop`; `:composeApp:desktopTest --tests
"…features.social.*"` green including the four new `SocialPresencePayloadTest` cases;
`scripts/run-pure-suites.sh` all groups green (the `explicitNulls` change touches decoding too, so
the broad suite is the real check here). ⚠ The `buildWindowsPlayerBridge` configuration-cache
warning is pre-existing and unrelated.

**Owed on this finding:** one physical run to confirm a row now appears — this needs **one** client
playing anything for ~30 s, not a two-client party. Then re-check `watch_presence`.

**Done, authorized and deployed (2026-09-11):** both sanitizers now treat jsonb `'null'` as absent,
and so does `sanitize_party_content`, which turned out to have the same fault and had never been
found because it raises no chosen label - it dies on a raw `cannot call jsonb_each on a non-object`.
Migration `202609110002_json_null_is_an_absent_payload.sql`, function bodies only, deployed to
`pzbpghmmordvzcfbayoh` and verified against the live database afterwards. The client remains correct
without it; this is what stops **mobile meeting the identical trap in Phase 6**.

## Phase 5 Setup / onboarding redesign — code complete, unverified on hardware (2026-09-10)

Branch `claude/phase-5-onboarding`, cut from `1c5ee9ea`. Persistent ledger: workspace-root
`PLAN-phase-5-onboarding.md`. **Phase 4 is closed and none of its deferred debt was touched.**

**Status: STAGES 0-8 DONE, STAGES 9-10 NOT STARTED.** Everything below compiles and passes the
automated suites. **Nothing has been run on a real install**, and the wizard is a screen that gates
the app — four of its previous six revisions reached a device broken in a way only looking caught.
Treat the physical matrix in the plan as the real gate.

The wizard is revision 7. It now asks **what Nuvio Z does before what it looks like**: playback
mode → only the configuration that mode can use → sources → an explicit whole-app social opt-in →
identity if that is on and not already set → two appearance steps instead of four → three summary
lines. Preserved untouched: `PlaybackModeCard`, the animated storyboard and its pointer/no-pointer
grammar, restart-on-mode-change, the Welcome still, named-step restoration, the dynamic plan,
immediate real-setting writes, the monotonic revision protections and `nuvioConsumePointerEvents`.

**The branch rule is outside Compose.** `playbackSetupVariant(modeName)` lives in the import-free
`SetupWizardSteps.kt`, because that file is the only part of the wizard a test can reach.
`PlaybackSetupVariantCoverageTest` walks the real `PlaybackMode` entries, so a mode added without a
variant fails there rather than in front of a user. **Classic gets no playback-setup step at all** —
its own card says *"You read the releases and pick one"* and the storyboard holds longest on a
pointer walking every row, so offering to skip the list on the next screen would contradict both.
`streamAutoPlayMode` stays in Settings.

**The social preference is not a player setting.** `SocialFeaturePreferencesRepository` +
`…Storage` under `features/social`, with its own `ProfileSettingsSync` payload, in the shape of
`EpisodeReleaseNotifications`. It is stored **nullable** so "never answered" stays distinguishable
from "chose off", which is what `resolveSocialFeaturesEnabled` needs to keep an established social
user from losing their friends to a new boolean defaulting false. A cache-cold install is rescued
by `SocialRepository.probeExistingIdentity`, which reuses the existing RPCs, opens no realtime and
publishes no presence. ⚠ It is deliberately **not** routed through `mergeMonotonicSyncInt`: that is
right for a revision that may only rise and wrong for a preference a user may switch back off.

**Social OFF is a lifecycle transition, not a visibility change.** `shutdownSocialLayer()` departs
any party through `WatchPartySessionCoordinator.leave()` and waits for the phase to settle, then
`WatchPartyRepository.setActiveProfile(null)`, then `SocialRepository.activate(null)` — and the
callers run it **before** writing the preference, so the surfaces cannot vanish out from under an
in-flight departure. Sixteen surfaces are gated through one `SocialFeatureGate`.
`coerceAvailableTab` is a pure coercion beside `AppScreenTab.fromName`, which still parses `Social`
in both states.

**New in Settings: a Social page.** Before this there were **zero** social rows anywhere in
Settings — the handle, both privacy toggles and the join policy all lived inside `SocialScreen` and
would have become unreachable the moment the tab could be hidden. The master toggle is the one
social surface that stays visible when social is off; without it the preference is a one-way door.

**Verified (automated only):**

- `scripts/run-pure-suites.sh` — all eight groups green. `SetupWizardStepsTest` 24 → **34 tests**,
  covering all 24 plan permutations through both termination properties, all three ways a step can
  leave the plan under the user, every removed step name, and the full migration truth table.
- `./gradlew :composeApp:desktopTest` — **BUILD SUCCESSFUL in 15m 29s, 1,783 tests, no failures**,
  including all three known flakies. Baseline on `1c5ee9ea` was also fully green, in 14m 14s, so a
  red run from those three here is load rather than this branch.
  ⚠ Run it **alone**. Two concurrent Gradle invocations on this project produced one spurious
  `desktopTest FAILED` during this session — the collision `AGENTS.md` rule 3 describes. `./gradlew
  --stop`, delete `composeApp/build/kotlin/compileKotlinDesktop/classpath-snapshot/`, run once.
- `SetupWizardRenderHarness` now draws **three full runs** — `streamlined`, `instant`,
  `classic-social-off` — at four window sizes, 128 PNGs in `composeApp/build/setup-wizard-render/`.

**One real defect was found by looking at those PNGs and fixed.** `SetupChoiceGroup` gave every
option `weight(1f)` and `maxLines = 1`, which is fine for two short words and destroys anything
longer: the playback step drew *"Only play what I can watch"* as "Only play what I", and rendered
*Prefer SDR*, *Prefer HDR*, *Require HDR* and *Require Dolby Vision* as "Prefer", "Prefer",
"Require", "Require" — four chips, two readable labels, no way to tell them apart. It is a wrapping
`FlowRow` of content-width chips now. **This is exactly what the harness is for; keep running it.**

**Owed, and it is the whole remaining risk:**

1. **Stage 9 — Hot Reload / MCP.** Not started. Needs `scripts/dev-desktop.ps1 hot` and then a
   session restart so the MCP attaches. Drive the window to 1280×820, ~900 dp (below
   `DesktopWizardMinWidth`, stacked) and 2560×1440 / 3840×2160.
2. **Stage 10 — the physical matrix**, on a debug MSI. Listed in full in
   `PLAN-phase-5-onboarding.md`. The launch that proves the wizard stays dismissed is the **second**
   one. ⚠ `Settings → Run setup again` still has no physical result since the
   `nuvioConsumePointerEvents` fix, and the two-client turn-social-off-mid-party case has never run.
3. **`nuvio-z` was deliberately not touched.** Mirroring `SetupWizardSteps.kt` alone breaks the
   mobile build — its `SetupWizardScreen.kt` and `SetupDiagram.kt` reference `SetupStep.Cards`,
   `Home` and `Details` in nine places — and fixing that means porting mobile's wizard body, which
   is Phase 6 work. The divergence map and the list of what Phase 6 can take wholesale are in
   `PLAN-phase-5-onboarding.md` §Stage 11.

**Roadmap renumbered.** Onboarding took the Phase 5 slot, so "Social to mobile" is now Phase 6,
"Identity and release engineering" 7, iOS 8, TV 9. `HANDOFF-phase-4-codex-2.md` §12 still says
"Phase 5" for the mobile repointing; that means Phase 6 now.

### Phase 5 friend-test MSI published (2026-09-10) — TEST ARTIFACT, not a closure

⚠ **This is not Stage 9, not Stage 10, and does not close Phase 5.** It is a manually cut MSI handed
to a friend outside the automated CI release pipeline, published purely so physical-install QA has
something to run against. Stages 9 and 10 remain **NOT STARTED** exactly as above.

GitHub prerelease `phase5-test-20260910` (https://github.com/Zokaper/NuvioZDesktop/releases/tag/phase5-test-20260910),
built from `claude/phase-5-onboarding` at commit `096add38e676eefb5117e26965689f53a7eab346` (the same
commit this status section already describes — working tree was clean, nothing new landed for this
build). `Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (253,022,400 bytes; SHA-256
`76ABF78944D72B45D5E7F8EF512450B40229434CBC84A186AEF274365AD36471`), packaged with the standard
release-style `:composeApp:packageReleaseMsi` task (no `-Pnuvio.desktop.debugChannel=true`) — same
shape as the Phase 4 friend-test artifact, not the debug-channel build.

**Deliberately invisible to both in-app updaters**, verified against the current predicates in
`AppUpdaterPlatform.desktop.kt`/`AppUpdater.kt`: the release channel requires `!prerelease`
(`includePrereleases = false` when not `debugChannel`) and this release has `prerelease: true`; the
debug channel requires the tag to start with `debug-` and `phase5-test-20260910` does not. No
existing Nuvio Z install, release or debug, will ever offer this as an update.

Phase 5 remains **OPEN**. This build proves nothing about Stage 9 (Hot Reload/MCP) or Stage 10 (the
physical matrix) — it only makes a real MSI available for a friend to start running that matrix
against.

## Phase 4 Watch Together — closure status (2026-09-10)

**Final status: DONE WITH NON-BLOCKING QA DEBT.** The architecture below (Stages 1–7 of
`PLAN-watch-together-architecture.md`) is implemented, backend-deployed, and has now been
exercised on two real desktop clients with a successful outcome for the core experience. The
formal 21-scenario matrix in `PHASE-4-TWO-CLIENT-VERIFICATION.md` (invites, host transfer,
reconnects, cross-addon/cross-debrid matching, etc.) has **not** been executed; only the five-item
refinement retest in that file has a recorded physical result. Phase 5 has not started.

Confirmed working on two real clients, real profiles, isolated data roots (2026-09-10, MSI
`Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`, SHA-256 `0469315D11CDA34E865E468F7C936A538B46DAC20D4275811841F0FA704C02BF`):
realtime peer/server delivery with no "Live sync lost", host/guest playback sync (effectively
frame-perfect in the observed run), pause/resume sync, seek sync, guest-ready-first barrier hold,
host-ready-first barrier hold with automatic resume and no manual Play, host-only lock making every
guest transport control inert while volume/fullscreen/subtitles/audio stay local, and the earlier
false startup-stall/bounce-to-source-loading bug not recurring. See the "Result, 2026-09-10" note
under the refinement retest in `PHASE-4-TWO-CLIENT-VERIFICATION.md` for the exact wording.

**Known non-blocking bug — deferred, not fixed here.** Collaborative-control actor attribution:
when collaborative controls are enabled and a guest (e.g. "Big Z") pauses, resumes, or seeks, the
in-player indicator can still attribute the action to the host instead of naming the guest.
Command authority and playback correctness are unaffected — this is presentation-only. Related
caveat: a machine-generated barrier pause/resume may also read as human-attributed; distinguishing
that properly needs additional command/wire semantics and is out of scope for Phase 4.

**Future UX/performance polish — deferred, not started here.** The in-player Watch Together panel
works but needs a later pass on (1) visual hierarchy/layout/readability/information density and
(2) responsiveness — state-update latency, recomposition/update behavior, interaction feedback, and
any unnecessary delay/jank. Not a Phase 4 blocker; do not redesign it as part of this closure.

**Carried Phase 5 debt (already deployed, documented in full under the entry below):** the backend
tolerates a missing `authority_epoch` on Realtime party broadcasts so an install between protocol
v1 and v2 fails for a chosen reason and mobile's Phase 5 repointing isn't blocked on the epoch field
landing first. Removal instructions are in `202609100001_release_liveness_authz_and_lifecycle.sql`
and section 12 of `HANDOFF-phase-4-codex-2.md`.

**Known unrelated flaky tests (not Phase 4 blockers):** `WatchedItemsStoreTest`,
`DesktopDownloadQueueE2ETest`'s trickle/drop case, and
`NativePlayerControllerTeardownTest.failedOrdinaryDisposeBlocksTerminalNavigation` — all fail only
under machine load and pass run alone; unrelated to Watch Together.

## Watch Together - the Realtime transport had never delivered anything (2026-09-10)

The first physical two-client run produced two independently-proven failures. Both are fixed here;
neither has been re-verified on hardware, and the physical gate is still open.

### Bug A - no client broadcast has ever been delivered

The logs said it plainly once somebody looked: both clients subscribed, both stayed
`realtime=subscribed`, every send returned local success, durable polling succeeded continuously,
and `WatchPartyTrace T3 RealtimeReceived` was **zero for the entire run** - and zero in every
historical debugTools log beside it. `PartyRealtimeHealth` therefore never left
`SubscribedUnverified`, `PartyPresentationProjector` correctly showed "Live sync lost", and the
party ran entirely on five-second durable polling.

The cause is a misunderstanding of what `realtime.messages` RLS is. **Supabase Realtime authorizes a
private channel, not a message.** On join it asks the policies for a read capability and a write
capability and caches both for the life of the socket; the write capability is decided by inserting
a **stub row** - topic and extension set, `payload` NULL - inside a transaction it then rolls back.
There is no per-broadcast payload hook. `nuvio_private_realtime_write` called
`realtime_party_write_allowed(party, payload, extension)`, whose first payload test refuses an
absent `sender_profile_id`, so it was asked about NULL and refused. Every authenticated member was
granted read and refused write, permanently. A refused push is answered with nothing at all, and
supabase-kt does not await an ack for `broadcast()`, so the client saw only successes.

Proven live rather than by calling the helper: two authenticated members of a real party joined
`party:<id>`, both joins replied `{"status":"ok"}`, and a push of the exact protocol-v2 tick
payload - correct sender, correct generations, correct epoch, a payload the helper itself returns
`true` for - reached nobody, not even the sender under `broadcast.self`. A payload-free capability
policy delivered both frames to both sockets. A second push carrying *no* sender or generation
fields at all was delivered just as readily: the payload is never consulted per message, so the
"strict" checks were not protecting anything - they were only refusing the join probe.

Since per-message sender binding does not exist at this layer, authority moved to the topic:

- **`party:<id>` is the authority plane.** Members read it; **no client may write it**. The backend
  authors everything on it - the existing `state` broadcasts, and now the accepted transport
  command, which `party_submit_command_v2` emits from the row it just locked with the actor,
  generations, epoch and committed sequence *it* knows, plus `origin=server`.
- **`party_peer:<id>` is the peer plane.** Members read and write it: host position ticks, the clock
  exchange, per-member telemetry, presence. Nothing on it may command the party, and
  `partyMessageIsAdmissible` refuses a command arriving there before anything looks at it. Every
  sender-sensitive message is still checked against the durable snapshot - a tick only from the host
  the server named, a pong only from that host for an exchange this client started.

This is strictly tighter than what was deployed, because clients could not write the party topic at
all: a forged `state` payload - which a bare "make the policy payload-free" fix would newly have
allowed - stays impossible. A guest cannot forge the host, a stale generation cannot become
authoritative, a non-member reaches neither plane, and a host transfer bumps the epoch that the next
authored command carries. The one thing deliberately not closed: a member can still misreport its
own buffering state to the party, which costs a hold it did not need and nothing else.

`WatchPartySync.issueCommand` now takes a required `submitDurable` lambda instead of broadcasting.
The local directive still fires first, so the sender's own player never waits on the network; the
durable submission is the command's only route to anybody else, and being a parameter is what stops
a call site forgetting it - forgetting it used to cost a database row, and now costs every other
member the command. `WatchPartyCommand` carries `start_at_party_ms` and `play_after` so the barrier
survives the trip. `PartyRealtimeSendOutcome.Success` is renamed **`LocallyAccepted`**: it was never
evidence of delivery, and naming it `Success` is what made a dead transport look healthy.

### Bug B - a party hold was counted as a playback startup stall

Also physically reproduced, and not authority churn, not a realizer restart, not a launch-claim
failure: a guest's source loads slowly, the startup watchdog arms, Watch Together holds the guest at
the readiness gate while it waits for the party, the host starts, and the host pauses again before
the guest's first frame has settled. The guest sits deliberately paused at 7257ms. Its buffer stops
advancing - a paused player has nothing to advance for - and twelve seconds later
`PlaybackStartupWatchdog` reported `Stalled`, `onFatalPlaybackError` ran
`failOverAfterPlaybackStarted()`, `signalFailoverRetry()`, `popBack()`, and the guest was dumped
into source loading out of a party that was working.

`PlaybackStartupSample.isHeld` **freezes** every deadline rather than exempting the source from any
of them: `State.holdMs` accumulates held wall-clock and every comparison runs against
`effectiveElapsedMs`. A hold that ends also rebases the stall deadline once, because a player parked
for a minute has an empty pipeline to refill and handing it back whatever fraction of the deadline
was left would abandon a source for the restart the party itself caused. A source that is genuinely
dead after release still fails over. `isHeld` defaults to false, so non-party startup behaviour is
unchanged - asserted, not assumed.

The player publishes the hold through `resolvePartyStartupHold`, which names the mechanism doing the
holding (gate, barrier, or a party pause) and carries the gate's own reason with it.

### Logging hardening

Two clients launched in the same second wrote into the **same log file**, and the whole physical run
was interleaved with no way to attribute a line. Disk logs are now
`nuvio-debug-<stamp>-p<pid>-<entropy>.log`, the instance tag rides **every** line rather than only
the header, and the header names the appdata root as well. `PartyHealthState` transitions are logged
as transitions - `realtime=X->Y api=X->Y capability=X->Y` with the event that caused them - because
the run had `RealtimeSubscribed` in the log and "Live sync lost" on screen with nothing saying those
were the same fact. A channel that is still `SubscribedUnverified` after 20s now says so once, and
`PlaybackStartup` abandon diagnostics carry the party role, status, stage, hold reason, gate reason
and generation tuple.

### Verification

- pgTAP **235/235** against a database reset from scratch, including 16 new assertions that
  exercise the NULL-payload stub row the way a channel join does.
- Backend migration `202609110001_realtime_capability_and_server_authored_commands.sql` **deployed**
  to `pzbpghmmordvzcfbayoh`; live policies and the installed `party_submit_command_v2` read back
  afterwards read-only and match.
- Live two-plane probe on a real stack: 7/7 - both members join both planes, the peer plane carries
  ticks and telemetry both ways, a forged command pushed at the authority plane reaches nobody, and
  the server-authored command arrives with the server's sender and the committed tuple.
- Pure suites **all 8 groups green (517 tests)**. Group 6 had been *silently failing to compile* at
  HEAD - `PartySessionContracts.kt` was never added to it, so nine "passing" tests were nine
  initialization errors. The transport's pure rules are now in `WatchPartySyncRules.kt` so the suite
  compiles and executes them rather than reporting a green it had not earned.
- Focused desktop party/player/playback tests **597/597**, and `:composeApp:compileKotlinDesktop`.

**The physical gate has not passed.** Nothing here has been observed on two real clients; that run
is the next step and Phase 5 has not started.

## Watch Together deterministic architecture - desktop UltraReview fixes (2026-09-10)

Two findings from the desktop UltraReview of PR #7, both closed.

The reconnect loop could stop reconnecting for the life of the process. `openChannel` wraps the
subscribe in `withTimeout`, and the `TimeoutCancellationException` that throws *is* a
`CancellationException`, so `maintainChannel`'s `catch (c: CancellationException) { throw c }` sent
it out through the `collectLatest` on `desiredAuthority` that drives `WatchPartySync` for the whole
app. One subscribe that ran long took the authority collector with it, and every party after that
one sat on the durable poll with nothing left to reopen a channel. The classification now lives in
`partyChannelFailureIsScopeCancellation`, which reads a cancellation the loop caused itself as a
failed attempt and only a cancellation it did not cause as the scope going away: a subscribe timeout
takes the ordinary degraded/report/backoff/retry path, and genuine cancellation still propagates
untouched. The shared reporting is one `reportOpenFailure`.

`WatchPartyRepository.installSnapshot` no longer takes `reopenChannel`. Every caller that named it
passed `false`, and the ones that took the default made a second `WatchPartySync.updateAuthority`
call with the argument the method had already passed a few lines earlier - a no-op by that method's
own equality checks, and a second place to have to keep right.

`WatchPartyChannelReconnectTest` covers the regression with real casualties rather than hand-built
stand-ins: a genuine `withTimeout` expiry, and a genuine cancellation observed by a child whose
scope went away. Focused Watch Party desktop tests 150/150 and `:composeApp:compileKotlinDesktop`
pass. No desktop UltraReview finding remains open. The physical matrix is untouched and still
outstanding.

## Watch Together deterministic architecture — Stage 7 partial (2026-09-09)

The first deletions of Stage 7, limited to scaffolding that is provably dead or provably duplicated.

`WatchPartyUiState` no longer carries `connection` or `connectionBannerMessage`. Both were copies of
what `PartyPresentationProjector` produces, recomputed inside `updateHealth` and cached on the
repository, which made the repository a second presentation authority for a fact it does not own -
and one that could disagree with the screen beside it. The lobby already projects its own
presentation and now reads the connection from it; `connectionBannerMessage` had no reader at all.

`PartySessionShadowState` and the shadow comparison in `WatchPartySessionCoordinator` are gone. They
existed to compare the Stage 1 reducer against the legacy repository snapshot during the switchover,
and nothing has read them since. A comparison nobody looks at is not a safety net - it is a second
answer with no arbiter.

Verified by inspection as already complete, and needing no deletion: disposal-as-lobby (location is
published from explicit session intents, and attachment loss publishes nothing), the destructive
active-player lobby flow (Stage 3), the repository launch latch and route-owned resolution (Stage 4),
and the duplicate client host claim (Stage 5).

**Stage 7 remains open.** v2 contract removal is deliberately not started: mobile still calls the v2
party RPCs against this backend, so removal waits on the Phase 5 repointing rather than on this
stage. Stage 7's exit is the full physical matrix, which is outstanding along with the Stage 2, 3, 5
and 6 physical gates.

Focused party/player tests 289/289 and `:composeApp:compileKotlinDesktop` pass.

## Watch Together deterministic architecture — Stage 6 automated checkpoint (2026-09-09)

Active source switching is implemented in-route. A member picking a source from the player's own
sources panel now moves the whole party: `shouldPublishPartySourceChange` gates it on the party
playing this exact content, the member being permitted (host always, guest only while
collaborative), and the pick not being the source the party is already on. The advance names the
generation it expects, so two simultaneous picks produce one advance and one rejection, and a local
latch stops a retry or a debrid re-resolution of the same pick from advancing it twice. A refused
advance releases both latches and leaves the local swap standing as an alternate.

Every other member adopts the new source without going anywhere. `decidePartySourceHandoff` returns
`Adopt` only for a generation this player has not acted on and is not already playing; the player
loads the catalogue through its own `PlayerStreamsRepository`, runs the Stage 4 strict matcher and
settle decision over it, and hands off with the same `switchToSource` an in-player pick uses. The
old source plays throughout, and the route, controller and HWND are untouched by construction. A
member who cannot realize the party's new pick reports `choosing_fallback` and keeps playing what
they have: the generation is never silently rolled back, and a failed adoption is remembered so it
is not retried against a catalogue that has already answered.

The player's party identity key was `"$id:$contentGeneration"` and is now the whole authority tuple.
Omitting source generation and authority epoch is what would have left every party effect - gate,
readiness, ticks, drift, telemetry - running against the source or the host it had just replaced. An
active player also spends the automatic-launch claim for the authority it is playing, so a member who
adopted a switch in place and then backed out to the lobby is not thrown straight back into it.

Focused party/player tests pass 289/289 - the full blast radius of the change - and
`:composeApp:compileKotlinDesktop` passes. Stage 6's exit gate is physical and outstanding: a
two-client switch, exactly one generation advance, and failure/fallback/cancel/retry/stale behaviour
on real clients.

## Watch Together deterministic architecture — Stage 5 automated checkpoint (2026-09-09)

Backend commit `b681c45` in `nuvio-z-backend` carries the minimum change the Stage 1-4 client
evidence proved necessary, and nothing else. The party state broadcast now carries
`authority_epoch` alongside the `source_generation` and `stage` that were already deployed; the
member broadcast now fires on `client_location`, which was the one member field the presentation
reads that nothing announced. Liveness has one owner per question: 15 seconds is host-transfer grace
only, `party_heartbeat` marks a member offline at 20 to match `party_reap_stale`, and transfer picks
a replacement from members live within that same 20-second window rather than any member seen in the
last minute. `party_claim_or_transfer_host` stays as an RPC because mobile still calls it, but it now
delegates to `party_transfer_stale_host` instead of deciding again with its own window.

On the desktop client, `applyBroadcastState` is now the pure `applyPartyStateBroadcast`, which
returns a typed outcome and treats an authority advance the way it already treated a generation
move: as an invalidation to refresh through, never as a payload to apply. The local grace-and-claim
host race is deleted - the client learns its new host from the broadcast like any other authority
change - and `WatchPartyHostGraceMs` is documented as the backend's number, mirrored for description
rather than applied.

pgTAP passes 165/165 against a fresh local database including ten new Stage 5 assertions; focused
party/player tests pass 280/280; `:composeApp:compileKotlinDesktop` passes. **The migration is not
deployed.** `supabase db push` is the maintainer's to run against project `pzbpghmmordvzcfbayoh`;
both directions are compatible, so client and backend may land in either order. Stage 5's
two-client physical verification of transfer and location propagation is outstanding, as are the
Stage 2 and Stage 3 physical gates.

## Watch Together deterministic architecture — Stage 4 automated checkpoint (2026-09-09)

Party source realization is now owned by a process-scoped `PartySourceRealizer` keyed on the exact
authoritative identity `(partyId, contentGeneration, sourceGeneration, descriptor)`. It owns the
work states (`Unresolved`/`Matching`/`Resolving`/`Ready`/`FallbackRequired`/`Failed`), the one-shot
automatic-launch claim, and the sensitive resolved `PlayerLaunch`, of which only an opaque
realization ID ever leaves the object. Every entry point is rejected unless it names the current
authority, so a match or resolution that completes after the host changed the source cannot report
into - or resolve for - the party as it now is.

The three route-local owners this replaces are gone: `WatchPartyRepository`'s
`launchedSourceGeneration` latch and `claimSourceLaunch()`, `PlayerLaunchStore`'s three party-launch
retention methods, and the strict-match rule that existed only as a `remember` block inside
`StreamDestination`. That rule is now the pure `tierPartyPlaybackSources` + `decidePartyRealization`
pair, so the settle gate and the match are testable without a composition. `StreamDestination`
reports `matching`/`resolving`/`fallbackRequired` and, through the single `giveUpToSourceList` choke
point, `abandoned`; it no longer owns any of that state. `PartyStreamLaunchContext` carries the
content generation so the route builds the same complete key the repository installs.

Readiness is now derived from real work rather than from whichever screen is composed: the session
coordinator publishes `fetching`/`resolving`/`choosing_fallback`/`failed`/`source_ready` from
realizer transitions, each carrying its source generation. The realization is dropped on source or
content generation advance, leave, end (including a remote end arriving by snapshot), profile
change - now unconditionally, so a failed departure RPC cannot carry one profile's resolved media
into another - and account wipe. Returning from the player to the lobby reuses the retained
realization without a `StreamRoute` and cannot re-arm the automatic launch.

Focused party/player suites pass 270/270, the mandatory Stage 4 full `:composeApp:desktopTest` gate
passes 1,708/1,708 with zero failures, errors, or skips, and explicit `:composeApp:compileKotlinDesktop`
passes. Stage 4 has no physical gate of its own; Stage 2
and Stage 3 physical gates remain outstanding and unchanged.

## Watch Together deterministic architecture — Stage 3 automated checkpoint (2026-09-09)

Stage 3 active-player architecture is implemented and focused-green. `PlayerScreenRuntime` now
owns `partyRoomOpen`; the Watch Together control toggles the native Party Room for an active party,
and Back/Escape closes the room before any player exit. Opening/closing it performs no navigation,
RPC, source/generation mutation, controller release, or HWND mutation. The former active-player
`partyLobby -> requestBack()` command is removed.

Kotlin sends one typed `PartyRoomViewState` containing projected participant status, live health
and sync text, content/source details, invitations, control mode, wait setting, lifecycle actions,
and recovery errors. JavaScript only renders it and emits typed actions. Existing-party invitation
acceptance now resolves through the explicit `OpenPrePlaybackLobby` outcome, so same-content joins
still release the current player and begin a fresh party-owned preparation/attachment; only the
existing explicit create-around-current-playback action promotes in place.

Focused Party Room wire/page, entry-guard, lifecycle, retained-launch, player-surface, and session
tests pass; `node --check` passes; explicit `:composeApp:compileKotlinDesktop` passes. Stage 3 remains
formally `IN_PROGRESS` until repeated open/close, Back/Escape, same/different-content invitation,
and controller/HWND preservation are physically verified. Per the maintainer's architecture-first
strategy, Stage 4 may proceed while that physical gate remains recorded.

## Watch Together deterministic architecture — Stage 2 automated gate green (2026-09-09)

Post-checkpoint physical testing exposed two lifecycle races, now fixed in the pending Stage 2
stabilization checkpoint. A rejected Z token is replaced under the session mutex without first
publishing `NotAuthenticated`; only an actual HTTP 401 triggers that exchange, and the Z client no
longer runs an independent platform-auth setup. This prevents Realtime from tearing down private
channels while concurrent durable calls fall back to the publishable key. Realtime close is now
idempotent and distinguishes reconnect cleanup from intentional detach, while cancellation of an
in-flight broadcast is no longer reported as a failed send. Leaving a party also no longer changes
Swing visibility from `NativePlayerHost.removeNotify`, avoiding an invalidation of an already
disposed Compose `SkiaLayer`. Focused auth/transport/airspace tests and
`:composeApp:compileKotlinDesktop` pass. The physical two-client sync/leave gate remains outstanding.

Stage 2 remains `IN_PROGRESS` on `codex/watch-together-architecture`; implementation commit
`7365d45` completes the automated Realtime-transport and unified-presentation checkpoint. The
private channel now belongs to `WatchPartySync`: it creates the authenticated channel with
broadcast acknowledgements enabled, owns subscription/reconnect/close and protocol collectors,
reports actual lifecycle/send/receive health by channel instance, and clears generation-scoped
ticks, telemetry, dedupe, and hold state without replacing a healthy channel. Backend/API success
no longer claims live Realtime health; only validated traffic from another party member does.

Accepted play, pause, seek, and speed commands now emit their local directive synchronously before
either network path begins. Realtime send and durable persistence run independently in the
background, and a delayed send completion cannot update a replacement channel's health. Host-only
guest controls are rejected before any directive. Guest telemetry is visible to every client for
fresh per-member presentation while the existing host-only buffering watch retains all prior
grace, settle, cooldown, budget, late-join, and drift behavior.

`PartyPresentationProjector` is now the shared pure authority for connection banners, fresh host
status, and participant labels in the lobby and native player payload. Actual local playback owns
the self label; remote labels use only fresh live tick/peer evidence and never the global durable
party status. Stale evidence falls back to readiness/location rather than claiming playback.

The backend authorization correction is already committed as `67d4ced` in `nuvio-z-backend` and
was already deployed with migration history repaired; it was not repeated here. Verification on
the final source is green: explicit `:composeApp:compileKotlinDesktop`; 32/32 focused protocol,
health/permission, projector, playback-lifecycle, and retained-launch tests; and the mandatory
Stage 2 full `:composeApp:desktopTest` gate, **1,687/1,687**, zero failures/errors/skips. The
previously flaky stalled-download harness test passed in that full run.

Fresh release-style debug-tools MSI:
`composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
(258,725,663 bytes; SHA-256
`AF4445327C5AAAF39BA4F802F24AF0371F3FBCC2D1D6DD5D5A7E5F35CF0C243E`). The physical two-client
Stage 2 exit gate is still **NOT RUN**: prove p95 command delivery below 500 ms with no sample above
1 s, local directive below 50 ms, truthful degradation/recovery, and per-member labels. Do not
start Stage 3 until that evidence passes or the maintainer explicitly decides otherwise.

## Watch Together deterministic architecture — Stage 1 PartySession ownership and health split (2026-09-09)

Stage 1 is `DONE` on `codex/watch-together-architecture`. New domain seams separate the durable
gateway and live transport from the serialized, process-scoped `WatchPartySessionCoordinator`.
The coordinator now reduces typed intents in one queue, models attachment loss independently from
party membership/lobby entry, routes readiness/lifecycle actions, and shadow-compares its identity
and generation state with the legacy repository snapshot while that snapshot remains the Stage 1
UI authority. The durable gateway exposes party-domain snapshots rather than `WatchPartyUiState`,
and the live transport consumes an immutable authority context plus typed health/refresh sinks
instead of reading repository or UI state.

Health now records durable API reachability, poll activity, heartbeat success, Realtime channel
instance/lifecycle, receive time, peer and clock freshness, and send outcome independently. A
successful subscription or send remains `SubscribedUnverified`; only peer channel traffic proves
the live plane. Replacement channels clear prior live-plane telemetry, clock traffic does not
refresh peer freshness, and successful HTTP work never claims Realtime health. The existing RPCs,
broadcast payloads, generation checks, durable polling fallback, and wait-for-everyone timing
behavior remain compatible; no backend code or schema changed.

Durable heartbeat ownership is process-scoped: the repository poll keeps member liveness alive in
lobby/source/player states and publishes only fresh exact-generation telemetry supplied by the
player binding. Player disposal now reports attachment loss and clears process-local telemetry
without inferring lobby membership; explicit lobby entry publishes lobby location. Authorized
notification snapshots still install synchronously before navigation, while their semantic reducer
event remains serialized, avoiding a redundant-join race introduced by a fully queued install.

Stage 1 verification on the final source is green: 64/64 focused desktop tests pass across session
health, protocol, playback lifecycle, retained party launches, player surface lifetime, exit
navigation, and party route behavior; explicit `:composeApp:compileKotlinDesktop` passes. The prior
full `:composeApp:desktopTest` attempt ran 1,679 tests and failed only the untouched
`DesktopDownloadQueueE2ETest.a stalled download restarts from zero once before giving up`: after
240 seconds its 6 MiB fixture had completed instead of failing. The isolated unchanged test then
passed 1/1 in 135.83 seconds, evidence of an unrelated timing-sensitive harness failure rather than
a Stage 1 regression. Under the persistent verification policy, full desktop suites run after
Stages 2, 4, and 7/final (or earlier for unusually broad changes), not merely because a stage ends.
The next full-suite gate is Stage 2. Missing private-channel delivery remains unresolved and is the
Stage 2 transport problem; faster durable polling is still not an accepted correction. Stage 2 has
not begun.

## Watch Together deterministic architecture — Stage 0 instrumentation (2026-09-08)

Branch `codex/watch-together-architecture`. Added debug-gated, privacy-safe `WatchPartyTrace`
correlation for T0 input, T1 permission/acceptance, T2 Realtime send outcome and duration, T3 peer
receipt/validation, and T4 native-engine directive application. The same trace records channel
instances/lifecycle, independent observed API/Realtime/poll facts, durable command and snapshot
arrival, clock/tick freshness, and guest hold start/release evidence. No playback, send ordering,
health, source, navigation, or lifecycle behavior has been changed. The physical procedure is
`WATCH-TOGETHER-STAGE0-TRACE.md`.

Verification on the final source: desktop compilation passes; focused Watch Together/player-launch
tests pass (96/96). The immediately preceding complete instrumentation revision passed the full
desktop suite (1,674/1,674), and the final revision only tightened debug gating and added trace-only
outcomes before the focused rerun. The physical two-client 10 pause/resume + 10 seek matrix and
controlled Realtime interruption remain **NOT RUN**, so Stage 0 remains `IN_PROGRESS` and no
behavioral Stage 1 work may begin.

Instrumentation is committed as `dd3e2b4f2755bc5149911dcfa6aa38f68e3f559f`. Its background
release-style packaging completed with the Gradle-managed JetBrains JDK; Gradle's generated jpackage
arguments confirm `-Dnuvio.debugTools=true`. The install artifact is
`composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,631,455 bytes;
SHA-256 `133AEEA118C756CC326DD4EC33CA85B7F84A1C7513DED44476176DBC92E63F25`). The
published artifact and both `main-release/msi` copies are byte-identical. This is the required
physical Stage 0 test build; packaging does not advance the stage.

A partial same-machine physical run used two installed clients and produced distinct host/guest
logs. It covered five pauses, four resumes, and three seeks. Every host T2 send reported success,
yet the guest recorded zero T3 receives and no timing-plane clock/tick traffic while both clients
claimed `realtime=subscribed`. The guest followed durable state via `fallbackHold`/`fallbackDrift`:
11 observed user-command sequences arrived in 1,045–6,097 ms (median 3,560 ms; average 3,730.5 ms),
and one short pause was overwritten before the guest observed its sequence. Host T1–T2 itself cost
468–766 ms because local emission follows the awaited broadcast. This reproduces and attributes the
reported delay to absent live peer delivery plus durable fallback, but the private-channel failure's
cause is not yet established. The exact evidence is recorded in `WATCH-TOGETHER-STAGE0-TRACE.md`.
Seven more seeks and the controlled interruption/recovery segment remain required, so Stage 0 stays
`IN_PROGRESS` and Stage 1 remains blocked.

Maintainer decision after reviewing that evidence: Stage 0 is `DONE` and the remaining five
ordinary seeks plus controlled Realtime interruption/recovery segment are **SKIPPED**, not passed.
Stage 1 is now `IN_PROGRESS`. Missing private-channel delivery remains an open defect; Stage 1/2
must diagnose and correct live delivery and truthful health rather than shorten the durable poll.

## Phase 4 follow-up hardening: lifecycle resilience, truthful presence & UI fidelity (2026-09-08)

Completed an ironclad hardening pass for Watch Together covering disconnects, app exits, stale parties, reconnects, lobby/player transitions, truthful presence, and remaining Phase 4 UI issues:

1. **Friends Recently Watched Presentation Fidelity:**
   - Unified `TitlePresentationCard` with Continue Watching presentation modes: `Card` (landscape artwork with dark gradient overlay, top-right duration badge, bottom-left title/episode metadata, bottom progress bar), `Wide` (horizontal split card with fixed-width artwork strip and structured metadata block), and `Poster` (vertical 2:3 poster card with title block below).
   - In `HomeSocialSections.kt`, wrapped `SocialHomeRow` in `BoxWithConstraints` to dynamically evaluate layout style and card metrics (`continueWatchingLandscapeCardMetrics`), eliminating fixed 310.dp sizing and matching Continue Watching styling across all viewports.
2. **Notification Card Differentiation & Metadata Projection:**
   - Social notifications now render with contextual headers ("Watch Together", "Friend Request") and distinct social icons (`Icons.Filled.People`), dropping the misleading playback-resume header/icon.
   - Fixed backend `social_get_state_v2` and `SocialNotifications.kt` to project complete `content_summary` (`content_id`, `content_type`, `video_id`, `title`, `poster`, `release_year`, etc.), ensuring Watch Together invitation/join prompts display the target media's artwork and title.
   - Progress bar is suppressed on notification prompts (`showProgress = false`).
3. **Truthful Disconnect & Offline Presence:**
   - Expanded member status model (`DerivedMemberStatus` & `PartyReadyTone`: Ready, Working, Paused, Buffering, Reconnecting, Failed, Offline) across lobby tiles, player pills, and CEF HTML controls.
   - Reconnecting or disconnected members are honestly reported as "Reconnecting..." or "Offline" rather than claiming "Ready" or "Playing Together".
   - Local playback continues uninterrupted during network outages while honest offline banners and member statuses reflect actual connection state.
   - Reconnect heartbeat and epoch recovery restore live membership without duplicate entries.
4. **Stale Party & Content Guarding:**
   - Added content-identity guard in `resolveWatchPartyEntry`: entering a watch party for a specific target content verifies whether any held or active party matches the exact content; mismatched parties are automatically departed/closed first.
   - Backend migration `202609080002_party_lifecycle_and_notification_hardening.sql` deployed and recorded on Supabase project `pzbpghmmordvzcfbayoh`: provides `party_set_client_location` (updates `last_seen_at` and presence), automated stale reap (`party_reap_stale`), and snake_case `content_summary` projection in `social_get_state_v2`.
5. **Verification & Artifacts:**
   - Backend pgTAP tests pass: 153/153 tests green across all 6 test files (`stage14_lifecycle_hardening.sql` included).
   - Pure test suites pass: 495/495 tests green across all 8 groups.
   - Desktop test suite passes: 1,674/1,674 tests green in `:composeApp:desktopTest` with zero failures, errors, or skips.
   - Release-style debug-tools MSI packaged: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,619,167 bytes; SHA-256 `935F0A239BA845AE714B50BF7F056B519A716028BB3E85B293FE792CDA29D937`).
   - All physical verification scenarios in `PHASE-4-TWO-CLIENT-VERIFICATION.md` remain marked **NOT RUN** for physical maintainer validation; Phase 4 is not complete.

## Phase 4 Stage 14 PlayerRoute/lobby lifecycle correction (2026-09-08)

Physical two-client testing found that leaving `PlayerRoute` was incorrectly treated as losing the durable party source: player disposal published `ready_state=resolving`, and pressing Start in the lobby called `party_begin_source_selection` again. That backend RPC correctly opened a new source generation, cleared the selected descriptor, and reset connected members to `waiting_for_host`; the desktop client was invoking it for a local attachment transition. The lobby also had no reusable resolved-launch cache and party rematching initially inherited Classic mode's source-list surface, causing the observed list flash.

Desktop now separates durable source publication, process-local source realization, and player attachment. Player disposal changes only local attachment/location; a published descriptor is reused for its exact party/content/source generation, or locally rematched without republishing; real generation changes invalidate the retained launch and any staged host pick. Location/readiness snapshot RPCs are serialized, delayed older generation/epoch/sequence snapshots are rejected, and normal player attachment publishes `client_location=player`. Party resolution is a loading route from its first frame and cannot expose the ordinary source list unless matching explicitly fails. The existing held/restored-party entry fix is included so reopening Watch Together does not create a second party.

Automated verification is green: pure suites 495/495; focused Watch Party/player/navigation/route-surface desktop tests pass; full `:composeApp:desktopTest` 1,672/1,672 with zero failures/errors/skips; and `:composeApp:compileKotlinDesktop` passes. A release-style debug-tools MSI was built without rebuilding the unchanged native bridge: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,590,495 bytes; SHA-256 `67C32A059D7866A21B478AC46A5FD346CA551825F46BD794B506DA5D744796D6`). Backend code/schema was not changed or deployed. Stage 14 remains pending maintainer physical retest and is not marked PASS; Phase 4 is not complete.

## Phase 4 Stage 14 live source-selection hotfix (2026-09-08)

The first physical two-client run exposed a backend contract bug: `WatchPartyRepository` legitimately serialized nullable descriptor fields as explicit JSON `null`, while the deployed `sanitize_source_descriptor_v2` rejected any present `file_index` key when `info_hash` was null. Backend migration `202609080001_accept_null_party_file_index.sql` now accepts and strips an explicit-null unknown index while preserving rejection of negative indices and real indices without an info hash. It is deployed and recorded only on Nuvio Z project `pzbpghmmordvzcfbayoh`.

No desktop production code changed, so the current Stage 14 MSI remains the retest artifact. Focused descriptor/source tests pass, pure suites pass 484/484, and backend pgTAP passes 142/142. The physical two-client scenario remains pending maintainer retest and is not marked PASS.

## Phase 4: automated implementation gate green; watched matrix pending (2026-09-08)

Phase 4 desktop Stages 1-13 are implemented. Stage 12 is now complete: the Home and Social activity surfaces share `TitlePresentationCard`, title artwork selection is import-light and covered by the pure harness, and `SocialActivityChip` is retired. The native notification renderer now maps its three allowed actions to explicit bridge commands rather than synthesizing command names.

Automated verification is green:

- focused native controls JSON/page tests: 7/7;
- pure suites: 481/481 across eight groups;
- `:composeApp:compileKotlinDesktop` passes;
- targeted player/navigation/social/watchparty desktop tests pass;
- full `:composeApp:desktopTest`: 1,647/1,647, zero failures/errors/skips;
- backend pgTAP passes 130/130 after the reconciliation migration was updated to preserve `party_member_broadcast_update` across the live `source_match` type conversion (`nuvio-z-backend` commit `3ddc6eb`).

Release-style debug-tools MSI, including a freshly rebuilt Windows native player bridge, was built successfully at `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (252,670,144 bytes; SHA-256 `9083BFE6B7A2C1C579475635CE532612EF1CD1CCA27C4475DB8C568BF1B7A4B9`). The exact physical checklist is `PHASE-4-TWO-CLIENT-VERIFICATION.md` and every scenario remains **NOT RUN**.

The two scoped Phase 4 migrations, `202609030003` and `202609040001`, are deployed and recorded on Nuvio Z Supabase project `pzbpghmmordvzcfbayoh`. Live catalog verification confirms the four new enums and labels, `watch_join_requests`, all Phase 4 columns, both partial unique indexes, the checked-in RPC signatures, lifecycle/Realtime helpers, and the Realtime validator policy. `get_social_capabilities()` reports contract version 2 while preserving `social_enabled=true` and `watch_party_enabled=true`; live sanitizer probes reject URI-, URL-, header-, and credential-bearing source descriptors. No client scenario was run during deployment.

Phase 4 is **not complete**. The two-client watched matrix has not been executed. Never deploy Nuvio Z schema changes to `api.nuvio.tv`.

Last updated: 2026-09-07

## Phase 2 follow-up: Seamless desktop player handoff (2026-09-07)

Branch `claude/phase-2-desktop-handoff`.

### Watched MSI result — Phase 2F COMPLETE / ACCEPTED (2026-09-07)

Maintainer watched verification of the packaged MSI (`composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`) has been completed and Phase 2F is **ACCEPTED**.

**Confirmed working in real use:**
- **Source / Play → Loading:** Clean, immediate presentation using the 1dp `SwingPanel`/native-airspace gate preventing white-screen occlusion while the heavyweight player initializes underneath.
- **Loading → Video:** Premature native presentation, dark/light gradient flashes, and floating controls are eliminated. Promotion occurs strictly when real decoded video frames are verified (`PlaybackHandover.hasFirstFrame`).
- **Player → Previous Screen (One-press Escape):** Direct exit pops smoothly to `DetailsDestination` without re-entering the stale loading screen. Teardown is decoupled to background threads and concealment occurs after previous destination draw (`T2`), eliminating the black/gray gap and old white flash.
- **Failover & Selection Integrity:** Canonical ranked failover chain remains correct and advances automatically on stalled sources; "Choose source manually" uncovers the source list cleanly; mixed HDR/DV classification is preserved internally and formatted as composite labels (e.g. `HDR10/DV`).

**Accepted Presentation Limitation:**
- Desktop transitions involving the heavyweight native AWT/Win32 player (`NativePlayerHost` HWND) are now functionally clean and substantially smoother, but player <-> Compose transitions are not true crossfades.
- Loading -> player and player -> previous screen can still feel like controlled cuts rather than fully blended fades due to heavyweight HWND/AWT airspace dominance.
- The existing smooth loading -> cancel/details fade remains intact.
- This is an accepted presentation limitation for Phase 2F, not an open blocker. No further presentation or native-airspace work is scheduled.

### Latest implementation (Phase 2F Final Visual Handoff)

See detailed handoff in `PHASE-2F-HANDOFF.md`.

- **Task A (Player -> Details / Escape Exit):** Eliminated the black gap on exit. Proven root causes: (1) `host.concealInteropAirspaceForExit()` was called immediately at T0 before pop (T1) and previous destination draw (T2); (2) `MainAppContent.kt` had `fadeIn(tween(180, delayMillis = 60))` on `popTransitionSpec` for desktop, rendering `DetailsDestination` at alpha 0.0 for 60ms; (3) playback was not paused at T0. Fixed by freezing playback immediately on Escape (`setPaused(true)`), deferring native concealment to T2 (`PlayerExitDiagnostics.runAfterPreviousDraw`), and setting `popTransitionSpec` to `EnterTransition.None togetherWith ExitTransition.None`.
- **Task B (Loading -> Player Startup Pre-Frame Flash):** Eliminated premature native presentation, empty gradient surface, and floating controls. Proven root causes: (1) C++ `isLoading()` dropped to false on header parsing before real video frames were decoded; (2) `controls.html` and `controls.js` showed transport controls and gradients while `isLoading` was dropped; (3) AWT Canvas promotion didn't immediately resize `containerHwnd` in C++. Fixed by making C++ `isLoading()` require `hasFirstFrame()` driven by `MPV_EVENT_PLAYBACK_RESTART = 21`, position >= 0, and estimated frame >= 1; dispatching `"firstFrame"` event to Kotlin; hiding transport chrome by default in HTML/JS; decoupling opening overlay from `isLoading`; and wiring `host.onHostResized` to immediately resize the native container.
- **Native Player Bridge Compiled & Hardened:** Rebuilt `composeApp/build/native/windows/player_bridge.dll` (554,496 bytes) with `jetbrains_s_r_o_-25-amd64-windows.2` JDK (`BUILD SUCCESSFUL in 1m 3s`), adding handle-safety tracking (`gActivePlayers`) to prevent memory dereferencing on arbitrary or stale native handles.
- **Verification & MSI Artifact:** Pure test suites green (460/460 passed). Focused desktop player unit tests green (`NativePlayerAirspaceGateTest`, `NativePlayerControllerTeardownTest`, `NativePlayerControlsJsonTest`, `NativePlayerControlsPageTest`). Exit navigation tests green (`PlayerExitNavigationTest`, `PlayerExitOrderingTest`). Fresh release-style debug MSI packaged: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,298,898 bytes) built with `"-Pnuvio.desktop.debugTools=true"`. Maintainer watched verification passed. Phase 2F complete.

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

4. **Current Verified State (Latest Watched MSI Run):**
   - **Status:** **COMPLETE / ACCEPTED (2026-09-07)**.
   - **Startup Entry Handoff:**
     - The 1dp `SwingPanel` / native-airspace gate successfully fixed the visible startup transition.
     - Source → loading is visually smooth; heavyweight native player is concealed underneath until first real decoded video frame (`PlaybackHandover.hasFirstFrame`).
     - Pre-frame flash of gradient/chrome is eliminated.
   - **Native Airspace Architecture:**
     - Heavyweight AWT/Win32 Canvas airspace dominance respected.
     - 1dp parking until first real decoded video frame (`PlaybackHandover.hasFirstFrame`) is the canonical working architecture.
   - **Player Exit Navigation:**
     - Direct player exit requires only one Escape, skipping the transient retained `StreamRoute`.
     - Fatal playback failure strictly retains `StreamRoute` for automatic failover.
     - "Choose source manually" retains `StreamRoute` to display the source list as requested.
     - Old white flash and loading re-entry are eliminated.
     - Teardown is asynchronous in background thread `nuvio-player-release` (`T4`), and native concealment occurs at `T2` post-draw.
   - **Mixed HDR/DV & Failover Ranking:**
     - Mixed HDR10/DV streams retain both capabilities internally and display composite labels (`HDR10/DV`, `HDR/DV`).
     - Canonical failover order verified.
   - **Accepted Presentation Limitation:**
     - Desktop transitions involving the heavyweight native AWT/Win32 player are functionally clean and substantially smoother, but player <-> Compose transitions are not true crossfades.
     - Loading -> player and player -> previous screen can still feel like controlled cuts rather than fully blended fades due to heavyweight HWND/AWT airspace dominance.
     - The existing smooth loading -> cancel/details fade remains intact.
     - Recorded as an accepted presentation limitation for Phase 2F, not an open blocker.

5. **Verification & Artifacts:**
   - Pure test suites: 460 tests passed clean across all 6 groups (`scripts/run-pure-suites.sh`).
   - Desktop player test suite: passed clean (`:composeApp:desktopTest --tests "com.nuvio.app.features.player.desktop.*"`), including `NativePlayerAirspaceGateTest` (4), `NativePlayerControllerTeardownTest` (24), `NativePlayerControlsJsonTest` (2), `NativePlayerControlsPageTest` (3), `DesktopAppFullscreenTest` (3), and `DesktopPlayerVolumeTest` (3).
   - Exit navigation tests: passed clean (`:composeApp:desktopTest --tests "com.nuvio.app.navigation.PlayerExitNavigationTest" --tests "com.nuvio.app.features.player.PlayerExitOrderingTest"`).
   - Packaged release-style Windows MSI with debug tools:
     `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi` (258,298,898 bytes) built with `"-Pnuvio.desktop.debugTools=true"`.
   - Maintainer watched verification passed. Phase 2F closed.

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
