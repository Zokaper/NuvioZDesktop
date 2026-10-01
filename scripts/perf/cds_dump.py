import glob, os, subprocess, sys

# usage: cds_dump.py <appdir> <classlist> <archive>
app, classlist, archive = sys.argv[1], os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3])
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
args = [java] + opts + ["-Xshare:dump", f"-XX:SharedClassListFile={classlist}", f"-XX:SharedArchiveFile={archive}",
                        "-cp", ";".join([os.path.join(here, os.environ.get("PERF_CLASSES", "perfdriver.jar"))] + cp)]
r = subprocess.run(args, cwd=appdir, capture_output=True, text=True)
print(r.stdout[-3000:], r.stderr[-3000:])
print("exit", r.returncode, "archive bytes", os.path.getsize(archive) if os.path.exists(archive) else None)
