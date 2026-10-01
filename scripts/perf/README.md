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

## Performance Phase 1 additions (2026-10-01)

**Registry safety.** `run.py` always starts the app with
`-Djava.util.prefs.PreferencesFactory=PerfDriver$MemoryPrefsFactory`, and `PerfDriver` refuses to
start if `java.util.prefs` is anything else. On Windows the default factory is the registry
(`HKCU\Software\JavaSoft\Prefs`), where supabase-kt's default storage left the official login that
vanilla Nuvio and older Z builds share; a build under test must never read, move or refresh it.

```bash
# A local build: composeApp/build/compose/binaries/main/app/Nuvio Z after :composeApp:createDistributable.
# phase1.py copies the signed-out template data directory afresh for every run and refuses one that
# holds a session file.
PERF_NOJFR=1 python scripts/perf/phase1.py startup "<app>" out/b0-startup-1 "<signed-out template>"
python scripts/perf/startup_summary.py b0=out/b0-startup- b1=out/b1-startup-

# JDK 25+ JFR method tracing (JEP 520) names the work instead of inferring it from timer wakeups:
export PERF_JAVA=".../jetbrains_s_r_o_-25-amd64-windows.2/bin/java.exe" PERF_JFR_SETTINGS=default
export PERF_JFR_EXTRA=",method-trace=com.nuvio.app.features.social.OutgoingJoinRequestStore::tick;androidx.compose.foundation.pager.PagerState::animateScrollToPage"
python scripts/perf/phase1.py tabs "<app>" out/b1-tabs-1 "<signed-out template>"
python scripts/perf/methodtrace.py out/b1-tabs-1          # invocations per phase, time per thread
python scripts/perf/wakeups.py out/b1-tabs-1              # with jdk.ThreadPark#threshold=0ms (noisy)
```

`startup_summary.py` reports the window task, the first-frame task and **every UI task >= 100 ms
before the picker**, summed, so work that only moves from one task to another does not read as a
win. Suspend functions count once per resumption under method tracing (a hero page change is ~2
`animateScrollToPage` invocations). `composeApp/src/desktopTest/.../HiddenHomeActivityHarness.kt`
measures a hidden Home in the real `AppTabHost` (collectors, hero changes, snapshot writes).

## Performance Phase 2 additions (2026-10-01)

```bash
# Home scroll: cold load, Continue Watching (Shift+wheel), 3 vertical passes, Library <-> Home x3,
# one more pass. Interleave before/after builds; other agents' builds load the machine.
PERF_NOJFR=1 python scripts/perf/phase2.py scroll "<app>" out/a1-1 "<signed-out template>"
python scripts/perf/phase2_summary.py scroll before=out/b4- after=out/a1-

# Startup on an app image's own runtime (copy a matching java.exe into <app>/runtime/bin first;
# jpackage strips it): launch -> picker -> click -> Home.
PERF_JAVA="<app>/runtime/bin/java.exe" PERF_NOJFR=1 python scripts/perf/phase2.py startup "<app>" out/cds-1 "<template>"
python scripts/perf/phase2_summary.py startup none=out/none- cds=out/cds-

# CDS variants for one image: base.jsa (JDK classes) and full.jsa (JDK + app, path-bound) from a
# class list recorded with PERF_JVM_EXTRA="-Xshare:off -XX:DumpLoadedClassList=<file>".
python scripts/perf/cds_variants.py "<app>" <classlist> out/arch
PERF_JVM_EXTRA="-Xshare:on -XX:SharedArchiveFile=out/arch/full.jsa" python scripts/perf/phase2.py startup ...
```

- `PerfDriver` now writes `frames-raw.txt` (every frame, per phase, so phases pool into
  percentiles), logs the UI thread's **CPU** time per task (`TASK 1234ms cpu=987ms`) and per frame,
  accepts `hwheel` (Shift + wheel, horizontal scroll), and runs on a jlinked runtime without
  `jdk.management` (process CPU then reads 0). Windows reports thread CPU in 15.6 ms ticks: use the
  CPU columns for tasks of hundreds of ms, not for single frames.
- `run.py` takes extra JVM options from `PERF_JVM_EXTRA` and records the launch time (`launched`),
  so `phase2_summary.py startup` can report launch -> JVM main and launch -> picker.
- `phase2.py` samples the whole process's working set and private bytes after each step
  (`mem.txt`): Skia bitmaps live outside the Java heap. It gives up after 60 s if the app stops
  taking commands instead of waiting forever.
