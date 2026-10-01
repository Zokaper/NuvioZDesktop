import os, re, statistics, sys

# usage: startup_summary.py <label>=<outdir-glob-prefix> ...   e.g. b0=out/b0-startup- b1=out/b1-startup-
# Per run, from driver.log's startup phase (launch -> picker):
#   window   the largest UI task running Compose's application continuation (window + first composition)
#   frame    the largest first-frame task (skiko FrameDispatcher)
#   blocked  every UI task >= 100 ms before the picker, summed - so work that only moves from one task
#            to another does not read as a win
import glob


def run(out):
    window = frame = blocked = 0
    for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
        if " picker-idle " in ln:
            break
        m = re.search(r" startup +TASK (\d+)ms (.*)", ln)
        if not m:
            continue
        ms, what = int(m.group(1)), m.group(2)
        blocked += ms
        if "ui.window.Applicati" in what:
            window = max(window, ms)
        elif "FrameDispatcher" in what:
            frame = max(frame, ms)
    return window, frame, blocked


for arg in sys.argv[1:]:
    label, prefix = arg.split("=", 1)
    rows = [run(d) for d in sorted(glob.glob(prefix + "*")) if os.path.isdir(d) and not d.endswith("-appdata")]
    if not rows:
        continue
    for name, i in (("window", 0), ("frame", 1), ("blocked", 2)):
        vals = [r[i] for r in rows]
        print(f"{label:10s} {name:8s} n={len(vals)} median={statistics.median(vals):6.0f} mean={statistics.mean(vals):6.0f} "
              f"min={min(vals):5d} max={max(vals):5d}  {vals}")
