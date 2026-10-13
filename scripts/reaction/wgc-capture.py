"""Capture ONE window exactly as the compositor shows it (Windows Graphics Capture), to a PNG.

PrintWindow cannot see every D3D child surface (it misses the single-screen reaction stage), so this
is the ground truth for "what is on screen in that window". Window-only: nothing else the user has
open is captured, even when it covers the window. Needs `pip install windows-capture pillow`
(keep it in a throwaway venv); ReactionModeHarnessTest uses it when NUVIO_REACTION_HARNESS_WGC_PYTHON
names that venv's python.

    python wgc-capture.py "<exact window title>" out.png
"""
import sys
import threading

from windows_capture import Frame, InternalCaptureControl, WindowsCapture


def main(title: str, out: str) -> int:
    done = threading.Event()
    capture = WindowsCapture(cursor_capture=False, draw_border=False, window_name=title)

    @capture.event
    def on_frame_arrived(frame: Frame, control: InternalCaptureControl):
        frame.save_as_image(out)
        done.set()
        control.stop()

    @capture.event
    def on_closed():
        done.set()

    capture.start_free_threaded()
    return 0 if done.wait(10) else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
