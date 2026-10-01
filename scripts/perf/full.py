import os, subprocess, sys, time

# usage: full.py <appdir> <outdir> <clickx> <clicky> [extra jvm args...]
app, out, cx, cy = sys.argv[1], os.path.abspath(sys.argv[2]), sys.argv[3], sys.argv[4]
extra = sys.argv[5:]
here = os.path.dirname(os.path.abspath(__file__))
launcher = subprocess.Popen([sys.executable, os.path.join(here, "run.py"), app, out] + extra)
time.sleep(30)
with open(os.path.join(out, "cmd.txt"), "w") as f:
    f.write("mark picker-idle\n")
time.sleep(8)
subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", os.path.join(here, "grab.ps1"),
                "-ProcId", open(os.path.join(out, "pid")).read().strip(), "-Out", os.path.join(out, "picker.png"), "-Scale", "1.5"],
               capture_output=True)
subprocess.run([sys.executable, os.path.join(here, "drive.py"), out, cx, cy])
launcher.wait(timeout=120)
print("full done")
