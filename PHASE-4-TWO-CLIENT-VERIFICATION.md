# Phase 4 Two-Client Watched Verification

Status: **NOT EXECUTED**. This worksheet prepares the physical Stage 14 run; it records no manual result.

Authoritative criteria: `../PLAN-phase-4.md`, section 14. Use two installed desktop clients with different real social profiles and isolated data directories. Prefer two machines; two Windows processes with separate `APPDATA` roots are acceptable.

## Build under test

- MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
- Size: `258,619,167` bytes
- SHA-256: `935F0A239BA845AE714B50BF7F056B519A716028BB3E85B293FE792CDA29D937`
- Build command: `./gradlew :composeApp:packageReleaseMsi "-Pnuvio.desktop.debugTools=true" --console=plain`
- Native bridge: unchanged; the verified existing runtime was reused while the Kotlin application payload was rebuilt.
- Backend prerequisite: deploy verified Phase 4 migrations (`202609030003`, `202609040001`, `202609080001`, and `202609080002`) only to Supabase project `pzbpghmmordvzcfbayoh`. Never deploy to `api.nuvio.tv`.

## Per-scenario evidence

For every scenario, record party ID, content generation, source generation, authority epoch, match tier, sequence, tick age, drift, and lifecycle transitions. Do not record descriptor source text, media/audio/subtitle URLs, request or response headers, addon or repository URLs, debrid service/account/token data, or any credentials.

| Scenario | Host | Guest | Required result | Result | Evidence / notes |
|---|---|---|---|---|---|
| Direct join | Normal playback, direct policy | Click Watching Now | One backend party; host player never reloads; Party Room appears; guest reaches matching/ready/player | NOT RUN | |
| Approval | Normal playback, approval policy | Request join | Actionable request appears inside host player; Accept promotes in place | NOT RUN | |
| Decline | Approval policy | Request join | Decline removes request; no party/membership | NOT RUN | |
| Disabled | Disabled policy | Attempt UI and direct RPC | UI shows unavailable and backend rejects | NOT RUN | |
| Policy race | Approval request pending, switch policy | Wait/respond | Old request expires and cannot be accepted | NOT RUN | |
| Stale request | Ignore for over two minutes | Attempt Accept | Typed stale result; no party | NOT RUN | |
| Invitation | Active party | Accept then repeat with Decline | Lobby join works; decline consumes invitation | NOT RUN | |
| Exact torrent | Same info hash/file index, different ranking preferences | Join | Exact release selected despite guest ranking | NOT RUN | |
| Different file | Same torrent, wrong episode file ranks first | Join | Exact host file index wins | NOT RUN | |
| Different debrid accounts | Same torrent through separate accounts | Join | Each resolves locally; no host URL/header crosses wire | NOT RUN | |
| Cross-addon release | Different addons expose the same release | Join | Release digest/media match wins | NOT RUN | |
| Missing addon | Host origin absent on guest | Join | Guest exhausts strong matches, then sees explicit fallback choice | NOT RUN | |
| Mid-session join | Keep host playing | Join several minutes late | Host never restarts; guest enters near authoritative position and is never authority | NOT RUN | |
| Full lobby step-out | Host or guest exits PlayerRoute | Other keeps playing | Membership persists; other client is uninterrupted; re-entry rematches and resynchronizes | NOT RUN | |
| Participant leaves | Guest Leave Party | Host remains | Guest detaches cleanly; host stays in party/playback | NOT RUN | |
| Host transfers | Host chooses Leave and transfer | Guest remains | Authority epoch changes once; guest becomes host; old host may continue solo | NOT RUN | |
| Host ends | End Party during playback | Guest active | Host continues normal playback; guest pauses and chooses Continue/Exit | NOT RUN | |
| Reconnect | Kill socket, then restart one app | Other keeps playing | Active party restores; no duplicate membership; correct source generation resumes | NOT RUN | |
| Host disconnect | Kill host beyond 15 seconds | Guest remains | Deterministic transfer; stale host messages rejected | NOT RUN | |
| Episode change | Advance episode in player | Guest active/loading | New content/source generation; host keeps playback; guest rematches correct episode release | NOT RUN | |
| Sync regression | Pause/resume 10 times, seek 10 times, real host/guest rebuffer | Observe both | Commands advance sequence, drift settles, no repeated stale seeks or unintended holds | NOT RUN | |
| Cleanup | End/leave/kill clients | Inspect backend | No active orphan membership, live presence, join request, invitation, or abandoned party remains | NOT RUN | |

