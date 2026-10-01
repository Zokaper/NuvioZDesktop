import glob, os, subprocess, sys

# usage: run.py <appdir> <outdir> [extra jvm args...]
app, out = sys.argv[1], os.path.abspath(sys.argv[2])
extra = sys.argv[3:]
here = os.path.dirname(os.path.abspath(__file__))
java = r"C:\Program Files\Android\Android Studio\jbr\bin\java.exe"
appdir = os.path.join(app, "app")
cfg = glob.glob(os.path.join(appdir, "*.cfg"))[0]
cp, opts = [], []
for line in open(cfg, encoding="utf-8"):
    line = line.strip()
    if line.startswith("app.classpath="):
        cp.append(line.split("=", 1)[1].replace("$APPDIR", appdir))
    elif line.startswith("java-options="):
        opts.append(line.split("=", 1)[1].replace("$APPDIR", appdir))
os.makedirs(out, exist_ok=True)
NOJFR = bool(os.environ.get("PERF_NOJFR"))
args = [java] + opts + [
    f"-Dperf.dir={out}", "-Dperf.main=com.nuvio.app.MainKt",
    "-Dskiko.fps.enabled=true", "-Dskiko.fps.periodSeconds=2",
    "-Dskiko.fps.longFrames.show=true", "-Dskiko.fps.longFrames.millis=25",
] + ([] if NOJFR else [f"-XX:StartFlightRecording=filename={os.path.join(out, 'rec.jfr')},settings=profile,dumponexit=true" + os.environ.get('PERF_JFR_EXTRA', '')]) + extra + ["-cp", ";".join([os.path.join(here, os.environ.get("PERF_CLASSES", "perfdriver.jar"))] + cp), "PerfDriver"]
with open(os.path.join(out, "stdout.log"), "w") as f:
    p = subprocess.Popen(args, cwd=appdir, stdout=f, stderr=subprocess.STDOUT)
    open(os.path.join(out, "pid"), "w").write(str(p.pid))
    print("pid", p.pid)
    p.wait()
