"""Turn a StutterProbe run into a stutter report.

usage: python analyze.py <run dir> [--hitch-ms 25] [--top 12]

Reads timeline.tsv (and counters.csv when present) and writes report.md plus hitches.tsv into the
run directory. Every UI-thread task of hitch size is listed with when it happened, what the user had
just done, what ran (frame / coroutine / AWT event), how many classes it loaded, and what its stack
samples were doing, by category and by the app code on the stack.
"""
import collections
import csv
import os
import re
import sys

args, opts = [], {}
_argv = sys.argv[1:]
while _argv:
    a = _argv.pop(0)
    if a.startswith("--"):
        opts[a] = _argv.pop(0)
    else:
        args.append(a)
run = args[0]
HITCH_MS = float(opts.get("--hitch-ms", 25))
TOP = int(opts.get("--top", 12))

tasks = {}          # seq -> dict
order = []          # seqs in time order
samples = collections.defaultdict(list)  # seq -> [(offUs, [frame ids])]
blind = []          # samples before the wrapped queue (seq -1): (atUs, frames)
frames_dict = {}
inputs, marks, gcs, counters, shots, info = [], [], [], [], [], []

with open(os.path.join(run, "timeline.tsv"), encoding="utf-8", errors="replace") as f:
    for line in f:
        p = line.rstrip("\n").split(" ")
        k = p[0]
        try:
            if k == "T":
                seq = int(p[1])
                t = dict(seq=seq, start=int(p[2]), dur=int(p[3]), kind=p[4], classes=int(p[5]), desc=" ".join(p[6:]))
                tasks[seq] = t
                order.append(seq)
            elif k == "S":
                ids = [int(x) for x in p[3].split(",")] if p[3] else []
                if p[1] == "-1":
                    blind.append((int(p[4][1:]), ids))
                else:
                    samples[int(p[1])].append((int(p[2]), ids))
            elif k == "D":
                frames_dict[int(p[1])] = " ".join(p[2:])
            elif k == "I":
                inputs.append(dict(t=int(p[1]), what=p[2], rest=p[3:]))
            elif k == "M":
                marks.append(int(p[1]))
            elif k == "G":
                gcs.append(dict(t=int(p[1]), name=p[2], n=int(p[3]), ms=int(p[4])))
            elif k == "K":
                counters.append(dict(t=int(p[1]), heap=int(p[2]), classes=int(p[3]), jit=int(p[4]), threads=int(p[5])))
            elif k == "SHOT":
                shots.append(dict(t=int(p[2]), end=int(p[3]), file=p[4]))
            elif k == "Q":
                info.append(" ".join(p[1:]))
        except (IndexError, ValueError):
            pass  # a line cut short by a hard kill

order.sort(key=lambda s: tasks[s]["start"])
if not order:
    sys.exit("no tasks in timeline")
END = max(tasks[s]["start"] + tasks[s]["dur"] for s in order)

# ------------------------------------------------------------------ classification of a stack sample

def fr(i):
    return frames_dict.get(i, "?")

