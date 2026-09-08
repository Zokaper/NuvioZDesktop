# Phase 4 Two-Client Watched Verification

Status: **NOT EXECUTED**. This worksheet prepares the physical Stage 14 run; it records no manual result.

Authoritative criteria: `../PLAN-phase-4.md`, section 14. Use two installed desktop clients with different real social profiles and isolated data directories. Prefer two machines; two Windows processes with separate `APPDATA` roots are acceptable.

## Build under test

- MSI: `composeApp/build/compose/release-msis/Nuvio-Z-Windows-x64-0.1.22-alpha-z1.msi`
- Size: `252,670,144` bytes
- SHA-256: `9083BFE6B7A2C1C579475635CE532612EF1CD1CCA27C4475DB8C568BF1B7A4B9`
- Build command: `./gradlew :composeApp:packageReleaseMsi "-Pnuvio.desktop.debugTools=true" --console=plain`
- Native bridge: rebuilt from the current `player_bridge.cpp` with the installed MSVC toolchain before the final MSI package.
- Backend prerequisite: deploy the two locally verified Phase 4 migrations only to Supabase project `pzbpghmmordvzcfbayoh`. Never deploy to `api.nuvio.tv`.

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
