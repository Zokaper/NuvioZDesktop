import os, sys

# usage: summary.py <outdir>...
def load(out):
    rows = {}
    for ln in open(os.path.join(out, "frames.txt")):
        p = ln.split()
        if not p or p[0] == "phase": continue
        rows[p[0]] = dict(frames=int(p[1]), p95=float(p[4]), mx=float(p[6]), f25=int(p[8]), f33=int(p[9]), f50=int(p[10]), f100=int(p[11]),
                          g33=int(p[13]), g50=int(p[14]), g100=int(p[15]))
    return rows

print(f"{'run':16s} | {'sel max':>7s} | cold pass1 (frames >=33 >=50 >=100 max) | warm passes2-3 (frames >=33 >=50 >=100 max gaps>=50) | home-idle >=33")
for out in sys.argv[1:]:
    r = load(out)
    sel = r.get("after-select", {}).get("mx", 0)
    cold = [r[k] for k in ("scroll1-down", "scroll1-up") if k in r]
    warm = [r[k] for k in ("scroll2-down", "scroll2-up", "scroll3-down", "scroll3-up") if k in r]
    def agg(lst):
        return (sum(x["frames"] for x in lst), sum(x["f33"] for x in lst), sum(x["f50"] for x in lst), sum(x["f100"] for x in lst),
                max([x["mx"] for x in lst] or [0]), sum(x["g50"] for x in lst))
    c = agg(cold); w = agg(warm)
    hi = r.get("home-idle", {}).get("f33", 0)
    print(f"{os.path.basename(out):16s} | {sel:7.0f} | {c[0]:5d} {c[1]:4d} {c[2]:4d} {c[3]:4d} {c[4]:6.0f}            | {w[0]:5d} {w[1]:4d} {w[2]:4d} {w[3]:4d} {w[4]:6.0f} {w[5]:6d}            | {hi}")