CATS = [
    # (category, predicate on the frame string) checked from the leaf outward; first match wins.
    ("class loading", lambda s: s.startswith(("java.lang.ClassLoader.", "jdk.internal.loader.", "java.lang.Class.forName",
                                               "java.security.SecureClassLoader", "java.net.URLClassLoader", "java.util.zip.", "java.util.jar."))
        or ".defineClass" in s or "ClassLoader.loadClass" in s),
    ("waiting on a lock / blocking call", lambda s: s.startswith(("jdk.internal.misc.Unsafe.park", "java.lang.Object.wait", "java.lang.Thread.sleep",
                                                                 "kotlinx.coroutines.BlockingCoroutine.joinBlocking", "kotlinx.coroutines.BuildersKt.runBlocking"))),
    ("disk / network IO", lambda s: s.startswith(("java.io.", "java.nio.", "sun.nio.", "okio.", "java.util.prefs.", "sun.net.", "java.net."))),
    ("JSON / serialization", lambda s: s.startswith(("kotlinx.serialization.",))),
    ("image decode / scale", lambda s: s.startswith(("coil3.", "javax.imageio.")) or "ScaledBitmapPainter" in s or "DesktopImageDownscaler" in s
        or s.startswith(("org.jetbrains.skia.Image.", "org.jetbrains.skia.Bitmap.", "org.jetbrains.skia.Pixmap.", "org.jetbrains.skia.Codec."))),
    ("text layout", lambda s: s.startswith(("org.jetbrains.skia.paragraph.", "androidx.compose.ui.text.")) or "SkiaParagraph" in s),
    ("blur (haze)", lambda s: s.startswith(("dev.chrisbanes.haze.",))),
    ("skia draw / GPU submit", lambda s: s.startswith(("org.jetbrains.skia.", "org.jetbrains.skiko.redrawer.", "org.jetbrains.skiko.Direct3D",
                                                      "org.jetbrains.skiko.context."))),
    ("compose draw", lambda s: s.startswith("androidx.compose.ui.") and "draw" in s.rsplit(":", 1)[0].lower()),
    ("compose measure / layout", lambda s: s.startswith("androidx.compose.ui.") and any(w in s.rsplit(":", 1)[0].lower() for w in ("measure", "layout", "place"))),
    ("composition", lambda s: s.startswith(("androidx.compose.runtime.",))),
    ("animation", lambda s: s.startswith(("androidx.compose.animation.",))),
]


def category(ids):
    """Leaf-first: the innermost recognisable frame says what the thread was doing."""
    for i in ids:
        s = fr(i)
        for name, pred in CATS:
            try:
                if pred(s):
                    return name
            except Exception:
                pass
    return "other"


def app_frames(ids):
    """com.nuvio.app frames on the stack, leaf first, without line numbers."""
    out = []
    for i in ids:
        s = fr(i)
        if s.startswith("com.nuvio.app.") and "$$Lambda" not in s:
            m = s.rsplit(":", 1)[0].replace("com.nuvio.app.", "")
            if m not in out:
                out.append(m)
    return out


def interesting_leaf(ids):
    """Innermost frame that is not JDK/kotlin plumbing, for 'other'."""
    for i in ids:
        s = fr(i).rsplit(":", 1)[0]
        if not s.startswith(("java.", "jdk.", "sun.", "kotlin.", "kotlinx.coroutines.")):
            return s
    return fr(ids[0]).rsplit(":", 1)[0] if ids else "?"

# ------------------------------------------------------------------ what the user was doing

inputs.sort(key=lambda x: x["t"])
clicks = [i for i in inputs if i["what"] in ("press", "key")]


def context(t):
    """The last click/key before t, and how long before."""
    last = None
    for c in clicks:
        if c["t"] <= t:
            last = c
        else:
            break
    wheel_recent = any(i["what"] == "wheel" and 0 <= t - i["t"] <= 400_000 for i in inputs)
    if last is None:
        return "startup", wheel_recent
    return last, wheel_recent


def shot_after(t):
    for s in shots:
        if s["t"] >= t:
            return s["file"]
    return ""


def click_label(c):
    if c == "startup":
        return "startup"
    if c["what"] == "key":
        return "key %s" % (c["rest"][-1] if c["rest"] else "?")
    return "click (%s,%s)" % (c["rest"][0], c["rest"][1])

# ------------------------------------------------------------------ hitches

hitches = [tasks[s] for s in order if tasks[s]["dur"] >= HITCH_MS * 1000]
gc_by_time = sorted(gcs, key=lambda g: g["t"])


def gc_near(t, dur):
    return [g for g in gc_by_time if t - 60_000 <= g["t"] <= t + dur + 60_000 and g["ms"] > 0]


def shot_overlap(t, dur):
    return any(s["t"] < t + dur and s["end"] > t for s in shots)


