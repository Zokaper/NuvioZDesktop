import os, shutil, subprocess, sys, time

# Performance Phase 1 protocols. Always runs on a fresh copy of a signed-out data directory.
#
# usage: phase1.py <startup|tabs> <appdir> <outdir> <signed-out-appdata-template> [clickx clicky]
#
#   startup  launch -> 25 s on the profile picker -> close. Read: the first UI task (first TASK line).
#   tabs     launch -> picker -> click the profile -> 45 s -> 30 s idle on Home (hero visible)
#            -> Library tab -> 10 s settle -> 60 s idle (Home hidden) -> Home -> 30 s idle -> close.
#            Read: frames / UI-thread tasks / CPU per phase (frames.txt, phases.txt), and with
#            PERF_JFR_EXTRA=",jdk.ThreadPark#threshold=0ms" the coroutine timer wakeups (wakeups.py).
#
# The template must hold no `nuvio_official_session.properties`; this refuses to run otherwise.
mode, app, out, template = sys.argv[1], sys.argv[2], os.path.abspath(sys.argv[3]), sys.argv[4]
cx, cy = (sys.argv[5], sys.argv[6]) if len(sys.argv) > 6 else ("724", "640")
here = os.path.dirname(os.path.abspath(__file__))

for root, _, files in os.walk(template):
    if any("session" in f.lower() for f in files):
        sys.exit(f"refusing: {root} holds a session file")

appdata = out + "-appdata"
shutil.rmtree(appdata, ignore_errors=True)
shutil.copytree(template, appdata)
env = dict(os.environ, APPDATA=appdata)
launcher = subprocess.Popen([sys.executable, os.path.join(here, "run.py"), app, out], env=env)


def cmd(*lines):
    path = os.path.join(out, "cmd.txt")
    while os.path.exists(path):
        time.sleep(0.1)
    with open(path + ".tmp", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(path + ".tmp", path)


def shot(name):
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(here, "grab.ps1"),
                    "-ProcId", open(os.path.join(out, "pid")).read().strip(), "-Out", os.path.join(out, name), "-Scale", "1.5"],
                   capture_output=True)


time.sleep(25)
cmd("mark picker-idle")
if mode == "startup":
    time.sleep(5)
    shot("picker.png")
else:
    time.sleep(8)
    shot("picker.png")
    cmd("mark after-select", f"click {cx} {cy}")
    time.sleep(45)
    shot("t45-home.png")
    cmd("move 1900 1150", "gc", "mark home-visible")
    time.sleep(30)
    cmd("gc", "mark tab-library-settle", "move 934 80", "click 934 80")
    time.sleep(3)
    shot("library.png")
    time.sleep(7)
    cmd("move 1900 1150", "gc", "mark home-hidden")
    time.sleep(60)
    cmd("gc", "mark home-return-settle", "move 796 80", "click 796 80")
    time.sleep(5)
    cmd("move 1900 1150", "mark home-returned")
    time.sleep(30)
    shot("home-returned.png")
cmd("threads", "gc", "mark end")
time.sleep(2)
cmd("close")
launcher.wait(timeout=120)
print("done", mode, out)
