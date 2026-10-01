import os, subprocess, sys, time

# usage: drive.py <outdir> <clickx> <clicky>   (app already launched via run.py, sitting on the picker)
out, cx, cy = os.path.abspath(sys.argv[1]), sys.argv[2], sys.argv[3]
here = os.path.dirname(os.path.abspath(__file__))
pid = open(os.path.join(out, "pid")).read().strip()
# run.py's pid is the java pid (Popen of java.exe directly)

def cmd(*lines):
    path = os.path.join(out, "cmd.txt")
    while os.path.exists(path):
        time.sleep(0.1)
    with open(path + ".tmp", "w") as f:
        f.write("\n".join(lines) + "\n")
    os.replace(path + ".tmp", path)

def shot(name):
    subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(here, "grab.ps1"),
                    "-ProcId", pid, "-Out", os.path.join(out, name), "-Scale", "1.5"], capture_output=True)

def wheel_pass(name, rot):
    cmd(f"mark {name}", f"wheel 960 600 {rot} 50 120")
    time.sleep(7.5)

cmd("mark after-select", f"click {cx} {cy}")
time.sleep(12); shot("t12-after-select.png")
time.sleep(33); shot("t45-home.png")
cmd("gc", "mark home-idle")
time.sleep(20)
cmd("gc")
wheel_pass("scroll1-down", 1)
wheel_pass("scroll1-up", -1)
wheel_pass("scroll2-down", 1)
wheel_pass("scroll2-up", -1)
wheel_pass("scroll3-down", 1)
wheel_pass("scroll3-up", -1)
cmd("mark post-scroll", "gc")
time.sleep(30)
if os.environ.get("PERF_EXTRA_TABS"):
    cmd("mark tab-search", "move 600 500", "click 865 80")
    time.sleep(3); shot("search.png"); time.sleep(22)
    cmd("mark tab-home-return", "click 796 80")
    time.sleep(10)
long_idle = int(os.environ.get("PERF_LONG_IDLE", "0"))
if long_idle:
    cmd("move 1900 1150", "mark idle-long-warm")
    time.sleep(10)
    cmd("gc", "mark idle-long")
    time.sleep(long_idle)
    cmd("gc")
cmd("threads", "gc", "mark end")
time.sleep(2)
cmd("close")
print("done")