def summarize_task(t):
    smp = samples.get(t["seq"], [])
    cats = collections.Counter(category(ids) for _, ids in smp)
    apps = collections.Counter()
    leafs = collections.Counter()
    for _, ids in smp:
        a = app_frames(ids)
        if a:
            apps[a[0]] += 1
        leafs[interesting_leaf(ids)] += 1
    return smp, cats, apps, leafs


def fmt_counter(c, total, n=3):
    if not total:
        return ""
    return ", ".join("%s %d%%" % (k, round(100 * v / total)) for k, v in c.most_common(n))

# ------------------------------------------------------------------ report

L = []
w = L.append
w("# Stutter report: %s" % os.path.basename(os.path.abspath(run)))
w("")
for i in info:
    w("- `%s`" % i[:260])
w("")
dur_s = END / 1e6
all_t = [tasks[s] for s in order]
frames = [t for t in all_t if t["kind"] == "F"]
w("Session %.0f s, %d frames, %d UI-thread tasks >= 1 ms, %d user marks (F8), %d clicks/keys, %d screenshots."
  % (dur_s, len(frames), len(all_t), len(marks), len(clicks), len(shots)))
w("")

# Overview buckets
def bucket_counts(ts):
    c = collections.Counter()
    for t in ts:
        ms = t["dur"] / 1000
        for th in (16.7, 25, 33, 50, 100, 250, 1000):
            if ms >= th:
                c[th] += 1
    return c

bc = bucket_counts(all_t)
fc = bucket_counts(frames)
w("| UI-thread tasks | >=17 ms | >=25 | >=33 | >=50 | >=100 | >=250 | >=1 s |")
w("| --- | --- | --- | --- | --- | --- | --- | --- |")
w("| all tasks | %s |" % " | ".join(str(bc[t]) for t in (16.7, 25, 33, 50, 100, 250, 1000)))
w("| of which frames | %s |" % " | ".join(str(fc[t]) for t in (16.7, 25, 33, 50, 100, 250, 1000)))
w("")

# Where the time over budget went, by category (sample-weighted over every hitch)
cat_total = collections.Counter()
app_total = collections.Counter()
app_incl = collections.Counter()
nsamp = 0
for t in hitches:
    for _, ids in samples.get(t["seq"], []):
        nsamp += 1
        cat_total[category(ids)] += 1
        a = app_frames(ids)
        if a:
            app_total[a[0]] += 1
        for m in set(a):
            app_incl[m] += 1
w("## What the UI thread was doing during hitches (all %d stack samples in tasks >= %g ms)" % (nsamp, HITCH_MS))
w("")
w("| category | share |")
w("| --- | --- |")
for k, v in cat_total.most_common():
    w("| %s | %d%% |" % (k, round(100 * v / max(nsamp, 1))))
w("")
w("Innermost app (`com.nuvio.app`) frame per sample - the app code closest to the work:")
w("")
for k, v in app_total.most_common(TOP):
    w("- %d%% `%s`" % (round(100 * v / max(nsamp, 1)), k))
w("")
w("App frames anywhere on the stack (inclusive) - which screens/components the hitch time sits under:")
w("")
for k, v in app_incl.most_common(TOP * 2):
    w("- %d%% `%s`" % (round(100 * v / max(nsamp, 1)), k))
w("")

