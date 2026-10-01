import os, re, sys

# usage: frames.py <outdir>...   Long-frame (>25ms interval) stats per phase from skiko's FPS counter.
def analyze(out):
    drv = open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace").read().splitlines()
    marks, tasks, nano_off = [], [], None
    for ln in drv:
        m = re.match(r"\s*(\d+) (\d{13}) \S+\s+cmd: mark (\S+)", ln)
        if m: marks.append((int(m.group(1)), m.group(3)))
        m = re.match(r"\s*(\d+) (\d{13}) \S+\s+TASK (\d+)ms", ln)
        if m: tasks.append((int(m.group(1)), int(m.group(3))))
        m = re.search(r"nanoMs=(\d+) rel=(\d+)", ln)
        if m and nano_off is None: nano_off = int(m.group(1)) - int(m.group(2))
    frames = []
    for ln in open(os.path.join(out, "stdout.log"), encoding="utf-8", errors="replace"):
        m = re.match(r"(\d+) Long frame ([\d.]+) ms", ln.strip())
        if m: frames.append((int(m.group(1)), float(m.group(2))))
    if nano_off is None:
        # anchor: largest UI task after the profile click <-> closest long frame by duration
        sel = next((t for t, n in marks if n == "after-select"), 0)
        cand = [t for t in tasks if t[0] >= sel]
        big = max(cand, key=lambda x: x[1])
        best = min(frames, key=lambda f: abs(f[1] - big[1]))
        nano_off = best[0] - big[0]  # frame end ~= task end (task line logged at task end)
    def phase(rel):
        ph = "startup"
        for t, n in marks:
            if rel >= t: ph = n
        return ph
    stats = {}
    for nano, ms in frames:
        ph = phase(nano - nano_off)
        s = stats.setdefault(ph, [0, 0, 0, 0, 0, 0.0, 0.0])
        if ms < 33: s[0] += 1
        elif ms < 50: s[1] += 1
        elif ms < 100: s[2] += 1
        elif ms < 250: s[3] += 1
        else: s[4] += 1
        if ms < 1000: s[5] += ms - 16.7  # time lost vs 60Hz, ignoring idle gaps >1s
        s[6] = max(s[6], ms)
    print(f"\n### {os.path.basename(out)}  (anchor offset {nano_off})")
    print(f"{'phase':14s} {'25-33':>6s} {'33-50':>6s} {'50-100':>6s} {'100-250':>7s} {'>250':>5s} {'lostMs(<1s)':>11s} {'max':>7s}")
    for _, n in [(0, "startup")] + marks:
        if n in stats:
            s = stats.pop(n)
            print(f"{n:14s} {s[0]:6d} {s[1]:6d} {s[2]:6d} {s[3]:7d} {s[4]:5d} {s[5]:11.0f} {s[6]:7.0f}")

for d in sys.argv[1:]:
    analyze(d)