The exit gate fails if a guest silently plays a lower-tier release while an exact host match exists, even if synchronization appears correct.

## Refinement retest, 2026-09-10 (commit 1ec3ae6f)

Build under test for this section:

- MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
- Size: `252,915,904` bytes
- SHA-256: `0469315D11CDA34E865E468F7C936A538B46DAC20D4275811841F0FA704C02BF`
- Build command: `./gradlew :composeApp:packageReleaseMsi "-Pnuvio.desktop.debugTools=true" --no-configuration-cache --console=plain` on Temurin 21.0.12
- File logging confirmed in the packaged app: `[JavaOptions] java-options=-Dnuvio.debugTools=true`
- Backend unchanged; the deployed Phase 4 migrations on `pzbpghmmordvzcfbayoh` are the prerequisite as above.

Four findings from the first physical run were addressed. This section is the short sequence that
re-checks the four; it is **not** a replacement for the 21-scenario table above, which remains
`NOT RUN` in full.

**Result, 2026-09-10 (reported by the maintainer running two real clients on this MSI):** items
1, 2, 4 and 5 passed as specified — realtime genuinely delivered peer/server traffic, no
"Live sync lost", host/guest sync held (effectively frame-perfect in the observed run),
pause/resume and seeks synced correctly, both barrier orderings (guest-ready-first and
host-ready-first) worked with no manual Play needed, host-only lock made every guest control
inert while volume/fullscreen/subtitles/audio stayed live, and the former false
startup-stall/bounce-to-source-loading bug did not recur. Item 3 (actor attribution) **did not
fully pass**: synchronization was correct, but the in-player indicator can still attribute a
guest's pause/resume/seek to the host instead of naming the guest. This is tracked as a
non-blocking presentation bug — see `STATUS.md` and `ROADMAP.md` — and is deliberately not fixed
in this pass. This run is a targeted smoke/retest of five prior findings, not the formal
21-scenario matrix above; every row in that table is still `NOT RUN`.

Two clients, two real profiles, isolated data roots, **collaborative** control mode with "wait for
everyone" on, unless a step says otherwise.

1. **Barrier keeps the play intent, guest ready first.** Play, seek somewhere unbuffered, let the
   guest finish loading before the host. Expect: guest waits, both start together, nobody presses
   anything.
2. **Barrier keeps the play intent, host ready first.** Same, but let the *host* finish first. Expect:
   host starts, pauses for the guest within ~2.5s, and **starts again on its own within about half a
   second of the guest becoming ready.** This is the finding - if it needs a manual Play, it failed.
   Log lines to confirm: `waiting for <id> intent=playing`, then `stalled guests recovered,
   resuming for=<id>`.
3. **Actor attribution.** From the guest, press pause. Expect the host's screen to read
   "<guest name> paused", not nothing and not "the host". Repeat with resume and with a scrub in
   both directions ("skipped back" / "skipped ahead"). The acting client shows no notice - correct.
4. **Locked transport.** Switch the party to **host-only** and, on the guest, try each of: the play
   button, spacebar, the scrub bar, the ±10s buttons, the arrow-key fine seek, mouse thumb buttons,
   scroll over the scrubber, hold-right-click for 2x, the skip-intro prompt, and the next-episode
   card. Expect every one to do nothing locally and to say "The host controls playback". Then
   confirm **volume, fullscreen, subtitles and audio still work** - those are the viewer's own.
5. **Residual drift.** Pause/resume five times and seek five times. Watch `driftMs` on the guest.
   Expect steady state inside ~±65ms, and expect `holdMs` on the guest's `barrier` lines to now be
   **greater than zero** for most commands rather than zero for all of them - that is the barrier
   lead change working. Post-resume excursions should stay under ~350ms and close in about a second.