# Segments: per click/key
w("## By interaction")
w("")
w("Each row is the span from one click/key to the next. 'worst' is the longest UI-thread task in it.")
w("")
w("| t (s) | input | screen after | span s | frames | tasks>=25ms | >=50 | >=100 | worst ms | worst was | classes loaded | marks |")
w("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |")
bounds = [0] + [c["t"] for c in clicks] + [END + 1]
labels = ["startup"] + clicks
for i in range(len(bounds) - 1):
    a, b = bounds[i], bounds[i + 1]
    ts = [t for t in all_t if a <= t["start"] < b]
    if not ts:
        continue
    worst = max(ts, key=lambda t: t["dur"])
    cls = sum(t["classes"] for t in ts)
    mk = sum(1 for m in marks if a <= m < b)
    w("| %.1f | %s | %s | %.1f | %d | %d | %d | %d | %.0f | %s %s | %d | %s |" % (
        a / 1e6, click_label(labels[i]), shot_after(a + 1) if i else "", (b - a) / 1e6 if b <= END else (END - a) / 1e6,
        sum(1 for t in ts if t["kind"] == "F"),
        sum(1 for t in ts if t["dur"] >= 25000), sum(1 for t in ts if t["dur"] >= 50000), sum(1 for t in ts if t["dur"] >= 100000),
        worst["dur"] / 1000, worst["kind"], worst["desc"].split(".")[-1][:50], cls, mk or ""))
w("")

# User marks
if marks:
    w("## F8 marks (you felt a stutter)")
    w("")
    for m in marks:
        near = [t for t in all_t if m - 1_500_000 <= t["start"] <= m and t["dur"] >= 16700]
        w("- %.2f s: %d tasks >= 17 ms in the 1.5 s before: %s" % (m / 1e6, len(near),
          ", ".join("%.0f ms %s @-%.2fs" % (t["dur"] / 1000, t["kind"], (m - t["start"]) / 1e6) for t in sorted(near, key=lambda t: -t["dur"])[:5]) or "none - the stutter was not on the UI thread"))
    w("")

# Every hitch
w("## Every hitch (UI-thread task >= %g ms)" % HITCH_MS)
w("")
w("| t (s) | ms | kind | ran | classes | since input | samples | categories | app code (innermost) |")
w("| --- | --- | --- | --- | --- | --- | --- | --- | --- |")
rows = []
for t in hitches:
    smp, cats, apps, leafs = summarize_task(t)
    c, wheel = context(t["start"])
    since = "startup" if c == "startup" else "%s +%.2fs" % (click_label(c), (t["start"] - c["t"]) / 1e6)
    if wheel:
        since += " (scrolling)"
    flags = []
    if gc_near(t["start"], t["dur"]):
        flags.append("GC " + "+".join("%dms" % g["ms"] for g in gc_near(t["start"], t["dur"])))
    if shot_overlap(t["start"], t["dur"]):
        flags.append("probe screenshot overlapping")
    desc = t["desc"].replace("C:", "").replace("R:", "")
    desc = re.sub(r"^(androidx\.compose\.|org\.jetbrains\.|com\.nuvio\.app\.)", "", desc)[:70]
    w("| %.2f | %.0f | %s | %s | %d | %s | %d | %s | %s |" % (
        t["start"] / 1e6, t["dur"] / 1000, t["kind"], desc, t["classes"], since, len(smp),
        fmt_counter(cats, len(smp)) + ("; " + "; ".join(flags) if flags else ""),
        fmt_counter(apps, len(smp), 2) or fmt_counter(leafs, len(smp), 2)))
    rows.append((t, smp, cats, apps, leafs, since))
w("")

# GC
if gcs:
    tot = collections.defaultdict(lambda: [0, 0, 0])
    for g in gcs:
        tot[g["name"]][0] += g["n"]
        tot[g["name"]][1] += g["ms"]
        tot[g["name"]][2] = max(tot[g["name"]][2], g["ms"])
    w("## GC")
    w("")
    for k, (n, ms, mx) in tot.items():
        w("- %s: %d collections, %d ms total, worst batch %d ms" % (k, n, ms, mx))
    w("")

