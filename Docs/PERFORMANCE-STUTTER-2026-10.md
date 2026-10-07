# Desktop stutter comparison — 2026-10-07

Continuation of workspace `HANDOFF-perf-stutter.md` and `PERF-OBSERVE-2026-10-06.md`.
Branch: `claude/perf-stutter-1`. Product fixes: `708673199` (Windows poster downscale on workers)
and `be94152d5` (lazy Settings search index). Nothing pushed, merged or published.

## Result

Keep both fixes. The targeted UI-thread work disappeared. The maintainer confirms Settings now
opens like the other tabs; Home is slightly smoother but still very stuttery, and general tab
switches still stutter. Smooth browsing has not been achieved.

## Conditions and data

Both apps use installed 2.0.132's launcher, JDK 17.0.20.1, configuration, libraries and signed-in
data, sequentially on battery, at 60 Hz, window 1935x1215 and scale 1.5. The patched jar differs
in exactly 33 entries, all belonging to `AsyncImage_desktopKt*`, `ScaledBitmapPainter*`,
`DesktopImageDownscaler*` or `SettingsScreenKt*`. Launcher, java.dll and launcher cfg are identical.

Raw data under workspace `.claude-worktrees/perf-observe-data/`:

- Before: `ab-before-20261007-1`, normal shutdown marker, 104 s, 1,523 frames.
- After: `ab-after-20261007-2`, normal shutdown marker, launcher exit code 0, 63 s, 1,607 frames.
- Excluded: `ab-after-20261007-1`, truncated after 21 s, no normal shutdown or exit marker.
  Both app and recorder stopped; no corresponding Windows/JVM crash report was found. Cause
  unconfirmed. Retry used a detached hidden observer, stdout/stderr/exit-code capture, and ended
  normally. A chat interruption must not leave an interactive measurement tied to a live command.

Other JVM CPU: before 0.00 cores; after mean 0.01, p95 0.05, max 0.11. Machine mean CPU:
18.83% / 18.00%. The full regression build started only after both measurements finished.
Regenerate reports with `scripts/perf/observe/analyze.py <run>`. Screenshots contain personal
account content and remain local, outside git.

## Effects

Weighted attribution of tasks >=25 ms to sampled stacks estimates 2,305 ms of original UI-thread
hitch time inside `ScaledBitmapPainter.scale:` and 1,028 ms under `settingsSearchEntries`.
Neither appears in the successful patched run.

Match **`ScaledBitmapPainter.scale:` exactly**. The raw-data `attr.py` substring
`ScaledBitmapPainter.scale` also matches the patched, cheap `scaledBitmap` method and mislabels
it as scaling. Its hardcoded phase times apply only to `real-1`.

First tab visit's worst UI frame (one visit each):

| Tab | Before ms | After ms |
| --- | ---: | ---: |
| Search | 492 | 588 |
| Library | 196 | 131 |
| Downloads | 414 | 196 |
| Social | 533 | 467 |
| Settings | 1,392 | 229 |

Settings agrees with the removed eager index and the maintainer's observation. Other tab numbers
are individual observations, not demonstrated improvements: class loading, content arrival and
interaction timing differ.

Initial Home scroll bounds are first wheel input through first Search click: before
56.462815–71.650581 s; after 23.672890–39.511219 s. Pool `F` tasks starting within the interval;
quantiles use linear interpolation and threshold counts use `>=`.

| Initial Home scroll | Before | After |
| --- | ---: | ---: |
| Frames | 251 | 389 |
| p50 frame ms | 2.90 | 2.43 |
| p95 frame ms | 110.86 | 49.62 |
| Frames >=50 ms | 24 (9.6%) | 19 (4.9%) |
| Frames >=100 ms | 16 (6.4%) | 7 (1.8%) |
| Worst frame ms | 615.9 | 347.2 |

Directional observations only: nonzero wheel events differ (186 / 411) and the after run has
small background JVM activity. Do not claim a controlled percentage speedup. The after run has
one tab loop, so there is **no matched warm-tab comparison**.

## Remaining costs and next steps

- First Search: 588 ms, 700 classes in that frame; samples class loading 39%, composition 27%,
  IO 17%. Repeated composition-heavy frames follow while cards arrive.
