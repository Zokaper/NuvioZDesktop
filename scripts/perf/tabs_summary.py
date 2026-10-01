import os, re, subprocess, sys

# usage: tabs_summary.py <outdir>...   one row per run and phase of phase1.py's tabs protocol:
# frames/min, UI-thread busy %, process CPU (cores), and, for a method-traced run, the traced calls
# per minute (hero = PagerState::animateScrollToPage, tick = OutgoingJoinRequestStore::tick).
PHASES = ("home-visible", "home-hidden", "home-returned")
here = os.path.dirname(os.path.abspath(__file__))


def table(path, key_cols):
    rows = {}
    for ln in open(path):
        p = ln.split()
        if p and p[0] in PHASES:
            rows[p[0]] = {k: p[i] for k, i in key_cols.items()}
    return rows


print(f"{'run':16s} {'phase':14s} {'frames/min':>10s} {'UI busy%':>8s} {'cpu cores':>9s} {'hero/min':>8s} {'tick/min':>8s}")
for out in sys.argv[1:]:
    frames = table(os.path.join(out, "frames.txt"), {"frames": 1})
    phases = table(os.path.join(out, "phases.txt"), {"dur": 1, "busy": 3, "cpu": 23})
    traced = {}
    if os.path.exists(os.path.join(out, "rec.jfr")):
        txt = subprocess.run([sys.executable, os.path.join(here, "methodtrace.py"), out], capture_output=True, text=True).stdout
        for ln in txt.splitlines():
            m = re.match(r"(\S+)\s+[\d.]+s\s+(.*)", ln)
            if m and m.group(1) in PHASES:
                hero = re.search(r"animateScrollToPage=\d+ \(([\d.]+)/min\)", m.group(2))
                tick = re.search(r"tick=\d+ \(([\d.]+)/min\)", m.group(2))
                traced[m.group(1)] = (hero.group(1) if hero else "0", tick.group(1) if tick else "0")
    for ph in PHASES:
        if ph not in frames:
            continue
        dur = float(phases[ph]["dur"])
        fpm = int(frames[ph]["frames"]) / dur * 60
        hero, tick = traced.get(ph, ("-", "-"))
        print(f"{os.path.basename(out):16s} {ph:14s} {fpm:10.0f} {phases[ph]['busy']:>8s} {phases[ph]['cpu']:>9s} {hero:>8s} {tick:>8s}")
