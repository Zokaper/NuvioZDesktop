import glob, os, subprocess, sys

# usage: cds_variants.py <appdir> <classlist> <outdir>
#
# Builds the CDS archives Performance Phase 2 compares, with the app image's own runtime (put a
# matching java.exe in <appdir>/runtime/bin first; jpackage strips it) and the exact classpath run.py
# launches with, so the archives validate under the harness:
#   base.jsa   JDK classes only - no app classpath, so it is valid wherever the app is installed
#   full.jsa   JDK + app classes in one static archive - valid only at this exact path (the ceiling)
# Run with PERF_JVM_EXTRA="-XX:SharedArchiveFile=<archive>" (or -Xshare:off for the baseline).
app, classlist, out = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2]), os.path.abspath(sys.argv[3])
here = os.path.dirname(os.path.abspath(__file__))
java = os.path.join(app, "runtime", "bin", "java.exe")
appdir = os.path.join(app, "app")
cfg = glob.glob(os.path.join(appdir, "*.cfg"))[0]
cp = [os.path.join(here, os.environ.get("PERF_CLASSES", "perfdriver.jar"))]
opts = []
for line in open(cfg, encoding="utf-8"):
    line = line.strip()
    if line.startswith("app.classpath="):
        cp.append(line.split("=", 1)[1].replace("$APPDIR", appdir))
    elif line.startswith("java-options="):
        opts.append(line.split("=", 1)[1].replace("$APPDIR", appdir))
os.makedirs(out, exist_ok=True)

JDK_PREFIXES = ("java/", "javax/", "sun/", "jdk/", "com/sun/")


def is_jdk(line):
    if line.startswith("@lambda-form-invoker"):
        return True
    if line.startswith("@lambda-proxy"):
        return line.split()[1].startswith(JDK_PREFIXES)
    return line.startswith(JDK_PREFIXES)


# JDK 17 rejects the whole list on a line over 4096 characters (a lambda capturing many values).
lines = [l for l in open(classlist, encoding="utf-8") if l.strip() and not l.startswith("#") and len(l) < 4000]
open(os.path.join(out, "all.lst"), "w", encoding="utf-8").writelines(lines)
jdk = [l for l in lines if is_jdk(l)]
open(os.path.join(out, "jdk.lst"), "w", encoding="utf-8").writelines(jdk)
print(f"classes: {len(lines)} listed, {len(jdk)} JDK")


def dump(name, lst, with_cp):
    args = [java] + opts + ["-Xshare:dump", f"-XX:SharedClassListFile={lst}", f"-XX:SharedArchiveFile={os.path.join(out, name)}"]
    if with_cp:
        args += ["-cp", ";".join(cp)]
    r = subprocess.run(args, cwd=appdir, capture_output=True, text=True)
    warnings = sum("Preload Warning" in l or "[warning]" in l for l in (r.stdout + r.stderr).splitlines())
    size = os.path.getsize(os.path.join(out, name)) if os.path.exists(os.path.join(out, name)) else 0
    print(f"{name}: exit {r.returncode}, {size / 1048576:.1f} MB, {warnings} warning lines")
    open(os.path.join(out, name + ".log"), "w", encoding="utf-8").write(r.stdout + r.stderr)


dump("base.jsa", os.path.join(out, "jdk.lst"), with_cp=False)
dump("full.jsa", os.path.join(out, "all.lst"), with_cp=True)
