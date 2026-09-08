# Watch Together Stage 0 trace run

This run attributes command latency before any behavioral change. Use the same instrumented debug
build on two physical clients. Do not paste invite codes or any source/addon/debrid details into this
file or an issue.

## Build and logs

Build or package with `-Pnuvio.desktop.debugTools=true`. Each client writes its startup log under
the Nuvio Z desktop app-data `logs` directory; the exact path is printed as `Nuvio debug log:` at
startup. Keep the two original files and label them `host` and `guest` outside the repository.

Filter the completed files with:

```powershell
Select-String -Path '<host-log>' -Pattern 'WatchPartyTrace'
Select-String -Path '<guest-log>' -Pattern 'WatchPartyTrace'
```

`WatchPartyTrace` contains only shortened party/profile identifiers, command IDs, generation
numbers, positions, timing/health facts, and fixed reason labels. It must never contain a source
descriptor, URL, header, invite code, or credential.

## Required run

Start both clients in the same party and let playback settle. Record wall-clock notes only to help
identify the operation; the command UUID joins the actual evidence.

| # | Actor | Operation | T0–T1 ms | T1–T2 ms | T2–T3 ms | T3–T4 ms | T0–T4 ms | Path/result |
| ---: | --- | --- | ---: | ---: | ---: | ---: | ---: | --- |
| 1 | host | pause | | | | | | |
| 2 | host | resume | | | | | | |
| 3 | host | pause | | | | | | |
| 4 | host | resume | | | | | | |
| 5 | host | pause | | | | | | |
| 6 | host | resume | | | | | | |
| 7 | host | pause | | | | | | |
| 8 | host | resume | | | | | | |
| 9 | host | pause | | | | | | |
| 10 | host | resume | | | | | | |
| 11 | host | seek | | | | | | |
| 12 | host | seek | | | | | | |
| 13 | host | seek | | | | | | |
| 14 | host | seek | | | | | | |
| 15 | host | seek | | | | | | |
| 16 | host | seek | | | | | | |
| 17 | host | seek | | | | | | |
| 18 | host | seek | | | | | | |
| 19 | host | seek | | | | | | |
| 20 | host | seek | | | | | | |

Then interrupt Realtime on one client while leaving backend HTTP reachable. Perform one pause, one
resume, and one seek, wait at least one complete five-second polling interval, restore Realtime, and
repeat those three operations. Record whether each command shows `T2 outcome=success`, `failed`, or
`unavailable`, whether the peer has T3/T4, and which durable broadcast/poll sequence eventually
arrived.

## Correlation rules

- T0 to T1 is local input/permission and coroutine scheduling.
- T1 to T2 is the awaited broadcast call in the current architecture.
- T2 to T3 is network/peer delivery after the sender's broadcast call completes.
- T3 to T4 is peer collection, barrier timing, and native engine application.
- If T3/T4 are absent but a later durable `broadcast` or `poll` changes the peer, classify the
  operation as durable fallback and measure from T0 to that durable arrival.
- Compare `channel`, `realtime`, `polling`, and `api` on every trace. An HTTP success is evidence
  about `api` only; do not infer socket health from it.
- For any `hold` line, retain the member, engine state, telemetry/hold ages, classification, start,
  and release. `genuine-stall-candidate` means the current protocol lacks an explicit late-join vs
  correction marker; the player already suppresses routine correction telemetry, but Stage 0 must
  call this limitation out rather than overclaim certainty.

Stage 0 remains `IN_PROGRESS` until the completed table and both timestamped logs attribute the
reported roughly three-second delay, or the maintainer explicitly authorizes moving on with a
documented evidence-based explanation.
