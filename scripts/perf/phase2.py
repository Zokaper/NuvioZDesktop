import os, shutil, subprocess, sys, time

# Performance Phase 2 protocols. Like phase1.py, always on a fresh copy of a signed-out data directory.
#
# usage: phase2.py <scroll|startup> <appdir> <outdir> <signed-out-appdata-template> [clickx clicky]
#
#   scroll   launch -> picker -> click the profile -> 45 s (cold Home load) -> 20 s idle
#            -> Continue Watching right/left (Shift+wheel) -> 3 vertical wheel passes down/up
#            -> Library and back to Home x3 -> one more vertical pass -> close.
#            Read: frames.txt per phase (summary2.py), mem.txt (process memory after each step).
#   startup  launch -> 20 s on the picker -> click the profile -> 25 s -> close.
#            Read: startup2.py (launch -> first window task, picker, after-click freeze).
#
# The template must hold no session file; this refuses to run otherwise.
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
    deadline = time.time() + 60
    while os.path.exists(path):
        if time.time() > deadline or launcher.poll() is not None:
            sys.exit(f"the app stopped taking commands; see {out}/stdout.log")
        time.sleep(0.1)
    with open(path + ".tmp", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(path + ".tmp", path)


def pid():
    return open(os.path.join(out, "pid")).read().strip()


def shot(name):
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(here, "grab.ps1"),
                    "-ProcId", pid(), "-Out", os.path.join(out, name), "-Scale", "1.5"], capture_output=True)


def mem(label):
    # Working set and private bytes of the whole process: Skia bitmaps live outside the Java heap.
    cmd("gc")
    time.sleep(1.5)
    r = subprocess.run(["powershell", "-NoProfile", "-Command",
                        f"$p = Get-Process -Id {pid()}; \"$($p.WorkingSet64) $($p.PrivateMemorySize64)\""],
                       capture_output=True, text=True)
    with open(os.path.join(out, "mem.txt"), "a") as f:
        f.write(f"{label} {r.stdout.strip()}\n")


def wheel_pass(name, rot):
    cmd(f"mark {name}", f"wheel 960 600 {rot} 50 120")
    time.sleep(7.5)


if mode == "startup":
    time.sleep(20)
    cmd("mark picker-idle")
    time.sleep(2)
    shot("picker.png")
    cmd("mark after-select", f"click {cx} {cy}")
    time.sleep(25)
    shot("home.png")
else:
    time.sleep(25)
    cmd("mark picker-idle")
    time.sleep(8)
    shot("picker.png")
    cmd("mark after-select", f"click {cx} {cy}")
    time.sleep(45)
    shot("t45-home.png")
    cmd("move 1900 1150", "mark home-idle")
    mem("home-loaded")
    time.sleep(20)
    # Continue Watching sits at the bottom of the first screen under the hero.
    cmd("mark cw-right", "hwheel 800 1150 1 60 40")
    time.sleep(4)
    shot("cw-right.png")
    cmd("mark cw-left", "hwheel 800 1150 -1 60 40")
    time.sleep(4)
    cmd("move 1900 1150")
    mem("after-cw")
    for n in (1, 2, 3):
        wheel_pass(f"scroll{n}-down", 1)
        if n == 1:
            shot("scrolled.png")
        wheel_pass(f"scroll{n}-up", -1)
        mem(f"after-scroll{n}")
    cmd("mark post-scroll", "move 1900 1150")
    time.sleep(10)
    for n in (1, 2, 3):
        cmd(f"mark tab-library-{n}", "move 934 80", "click 934 80")
        time.sleep(6)
        # Off Home the bar is expanded and Home sits at x=490 (see phase1.py).
        cmd(f"mark tab-home-{n}", "move 490 80", "click 490 80")
        time.sleep(6)
        cmd("move 1900 1150")
        mem(f"after-tabs{n}")
    shot("home-returned.png")
    wheel_pass("scroll4-down", 1)
    wheel_pass("scroll4-up", -1)
    cmd("mark end-idle", "move 1900 1150")
    time.sleep(10)
    mem("end")
cmd("threads", "gc", "mark end")
time.sleep(2)
cmd("close")
launcher.wait(timeout=120)
print("done", mode, out)
