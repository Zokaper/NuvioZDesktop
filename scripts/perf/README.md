# Desktop performance harness

Profiles an **installed, unmodified** Nuvio or Nuvio Z desktop app (the jars and `.cfg` under
`C:\Program Files\<app>\app`) on a full JDK, so that JFR and frame timing are available. Written
for `Docs/PERFORMANCE-AUDIT-2026-10.md` in `nuvio-z`; read that first for what the numbers mean.

Nothing here changes the app. Input is posted **in-process** as AWT events (Win32 synthetic input
does not reach a Compose window from an agent shell), so the scripts can drive the picker and
scroll Home without a human.

## Build the driver

```bash
JB="/c/Program Files/Android/Android Studio/jbr/bin"
"$JB/javac" -d classes scripts/perf/PerfDriver.java
"$JB/jar" cf scripts/perf/perfdriver.jar -C classes .
```

## Run

```bash
# Full protocol: launch, picker, click the profile at (x, y) in window coordinates, 45 s,
# 20 s idle, 6 wheel passes, 30 s idle, close. Output in out/<name>/.
python scripts/perf/full.py "C:/Program Files/Nuvio Z" out/z-1 724 640
python scripts/perf/summary.py out/z-1 out/vanilla-1   # side-by-side table
python scripts/perf/longframes.py out/z-1 "scroll"     # what each >=25 ms frame contained (needs JFR)
```

Environment switches: `PERF_JFR_EXTRA=",jdk.ExecutionSample#period=1ms"` (finer sampling),
`PERF_NOJFR=1` (clean CPU numbers), `PERF_EXTRA_TABS=1` (switch tab, idle, return; TopBar layout
coordinates), `PERF_LONG_IDLE=60` (final idle phase), `APPDATA=<dir>` (run on a **copy** of the data
directory; remove `nuvio_official_session.properties` from the copy first so it cannot rotate a real
token).

Profile tile coordinates come from `picker.png` (window coordinates at 150 % scale on the audit
machine). One app at a time: the official session must never be refreshed by two processes.

## Outputs

- `frames.txt` — per phase: skiko frames, p50/p95/p99/max frame time on the UI thread, counts of
  frames >= 16/25/33/50/100 ms and of gaps between frames.
- `phases.txt` — per phase: every UI-thread task (busy %, long tasks), heartbeat latency, process
  CPU (cores), GC, allocation.
- `driver.log` — `FRAME`/`TASK` lines with wall-clock windows, `STALL` lines with UI-thread stacks.
- `rec.jfr` — JFR (profile settings) unless `PERF_NOJFR`.

`ScaleBench.java` times `ScaledBitmapPainter`'s rescale on a cached poster; `cds_dump.py` builds a
static AppCDS archive from a `-XX:DumpLoadedClassList` run.
