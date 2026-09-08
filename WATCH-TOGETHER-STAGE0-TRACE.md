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

## Partial physical run — 2026-09-08

Two installed clients ran on the same physical Windows machine. Original logs remain outside the
repository as `nuvio-debug-20260908-231045.log` (guest) and
`nuvio-debug-20260908-231053.log` (host). This run covered five user pauses, four user resumes, and
three user seeks. It did not include a controlled Realtime interruption.

Both clients reported `realtime=subscribed`, `channel=ch-1`, and polling active. Every host command
reported T2 `outcome=success`, but neither log contains any T3 event; the guest also contains no
timing-plane clock or tick receipt. All 13 host commands, including the initial gate play, applied
locally at T4. The guest instead reacted to later durable state sequences through `fallbackHold`
and `fallbackDrift`.

| Counter | Operation | T0–T1 ms | T1–T2 ms | Host T0–T4 ms | Guest durable-state arrival ms | Result |
| ---: | --- | ---: | ---: | ---: | ---: | --- |
| 2 | pause | 24 | 561 | 592 | 3,383 | fallback |
| 3 | resume | 5 | 692 | 741 | 3,265 | fallback |
| 4 | pause | 11 | 508 | 531 | 1,045 | fallback |
| 5 | resume | 7 | 496 | 564 | 3,560 | fallback |
| 6 | pause | 9 | 589 | 607 | not observed | overwritten before guest observation |
| 7 | resume | 7 | 492 | 538 | 4,790 | fallback; guest next saw sequence 9 |
| 8 | pause | 11 | 687 | 704 | 4,517 | fallback |
| 9 | seek | 6 | 483 | 501 | 2,803 | fallback |
| 10 | resume | 7 | 468 | 492 | 4,818 | fallback |
| 11 | seek | 14 | 766 | 837 | 5,652 | fallback; buffering snapshot preceded final seek alignment |
| 12 | seek | 5 | 478 | 572 | 1,105 | fallback |
| 13 | pause | 12 | 542 | 565 | 6,097 | fallback |

For the 12 user operations, T1–T2 consumed 468–766 ms (563.5 ms average) because the current host
path awaits the broadcast call before local emission. Host T0–T4 was 492–837 ms (603.7 ms average).
Of the 12 expected guest sequences, 11 were observed through durable state after 1,045–6,097 ms,
with a 3,560 ms median and 3,730.5 ms average. Sequence 8 was never observed on the guest; the next
snapshot was sequence 9, so that short pause was coalesced away.

This reproduces and attributes the reported roughly three-second delay: a successful local
broadcast completion and an initially subscribed channel did not mean the peer received the live
timing message. The peer followed the slower durable state path, whose roughly five-second cadence
and intermittent API failures produced variable multi-second arrival and occasional coalescing.
This does not yet identify why private-channel broadcast delivery was absent.

Stage 0 remains `IN_PROGRESS`. To complete the required matrix, collect seven more seeks total and
the controlled Realtime interruption/recovery segment (pause, resume, and seek while interrupted;
then repeat after restoration). Preserve the new pair of original logs outside the repository.
