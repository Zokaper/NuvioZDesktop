import datetime, json, os, re, subprocess, sys
from collections import Counter, defaultdict

# usage: methodtrace.py <outdir> [phase...]
# For a run recorded on a JDK 25+ (PERF_JAVA) with
#   PERF_JFR_EXTRA=",method-trace=<Class>::<method>;<Class>::<method>..."
# prints how often each traced method ran in each phase, per minute. Unlike timer wakeups this names
# the work: e.g. OutgoingJoinRequestStore::tick, or PagerState::animateScrollToPage (the hero paging).
out = sys.argv[1]
want = sys.argv[2:]
jfr = os.environ.get("PERF_JFR") or r"C:\Users\Rayoa\.gradle\jdks\jetbrains_s_r_o_-25-amd64-windows.2\bin\jfr.exe"
raw = subprocess.run([jfr, "print", "--json", "--events", "jdk.MethodTrace", "--stack-depth", "1", os.path.join(out, "rec.jfr")],
                     capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
events = json.loads(raw)["recording"]["events"]

marks = []
for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
    m = re.match(r"\s*\d+ (\d{13}) \S+\s+cmd: mark (\S+)", ln)
    if m:
        marks.append((int(m.group(1)), m.group(2)))
durations = {n: (t2 - t) / 1000.0 for (t, n), (t2, _) in zip(marks, marks[1:])}


def phase_of(epoch_ms):
    ph = "startup"
    for t, n in marks:
        if epoch_ms >= t:
            ph = n
    return ph


def dur_ms(d):
    m = re.match(r"PT(?:(\d+)M)?([\d.]+)S", d or "")
    return (int(m.group(1) or 0) * 60 + float(m.group(2))) * 1000 if m else 0.0


counts = defaultdict(Counter)
spent = defaultdict(lambda: defaultdict(float))  # phase -> (method, thread kind) -> ms
for e in events:
    v = e["values"]
    method = v.get("method") or {}
    name = (method.get("type") or {}).get("name", "?").split(".")[-1] + "::" + method.get("name", "?")
    ts = int(datetime.datetime.fromisoformat(v["startTime"].replace("Z", "+00:00")).timestamp() * 1000)
    counts[phase_of(ts)][name] += 1
    thread = (v.get("eventThread") or {}).get("javaName") or "?"
    kind = "UI" if thread.startswith("AWT-EventQueue") else "background"
    spent[phase_of(ts)][(name, kind)] += dur_ms(v.get("duration"))

print(f"### {os.path.basename(out)}")
for n in ["startup"] + [m for _, m in marks]:
    if n == "startup" and (not want or "startup" in want):
        row = "  ".join(f"{k}={v}" for k, v in sorted(counts[n].items()))
        print(f"{'startup':22s}          {row or '-'}")
    if (want and n not in want) or n not in durations:
        continue
    d = durations[n]
    row = "  ".join(f"{k}={v} ({v / d * 60:.1f}/min)" for k, v in sorted(counts[n].items()))
    print(f"{n:22s} {d:6.1f}s  {row or '-'}")
    for (meth, kind), ms in sorted(spent[n].items()):
        print(f"{'':22s}   {meth} on {kind}: {ms:.0f} ms")
for (meth, kind), ms in sorted(spent["startup"].items()):
    if not want or "startup" in want:
        print(f"{'startup':22s}   {meth} on {kind}: {ms:.0f} ms")
