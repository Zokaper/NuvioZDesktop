import glob, os, re, statistics, sys

# Performance Phase 2 summaries.
#
# usage: phase2_summary.py scroll  <label>=<outdir-glob-prefix> ...
#        phase2_summary.py startup <label>=<outdir-glob-prefix> ...
#
# scroll: frames pooled per group of phases from frames-raw.txt (every frame, not only long ones):
#   load = after-select (cold Home load), cw = Continue Watching Shift+wheel, cold = first vertical
#   pass, warm = passes 2-3, tabs = Library/Home switches, back = the pass after returning to Home.
#   Columns: frames, p50/p95/p99/max ms, frames >= 16.7 / 33 / 50 / 100 ms. Then mem.txt (MB).
# startup: per run, from driver.log and run.py's launch timestamp:
#   jvm      launch -> the driver's first line (JVM boot + class loading up to main)
#   window   the largest UI task before the picker (window + first composition: the first stall)
#   picker   launch -> the end of the last UI task >= 100 ms before the picker (picker usable)
#   blocked  every UI task >= 100 ms before the picker, summed
#   shell    the largest UI task after the profile click (first Home composition freeze)
#   home     click -> the end of the last UI task >= 100 ms within 15 s of the click
#   *_cpu    the same tasks' UI-thread CPU time (PerfDriver logs it): what the work cost, without the
#            time spent waiting for a core when other processes load the machine
GROUPS = [
    ("load", lambda p: p == "after-select"),
    ("cw", lambda p: p.startswith("cw-")),
    ("cold", lambda p: p.startswith("scroll1-")),
    ("warm", lambda p: p.startswith("scroll2-") or p.startswith("scroll3-")),
    ("tabs", lambda p: p.startswith("tab-")),
    ("back", lambda p: p.startswith("scroll4-")),
]


def frames(out, cpu=False):
    # frames-raw.txt: "<phase> v v v" (wall, 0.1 ms) then, from newer drivers, "<phase>#cpu v v v".
    by = {}
    for ln in open(os.path.join(out, "frames-raw.txt")):
        parts = ln.split()
        if parts and parts[0].endswith("#cpu") == cpu:
            by[parts[0].removesuffix("#cpu")] = [int(v) / 10.0 for v in parts[1:]]
    return by


def pct(sorted_vals, q):
    return sorted_vals[min(len(sorted_vals) - 1, int(len(sorted_vals) * q / 100.0))] if sorted_vals else 0.0


def scroll(label, runs):
    print(f"\n## {label}  ({len(runs)} runs: {', '.join(os.path.basename(r) for r in runs)})")
    for title, cpu in (("frame wall time (ms)", False), ("frame UI-thread CPU (ms)", True)):
        data = [frames(r, cpu) for r in runs]
        if not any(data):
            continue
        print(title)
        table(data)
    memory(runs)


def table(data):
    print(f"{'group':6s} {'frames':>7s} {'p50':>6s} {'p95':>6s} {'p99':>6s} {'max':>6s} {'>=16.7':>7s} {'>=33':>5s} {'>=50':>5s} {'>=100':>6s}   per run >=33")
    for name, match in GROUPS:
        pooled, per_run = [], []
        for by in data:
            vals = [v for p, vs in by.items() if match(p) for v in vs]
            pooled += vals
            per_run.append(sum(v >= 33 for v in vals))
        pooled.sort()
        if not pooled:
            continue
        print(f"{name:6s} {len(pooled):7d} {pct(pooled, 50):6.1f} {pct(pooled, 95):6.1f} {pct(pooled, 99):6.1f} {pooled[-1]:6.0f} "
              f"{sum(v >= 16.7 for v in pooled):7d} {sum(v >= 33 for v in pooled):5d} {sum(v >= 50 for v in pooled):5d} "
              f"{sum(v >= 100 for v in pooled):6d}   {per_run}")


def memory(runs):
    labels, rows = None, []
    for r in runs:
        path = os.path.join(r, "mem.txt")
        if not os.path.exists(path):
            continue
        lines = [ln.split() for ln in open(path) if len(ln.split()) == 3]
        labels = [l[0] for l in lines]
        rows.append([(int(l[1]) / 1048576, int(l[2]) / 1048576) for l in lines])
    if rows:
        print("memory MB (working set / private), median of runs:")
        for i, name in enumerate(labels):
            ws = statistics.median(row[i][0] for row in rows if i < len(row))
            pv = statistics.median(row[i][1] for row in rows if i < len(row))
            print(f"   {name:14s} {ws:7.0f} / {pv:7.0f}")


def startup_run(out):
    launched = int(open(os.path.join(out, "launched")).read())
    first_epoch = None
    pre, post = [], []
    click = None
    phase = "startup"
    for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
        m = re.match(r"\s*\d+ (\d{13}) (\S+)\s+(.*)", ln)
        if not m:
            continue
        epoch, phase, rest = int(m.group(1)), m.group(2), m.group(3)
        if first_epoch is None:
            first_epoch = epoch
        if rest.startswith("cmd: mark after-select"):
            click = epoch
        t = re.match(r"TASK (\d+)ms(?: cpu=(\d+)ms)?", rest)
        if t:
            ms, cpu = int(t.group(1)), int(t.group(2) or -1)
            (post if click else pre).append((epoch, ms, cpu))
    pre_big = [x for x in pre if x[1] >= 100]
    post_big = [x for x in post if x[1] >= 100 and x[0] - click <= 15000]
    window = max(pre, key=lambda x: x[1], default=(0, 0, 0))
    shell = max(post, key=lambda x: x[1], default=(0, 0, 0))
    return dict(
        jvm=first_epoch - launched,
        window=window[1],
        window_cpu=window[2],
        picker=(max(x[0] for x in pre_big) - launched) if pre_big else 0,
        blocked=sum(x[1] for x in pre_big),
        blocked_cpu=sum(x[2] for x in pre_big),
        shell=shell[1],
        shell_cpu=shell[2],
        home=(max(x[0] for x in post_big) - click) if post_big else 0,
    )


def startup(label, runs):
    rows = [startup_run(r) for r in runs]
    print(f"\n## {label}  ({len(rows)} runs)")
    for key in ("jvm", "window", "window_cpu", "picker", "blocked", "blocked_cpu", "shell", "shell_cpu", "home"):
        vals = [r[key] for r in rows]
        print(f"{key:11s} median={statistics.median(vals):6.0f}  min={min(vals):5d}  max={max(vals):5d}  {vals}")


mode = sys.argv[1]
for arg in sys.argv[2:]:
    label, prefix = arg.split("=", 1)
    runs = sorted(d for d in glob.glob(prefix + "*") if os.path.isdir(d) and not d.endswith("-appdata"))
    (scroll if mode == "scroll" else startup)(label, runs)
