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

Prior targeted desktop settings/core.ui tests: 97, zero failures. Full split suite is running in
`build/perf-stutter-ab-split-20261007/`; record fresh summary when complete. Mobile code and tests
unchanged. Canonical patch-surface notes use separate mobile branch
`codex/perf-stutter-documentation`, isolated from Social work.
