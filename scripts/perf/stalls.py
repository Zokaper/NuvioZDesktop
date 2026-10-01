import re, sys, collections

# usage: stalls.py driver.log [phase] [minMs]
path = sys.argv[1]
phase = sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != "-" else None
min_ms = int(sys.argv[3]) if len(sys.argv) > 3 else 50
lines = open(path, encoding="utf-8", errors="replace").read().splitlines()
stalls = []
cur = None
for ln in lines:
    m = re.match(r"\s*(\d+) (?:\d{13} )?(\S+)\s+STALL (\d+)ms", ln)
    if m:
        cur = dict(t=int(m.group(1)), phase=m.group(2), ms=int(m.group(3)), samples=[])
        stalls.append(cur)
        continue
    m = re.match(r"\s+\[(\d+)x\] (.*)", ln)
    if m and cur is not None:
        cur["samples"].append((int(m.group(1)), m.group(2)))
        continue
    if re.match(r"\s*\d+ (?:\d{13} )?\S+\s+\S", ln):
        cur = None

def classify(s):
    leaf, _, rest = s.partition(" <- ")
    frames = [f for f in rest.split(" < ") if f.strip()]
    app = next((f for f in frames if f.startswith("~")), frames[0] if frames else "?")
    if "defineClass" in leaf or "ZipFile" in leaf or "Inflater" in leaf or "<clinit>" in leaf or "loadClass" in leaf or "FileDispatcherImpl.read0" in leaf or "getBooleanAttributes" in leaf:
        cat = "classload/clinit"
    elif "Skia" in leaf or "skiko" in leaf or "skia" in leaf.lower() or "_nDraw" in leaf or "Surface" in leaf:
        cat = "skia-native"
    else:
        cat = "other"
    return leaf, app, cat

tot_leaf = collections.Counter(); tot_app = collections.Counter(); tot_cat = collections.Counter()
for st in stalls:
    if phase and st["phase"] != phase: continue
    if st["ms"] < min_ms: continue
    leafc = collections.Counter(); appc = collections.Counter(); catc = collections.Counter()
    for n, s in st["samples"]:
        leaf, app, cat = classify(s)
        leafc[leaf] += n; appc[app] += n; catc[cat] += n
    tot_leaf.update(leafc); tot_app.update(appc); tot_cat.update(catc)
    print(f"== {st['t']}ms phase={st['phase']} STALL {st['ms']}ms samples={sum(n for n,_ in st['samples'])} cats={dict(catc)}")
    for a, n in appc.most_common(6): print(f"     app {n:4d}  {a[:160]}")
    for a, n in leafc.most_common(4): print(f"     leaf {n:4d}  {a[:120]}")
print("\n==== TOTAL categories", dict(tot_cat))
for a, n in tot_app.most_common(25): print(f"  app {n:5d}  {a[:170]}")
for a, n in tot_leaf.most_common(15): print(f"  leaf {n:5d}  {a[:150]}")