- First Social: 467 ms, 270 classes; composition 37%, class loading 25%.
- Home hitches still involve `homeSocialSections`, `FriendActivityRow`, `NuvioPosterCard`,
  `ContinueWatchingCard`, hover previews, badges, layout and text.
- Startup still initializes MDBList / settings sync on the UI thread.
- Hidden Home activity remains unfixed here. Phase 1 `4c68420cb` supplies the missing screen
  activity / lifecycle gate; this comparison does not prove its effect independently.

Keep the handoff's order: settings-sync startup (`f50962f88`), hidden Home (`4c68420cb`), then
background tab-class warming and row/card composition. Measure separately, with an explicit
second tab loop to verify warm switches.

## Verification

Prior targeted desktop settings/core.ui tests: 97, zero failures. Full split suite completed:
rest 1,898 / playback 1,072 / downloads 457 / E2E 49 = **3,476**, zero failures/errors/skips and
zero duplicates, all four parts `rc=0`. Fresh summary: `build/perf-stutter-ab-split-20261007/`;
recorded HEAD `ef54fbdb2`, with product code unchanged since `be94152d5`. Later commits are
measurement documentation and recorder tooling. Mobile code and tests unchanged.
Canonical patch-surface notes use separate mobile branch
`codex/perf-stutter-documentation`, isolated from Social work.

## Isolated startup step — completed 2026-10-07

`codex/perf-stutter-2` adds only `c401e1bfc` (Phase 1 `f50962f88`) to the preceding two product
fixes. AppGate waits for one frame before initializing settings sync on Default; start is locked.
Desktop jar builds; focused sync/auth **108 tests pass**, zero failures/errors/skips, including
the eight-caller observer-start regression. Logs: `build/perf-startup-step-20261007/`.

App `perf-observe-data/apps/after-2-startup` is a copy of `after-1`, replacing 69 old classes with
71 rebuilt classes selected by SourceFile `AppGate.kt` / `ProfileSettingsSync.kt`. All unrelated
jar entries are verified byte-identical; launcher/runtime/configuration remain unchanged.
The copy helper initially mutated shared ZipInfo metadata during verification. This was corrected
by copying entry metadata before writing, and the successful copy passed all byte checks before
launch. The failed copy is archived locally as `after-2-startup-copy-attempt-1`.

Raw paired runs: `startup-before-20261007-1` / `startup-after-20261007-1`, both signed in, battery,
60 Hz, Java 17.0.20.1, normal shutdown and exit code 0. Baseline has 604 s elapsed including a long
wait for the maintainer; after has 87 s. Compare the first **12 s after premain**, not session length.

| Early UI measurement | Before | After |
| --- | ---: | ---: |
| Application/window task | 4,349.8 ms | 2,220.8 ms |
| First profile-picker frame | 2,093.0 ms | 1,794.1 ms |
| UI tasks >=100 ms within first 12 s, summed duration | 6,676.8 ms | 5,225.2 ms |
| Samples under `ProfileSettingsSync.startObserving` in hitches >=25 ms | 258 | 0 |
| Samples under `createMdbListHttpClient` in hitches >=25 ms | 130 | 0 |

Sample-weighted attribution puts 1,303 ms under settings observer startup before, zero after.
MDBList's 657 ms is a subset of that work, not an additional cost. The targeted work demonstrably
left the UI thread. **The maintainer did not notice a visible startup improvement.** Keep the
threading fix; do not call startup smooth or claim a confirmed end-to-end launch speedup.

Important limits: window size differs slightly (1935x1215 / 1920x1200); background JVM mean is
0.013 / 0.012 cores (max 0.176 / 0.121); JVM-start-to-premain delay is about 0.41 / 11.49 s,
outside this probe's timed tasks. Baseline waited much longer before profile selection. No matched
warm-tab claim is possible from these interactions. The early table is one observation per build.

Remaining launch cost: the after run has a 998 ms component task containing avatar-catalog /
`SupabaseProvider.getClient` initialization (479 ms attributed to the latter). Home entry still
has a roughly 1.5 s frame; Search/cards and tab transitions still have major composition costs.
Next independent change is the handoff's hidden-Home activity gate (`4c68420cb`). Human testing
is unavailable for about an hour, so prepare and verify it offline, retaining a separate test app.
