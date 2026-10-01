import datetime, json, os, re, subprocess, sys
from collections import Counter, defaultdict

# usage: wakeups.py <outdir> [phase...]
# Needs a run recorded with PERF_JFR_EXTRA=",jdk.ThreadPark#threshold=0ms". Reports, per phase, how
# often each thread woke from a park, per minute. kotlinx.coroutines' `DefaultExecutor` is the timer
# thread behind every `delay()` off the main dispatcher, so its wakeups are the app's periodic
# background work; `timeout` buckets say which cadence woke it.
out = sys.argv[1]
want = sys.argv[2:]
jfr = os.path.join(r"C:\Program Files\Android\Android Studio\jbr\bin", "jfr.exe")
raw = subprocess.run([jfr, "print", "--json", "--events", "jdk.ThreadPark", "--stack-depth", "1", os.path.join(out, "rec.jfr")],
                     capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
events = json.loads(raw)["recording"]["events"]

marks = []
for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
    m = re.match(r"\s*\d+ (\d{13}) \S+\s+cmd: mark (\S+)", ln)
    if m:
        marks.append((int(m.group(1)), m.group(2)))


def phase_of(epoch_ms):
    ph = "startup"
    for t, n in marks:
        if epoch_ms >= t:
            ph = n
    return ph


def epoch_ms(ts):
    return int(datetime.datetime.fromisoformat(ts.replace("Z", "+00:00")).timestamp() * 1000)


def dur_ms(d):
    # ISO-8601 duration as printed by jfr, e.g. "PT0.500123S"
    m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", d or "")
    return (int(m.group(1) or 0) * 60 + float(m.group(2))) * 1000 if m else 0.0


by_phase = defaultdict(Counter)
timer_buckets = defaultdict(Counter)
for e in events:
    v = e["values"]
    end = epoch_ms(v["startTime"]) + dur_ms(v.get("duration"))
    ph = phase_of(end)
    name = (v.get("eventThread") or {}).get("javaName") or "?"
    name = re.sub(r"-\d+$", "-N", name)
    by_phase[ph][name] += 1
    if "DefaultExecutor" in name:
        t = v.get("timeout")
        if isinstance(t, str):
            t_ms = dur_ms(t) if t.startswith("PT") and "-" not in t else -1  # negative = no timeout
        else:
            t_ms = t / 1_000_000 if isinstance(t, (int, float)) and t > 0 else -1
        bucket = "untimed" if t_ms < 0 else ("<=100ms" if t_ms <= 100 else "~500ms" if 400 <= t_ms <= 520 else "other")
        timer_buckets[ph][bucket] += 1

bounds = marks + [(None, None)]
durations = {}
for (t, n), (t2, _) in zip(marks, bounds[1:]):
    if t2:
        durations[n] = (t2 - t) / 1000.0

print(f"### {os.path.basename(out)}")
for _, n in marks:
    if want and n not in want:
        continue
    d = durations.get(n)
    if not d:
        continue
    c = by_phase.get(n, Counter())
    total = sum(c.values())
    timer = sum(v for k, v in c.items() if "DefaultExecutor" in k)
    print(f"{n:22s} {d:6.1f}s  all parks/min {total / d * 60:8.1f}   DefaultExecutor wakeups/min {timer / d * 60:7.1f}   "
          + " ".join(f"{k}={v}" for k, v in sorted(timer_buckets[n].items())))
    for k, v in c.most_common(6):
        print(f"      {v / d * 60:8.1f}/min  {k}")