# Counters: CPU of the app vs the rest of the machine
cpath = os.path.join(run, "counters.csv")
if os.path.exists(cpath):
    try:
        with open(cpath, newline="", encoding="utf-8", errors="replace") as f:
            rdr = list(csv.reader(f))
        hdr = rdr[0]
        pid = None
        for i in info:
            m = re.search(r" pid=(\d+)", i)
            if m:
                pid = m.group(1)
        idcols = [j for j, h in enumerate(hdr) if h.endswith("\\ID Process")]
        mine = None
        for row in rdr[1:]:
            for j in idcols:
                if j < len(row) and row[j].strip().split(".")[0] == pid:
                    mine = hdr[j].replace("\\ID Process", "\\% Processor Time")
            if mine:
                break
        tot_j = next(j for j, h in enumerate(hdr) if "_Total" in h)
        app_j = hdr.index(mine) if mine in hdr else None
        java_js = [j for j, h in enumerate(hdr) if "Process(java" in h and "Processor Time" in h]
        gpu_js = [j for j, h in enumerate(hdr) if ("pid_%s_" % pid) in h] if pid else []
        vals = collections.defaultdict(list)
        for row in rdr[1:]:
            def num(j):
                try:
                    return float(row[j])
                except (ValueError, IndexError):
                    return None
            v = num(tot_j)
            if v is not None:
                vals["machine %"].append(v)
            if app_j is not None and num(app_j) is not None:
                vals["app (cores)"].append(num(app_j) / 100)
            j = sum(x for x in (num(k) for k in java_js) if x is not None)
            vals["other JVMs: gradle/kotlin daemons (cores)"].append(j / 100)
            g = [x for x in (num(k) for k in gpu_js) if x is not None]
            if g:
                vals["app GPU 3D %"].append(sum(g))
        w("## Machine load during the session (1 s samples)")
        w("")
        w("| series | mean | p95 | max |")
        w("| --- | --- | --- | --- |")
        for k, v in vals.items():
            if not v:
                continue
            s = sorted(v)
            w("| %s | %.2f | %.2f | %.2f |" % (k, sum(s) / len(s), s[int(0.95 * (len(s) - 1))], s[-1]))
        w("")
    except Exception as e:  # counters are optional
        w("(counters.csv unreadable: %s)" % e)

# Deep dive on the worst hitches
w("## The %d worst hitches in detail" % min(TOP, len(rows)))
w("")
for t, smp, cats, apps, leafs, since in sorted(rows, key=lambda r: -r[0]["dur"])[:TOP]:
    w("### %.2f s - %.0f ms %s (%s)" % (t["start"] / 1e6, t["dur"] / 1000, t["kind"], since))
    w("")
    w("`%s` - %d classes loaded, %d samples" % (t["desc"], t["classes"], len(smp)))
    w("")
    if smp:
        w("- categories: %s" % fmt_counter(cats, len(smp), 6))
        w("- innermost app frames: %s" % (fmt_counter(apps, len(smp), 5) or "none"))
        w("- innermost non-JDK frames: %s" % fmt_counter(leafs, len(smp), 5))
        # The most common app call path (outer -> inner), truncated
        paths = collections.Counter(" > ".join(reversed(app_frames(ids)[:6])) for _, ids in smp if app_frames(ids))
        if paths:
            p, n = paths.most_common(1)[0]
            w("- most common app path (%d/%d): `%s`" % (n, len(smp), p))
    w("")

if blind:
    w("## Before the probe took over the event queue (%d blind samples)" % len(blind))
    w("")
    cats = collections.Counter(category(ids) for _, ids in blind)
    w("- categories: %s" % fmt_counter(cats, len(blind), 6))
    w("")

open(os.path.join(run, "report.md"), "w", encoding="utf-8").write("\n".join(L) + "\n")

with open(os.path.join(run, "hitches.tsv"), "w", encoding="utf-8") as f:
    f.write("start_s\tms\tkind\tdesc\tclasses\tsince\tsamples\tcategories\tapp\n")
    for t, smp, cats, apps, leafs, since in rows:
        f.write("%.3f\t%.1f\t%s\t%s\t%d\t%s\t%d\t%s\t%s\n" % (t["start"] / 1e6, t["dur"] / 1000, t["kind"], t["desc"], t["classes"], since,
                                                          len(smp), fmt_counter(cats, len(smp), 6), fmt_counter(apps, len(smp), 5)))
print(os.path.join(run, "report.md"))
