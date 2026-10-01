import collections, datetime, os, re, subprocess, sys

# usage: longframes.py <outdir> [phase-regex]
out = sys.argv[1]
phase_re = re.compile(sys.argv[2]) if len(sys.argv) > 2 else re.compile(".")
JFR = r"C:\Program Files\Android\Android Studio\jbr\bin\jfr.exe"
frames = []
for ln in open(os.path.join(out, "driver.log"), encoding="utf-8", errors="replace"):
    m = re.match(r"\s*\d+ \d{13} (\S+)\s+FRAME (\d+)ms startEpoch=(\d+) endEpoch=(\d+)", ln)
    if m and phase_re.search(m.group(1)):
        frames.append((m.group(1), int(m.group(2)), int(m.group(3)), int(m.group(4))))
txt = subprocess.run([JFR, "print", "--stack-depth", "80", "--events", "jdk.ExecutionSample,jdk.NativeMethodSample",
                      os.path.join(out, "rec.jfr")], capture_output=True, text=True, encoding="utf-8", errors="replace").stdout
samples = []
for block in txt.split("jdk.")[1:]:
    th = re.search(r'sampledThread = "([^"]*)"', block)
    if not th or "AWT-EventQueue" not in th.group(1): continue
    tm = re.search(r"startTime = (\d\d):(\d\d):(\d\d)\.(\d+) \((\d{4})-(\d\d)-(\d\d)\)", block)
    h, mi, s, frac, y, mo, d = tm.groups()
    epoch = datetime.datetime(int(y), int(mo), int(d), int(h), int(mi), int(s), int(frac[:6].ljust(6, "0"))).timestamp() * 1000
    fr = re.findall(r"^\s{4,}(\S.*?)(?:\s+line:\s*(\d+).*)?$", block.split("stackTrace = [", 1)[-1], re.M)
    fr = [(f.strip(), l) for f, l in fr if f.strip() and not f.strip().startswith(("]", "..."))]
    samples.append((epoch, fr))
samples.sort(key=lambda x: x[0])
print(f"EDT samples total={len(samples)} long frames={len(frames)}")

def cat(fr):
    names = [f for f, _ in fr]
    j = " | ".join(names)
    if "ScaledBitmapPainter" in j: return "image prescale on draw (ScaledBitmapPainter)"
    if "dev.chrisbanes.haze" in j: return "haze blur"
    if any(("defineClass" in n or "loadClass" in n or "<clinit>" in n or "MethodHandleNatives" in n or "LambdaForm" in n or "InnerClassLambdaMetafactory" in n) for n in names[:15]): return "class load / link / indy bootstrap"
    if any(re.search(r"(_nMakeFromEncoded|decode|Codec)", n) for n in names[:10]): return "image decode on UI thread"
    if any("paragraph" in n.lower() for n in names[:20]): return "text layout (skia paragraph)"
    if re.search(r"(_nDraw|_nFlush|_nFinishRecording|_nBeginRecording|PictureRecorder|_nClip|_nSave|_nRestore|_nConcat|Canvas\.)", " ".join(names[:4])): return "skia draw calls"
    if "Recomposer" in j and ("ComposerImpl" in j or "recompose" in j.lower()): return "recomposition"
    if re.search(r"(measure|Measure|placeAt|layoutChildren|remeasure)", " ".join(names[:30])): return "measure/layout"
    if re.search(r"(NodeCoordinator.draw|LayoutNode.draw|drawContent|GraphicsLayer)", j): return "draw (record)"
    if "pointer" in j.lower() or "PointerInput" in j: return "pointer input"
    return "other"

tot = collections.Counter(); app_tot = collections.Counter(); cat_ms = collections.Counter()
i = 0
for ph, ms, st, en in frames:
    win = [fr for t, fr in samples if st - 1 <= t <= en + 1]
    c = collections.Counter(cat(fr) for fr in win)
    apps = collections.Counter()
    for fr in win:
        a = next((f"{f}:{l}" for f, l in fr if f.startswith("com.nuvio")), None)
        if a: apps[re.sub(r"\(.*?\)", "", a)] += 1
    tot.update(c); app_tot.update(apps)
    n = max(1, len(win))
    for k, v in c.items(): cat_ms[k] += ms * v / n
    if ms >= 33:
        print(f"\n[{ph}] FRAME {ms}ms samples={len(win)}  " + ", ".join(f"{k}={v}" for k, v in c.most_common()))
        for a, v in apps.most_common(4): print(f"      {v:3d} {a[:150]}")
print("\n=== category share of long-frame time (ms, estimated)")
for k, v in cat_ms.most_common(): print(f"  {v:8.0f}  {k}")
print("\n=== top app frames inside long frames")
for a, v in app_tot.most_common(25): print(f"  {v:5d}  {a[:160]}")
