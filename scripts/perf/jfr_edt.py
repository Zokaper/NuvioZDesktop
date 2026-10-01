import collections, datetime, os, re, subprocess, sys

# usage: jfr_edt.py <outdir> [thread-regex] [jfrfile]
out = sys.argv[1]
thread_re = re.compile(sys.argv[2] if len(sys.argv) > 2 and sys.argv[2] != "-" else r"AWT-EventQueue")
jfr_file = sys.argv[3] if len(sys.argv) > 3 else None
if not jfr_file:
    for cand in ("rec.jfr", "dump1.jfr"):
        p = os.path.join(out, cand)
        if os.path.exists(p) and os.path.getsize(p) > 0:
            jfr_file = p
            break
JFR = r"C:\Program Files\Android\Android Studio\jbr\bin\jfr.exe"

# phases from driver log: "<rel> <epoch> <phase> cmd: mark X"
marks = []
for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
    m = re.match(r"\s*\d+ (\d{13}) \S+\s+cmd: mark (\S+)", ln)
    if m:
        marks.append((int(m.group(1)), m.group(2)))
def phase_of(epoch_ms):
    ph = "startup"
    for t, name in marks:
        if epoch_ms >= t: ph = name
        else: break
    return ph

txt = subprocess.run([JFR, "print", "--stack-depth", "60", "--events", "jdk.ExecutionSample,jdk.NativeMethodSample", jfr_file],
                     capture_output=True, text=True, encoding="utf-8", errors="replace").stdout

CATS = [
    ("image-prescale(ScaledBitmapPainter)", lambda fs: any("ScaledBitmapPainter" in f for f in fs)),
    ("haze-blur", lambda fs: any("dev.chrisbanes.haze" in f for f in fs)),
    ("classload/link", lambda fs: any(("defineClass" in f or "ClassLoader.loadClass" in f or "MethodHandleNatives.resolve" in f or "Unsafe.ensureClassInitialized" in f or ".<clinit>" in f) for f in fs[:12])),
    ("skia-present/flush", lambda fs: any(re.search(r"(Redrawer|Direct3D|DirectX|SwingRedrawer|_nFlush|flushAndSubmit|swapBuffers|_nFinishFrame|present)", f) for f in fs[:8])),
    ("text-layout", lambda fs: any(("paragraph" in f.lower() or "TextLayout" in f or "MultiParagraph" in f) for f in fs[:25])),
    ("compose-draw", lambda fs: any(re.search(r"(NodeCoordinator.draw|LayoutNode.draw|DrawModifierNode|GraphicsLayer.*(draw|record)|RenderNodeLayer|drawContent|\.draw\()", f) for f in fs)),
    ("compose-layout", lambda fs: any(re.search(r"(remeasure|measure-|\.measure\(|MeasureAndLayoutDelegate|LazyListMeasure|placeAt|layoutChildren|relayout)", f) for f in fs)),
    ("compose-composition", lambda fs: any(re.search(r"(Recomposer|ComposerImpl|composeContent|recompose)", f) for f in fs)),
    ("coroutines/effects", lambda fs: any("kotlinx.coroutines" in f for f in fs)),
]

def category(frames):
    for name, pred in CATS:
        if pred(frames): return name
    return "other"

samples = []  # (phase, kind, frames)
for block in txt.split("jdk.")[1:]:
    kind = "native" if block.startswith("NativeMethodSample") else "java"
    tm = re.search(r"startTime = (\d\d):(\d\d):(\d\d)\.(\d+) \((\d{4})-(\d\d)-(\d\d)\)", block)
    th = re.search(r'sampledThread = "([^"]*)"', block)
    if not tm or not th or not thread_re.search(th.group(1)): continue
    h, mi, s, frac, y, mo, d = tm.groups()
    dt = datetime.datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), int(frac[:6].ljust(6, "0")))
    epoch = int(dt.timestamp() * 1000)
    frames = re.findall(r"^\s{4,}(\S.*?)(?:\s+line:.*)?$", block.split("stackTrace = [", 1)[-1], re.M)
    frames = [f.strip() for f in frames if f.strip() and not f.strip().startswith("]") and not f.strip().startswith("...")]
    samples.append((phase_of(epoch), kind, frames))

by_phase = collections.OrderedDict()
for ph, kind, frames in samples:
    by_phase.setdefault(ph, []).append((kind, frames))

order = ["startup", "picker-idle"] + [m for _, m in marks if m not in ("picker-idle",)]
seen = set()
for ph in order:
    if ph in seen or ph not in by_phase: continue
    seen.add(ph)
    lst = by_phase[ph]
    cats = collections.Counter(category(f) for _, f in lst)
    kinds = collections.Counter(k for k, _ in lst)
    app = collections.Counter()
    leaf = collections.Counter()
    for _, f in lst:
        a = next((x for x in f if x.startswith("com.nuvio")), None)
        if a: app[re.sub(r"\(.*", "", a)] += 1
        if f: leaf[re.sub(r"\(.*", "", f[0])] += 1
    print(f"\n=== {ph}: EDT samples={len(lst)} {dict(kinds)}")
    for c, n in cats.most_common(): print(f"    {n:5d} {100*n/len(lst):5.1f}%  {c}")
    print("  top app frames:")
    for a, n in app.most_common(10): print(f"    {n:5d}  {a[:150]}")
    print("  top leaf:")
    for a, n in leaf.most_common(8): print(f"    {n:5d}  {a[:150]}")
