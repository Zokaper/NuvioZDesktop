# Stutter probe: measure the real app while a person drives it

The earlier harness (`claude/perf-phase-1`, `claude/perf-phase-2`, `scripts/perf/`) drove the app with
synthetic input. It ran signed out on a copy of the data, on a different JDK, while other agents'
Gradle builds ran. This probe measures the opposite way. A person uses the **installed, unmodified**
app as they normally would, signed in, on its own jpackage launcher and JDK 17 runtime. The probe
records why the UI thread was late.

Compose Desktop composes, lays out and draws on the AWT event thread. Every stutter you can see is
that thread held by something. The probe is a Java agent injected with `JAVA_TOOL_OPTIONS`, set for
that one launch only. It:

- wraps the AWT event queue and times every task. Skiko frames, coroutines resumed on `Dispatchers.Main`
  (named by their suspend lambda), and AWT input/paint/window events are each labelled;
- samples the event thread's stack every 3 ms once a task runs past 8 ms. Samples are kept per task;
- counts classes loaded inside each task. A first visit to a screen loads hundreds of classes, so the
  count is a cold-code signal;
- logs clicks, keys and wheel input, plus a 640 px screenshot 1.2 s after the last click so the
  report can say which screen you were on. **F8** drops a "that was a stutter" mark;
- polls GC and, through `typeperf`, logs machine CPU, the app's CPU, the other JVMs' CPU and the
  app's GPU 3D use once a second.

Overhead: the timing wrapper costs well under a microsecond per event. Stack sampling only runs
inside tasks already over 8 ms, and each sample pauses the thread for a fraction of a millisecond.
That inflates long tasks slightly but cannot create one.

## Build

```bash
JDK="/c/Program Files/Eclipse Adoptium/jdk-21.0.12.101-hotspot/bin"   # any JDK >= 17
OUT=/c/perf   # no spaces: JAVA_TOOL_OPTIONS splits on them
mkdir -p $OUT/classes
"$JDK/javac" --release 17 -d $OUT/classes scripts/perf/observe/StutterProbe.java
printf 'Premain-Class: StutterProbe\n' > $OUT/manifest.txt
"$JDK/jar" cfm $OUT/stutterprobe.jar $OUT/manifest.txt -C $OUT/classes .
```

## Run

Close Nuvio Z first. The script refuses to start a second signed-in instance.

```powershell
powershell -ExecutionPolicy Bypass -File scripts\perf\observe\observe.ps1 -Jar C:\perf\stutterprobe.jar -Out C:\perf\run-1
# use the app; press F8 when something stutters; close it with the window's X
python scripts/perf/observe/analyze.py C:/perf/run-1        # -> report.md, hitches.tsv
```

`-App "C:\Program Files\Nuvio Z Debug\Nuvio Z Debug.exe"` measures the Debug build, which carries
debug-tools overhead. `-NoShots` turns screenshots off. `-Smoke -SmokeSeconds 15` checks the probe on a
throwaway, signed-out copy: empty `APPDATA`/`LOCALAPPDATA` and in-memory `java.util.prefs`, so the real
data and the shared registry login are never touched. It can run beside a real instance.

For a human-driven run from a coding-agent session, launch the observer in a separate hidden
PowerShell process and return immediately. It then finishes independently of a chat/command
interruption. The app window remains visible for the human test.

```powershell
$observer = (Resolve-Path scripts\perf\observe\observe.ps1).Path
$run = 'C:\perf\run-2'  # fresh directory; probe/output paths must have no spaces
New-Item -ItemType Directory $run | Out-Null
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
    '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', ('"' + $observer + '"'),
    '-Jar', 'C:\perf\stutterprobe.jar', '-Out', $run
) -RedirectStandardOutput "$run\observer.stdout.log" -RedirectStandardError "$run\observer.stderr.log"
```

A completed recording has `exited.txt`, a `shutdown` entry in `timeline.tsv`, and `exit-code.txt`
with the launcher exit code. Inspect those before comparing. Keep incomplete attempts separately;
a missing exit/shutdown marker alone does not establish an application crash.

## Prepare a copy with one additional fix

`patch_app.py` copies a preceding test app and replaces only classes whose JVM `SourceFile`
attribute names the supplied Kotlin files. It includes generated companions, removes obsolete
generated classes, rejects classes newer than Java 17, and verifies every unrelated jar entry.
A newly added source may be absent from the base; every supplied source must exist in the rebuilt jar.
The output must be a fresh directory outside the base app. Runtime, configuration, libraries and
resources are kept from the base. Do not replace the whole locally built jar: its generated
configuration and native binaries can differ from the installed release.

```powershell
python scripts\perf\observe\patch_app.py C:\perf\apps\previous composeApp\build\libs\composeApp-desktop.jar C:\perf\apps\startup AppGate.kt ProfileSettingsSync.kt
```

Inspect the product diff first: only supply files belonging to the isolated fix being measured.

## Reading the report

- **By interaction**: one row per click or key. It shows the screen you landed on (screenshot file),
  frames, hitch counts, the worst task and the classes loaded. Compare the first visit to a tab with
  later visits. When only the first is bad, the cost is cold code (class loading, interpreter, JIT).
  When every visit is bad, it is the screen's real composition cost.
- **Every hitch**: each UI-thread task of 25 ms or more (`--hitch-ms`). It lists the task kind, what
  ran, the classes loaded, the time since your last input, and what the stack samples were doing.
- **Categories** classify each sample by its innermost recognisable frame: class loading,
  blocking/lock wait, IO, JSON, image decode/scale, text layout, haze blur, Skia draw/GPU submit,
  Compose draw, measure/layout, composition, animation. "Innermost app frame" is the
  `com.nuvio.app` code closest to the work.
- **Machine load**: when other JVMs (Gradle and Kotlin daemons) used real CPU during the session,
  say so next to any number you quote.

For task intervals and inclusive stack attribution, use `attribute.py` with explicit bounds:

```powershell
python scripts\perf\observe\attribute.py C:\perf\run-1 --start-seconds 15 --end-seconds 45 --frame ScaledBitmapPainter.scale: --frame settingsSearchEntries
```

Bounds are seconds after premain, selecting tasks by start time. Values include frame quantiles,
hitch/sample counts and sample-weighted full task duration for each literal stack fragment.
Fragments are independent and their attribution may overlap; do not add overlapping totals.
Match `ScaledBitmapPainter.scale:` with the colon: omitting it also matches the cheap
`scaledBitmap` cache lookup. Workspace scratch `attr.py` additionally has boundaries tied to
the original `real-1` run; do not reuse those for another recording. Check shutdown/exit markers
and machine load separately before drawing a comparison from these estimates.
