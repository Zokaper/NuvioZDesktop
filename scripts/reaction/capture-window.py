"""Capture ONE top-level window by its exact title, even behind other windows, to a PNG.

Used by ReactionModeHarnessTest. PrintWindow with PW_RENDERFULLCONTENT renders the window's own
content (including D3D/DirectComposition children such as mpv and WebView2) without capturing the
screen, so nothing else the user has open can end up in the image.

    python -I capture-window.py "<exact window title>" out.png [descendant window class]

With a class name, the first descendant window of that class is captured instead (e.g. the player's
native container, whose D3D content PrintWindow of the top-level frame does not include).
"""
import ctypes
import ctypes.wintypes as wt
import sys

from PIL import Image

user32 = ctypes.windll.user32
gdi32 = ctypes.windll.gdi32
user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # per-monitor v2: physical pixels

PW_RENDERFULLCONTENT = 2


class BITMAPINFOHEADER(ctypes.Structure):
    _fields_ = [
        ("biSize", wt.DWORD), ("biWidth", wt.LONG), ("biHeight", wt.LONG), ("biPlanes", wt.WORD),
        ("biBitCount", wt.WORD), ("biCompression", wt.DWORD), ("biSizeImage", wt.DWORD),
        ("biXPelsPerMeter", wt.LONG), ("biYPelsPerMeter", wt.LONG), ("biClrUsed", wt.DWORD),
        ("biClrImportant", wt.DWORD),
    ]


def find_descendant(parent, class_name):
    found = []
    callback_type = ctypes.WINFUNCTYPE(wt.BOOL, wt.HWND, wt.LPARAM)

    def visit(hwnd, _):
        buffer = ctypes.create_unicode_buffer(256)
        user32.GetClassNameW(hwnd, buffer, 256)
        if buffer.value == class_name:
            found.append(hwnd)
            return False
        return True

    user32.EnumChildWindows(parent, callback_type(visit), 0)
    return found[0] if found else None


def main(title: str, out: str, class_name: str = None) -> int:
    hwnd = user32.FindWindowW(None, title)
    if not hwnd:
        print(f"no window titled {title!r}", file=sys.stderr)
        return 2
    if class_name:
        hwnd = find_descendant(hwnd, class_name)
        if not hwnd:
            print(f"no {class_name!r} window inside {title!r}", file=sys.stderr)
            return 2
    rect = wt.RECT()
    user32.GetWindowRect(hwnd, ctypes.byref(rect))
    width, height = rect.right - rect.left, rect.bottom - rect.top
    window_dc = user32.GetWindowDC(hwnd)
    memory_dc = gdi32.CreateCompatibleDC(window_dc)
    bitmap = gdi32.CreateCompatibleBitmap(window_dc, width, height)
    gdi32.SelectObject(memory_dc, bitmap)
    ok = user32.PrintWindow(hwnd, memory_dc, PW_RENDERFULLCONTENT)
    header = BITMAPINFOHEADER()
    header.biSize = ctypes.sizeof(BITMAPINFOHEADER)
    header.biWidth, header.biHeight, header.biPlanes, header.biBitCount = width, -height, 1, 32
    buffer = ctypes.create_string_buffer(width * height * 4)
    gdi32.GetDIBits(memory_dc, bitmap, 0, height, buffer, ctypes.byref(header), 0)
    gdi32.DeleteObject(bitmap)
    gdi32.DeleteDC(memory_dc)
    user32.ReleaseDC(hwnd, window_dc)
    if not ok:
        print("PrintWindow failed", file=sys.stderr)
        return 3
    Image.frombuffer("RGBA", (width, height), buffer, "raw", "BGRA", 0, 1).convert("RGB").save(out)
    return 0


def tree(title: str, out: str) -> int:
    """Writes the window's descendants top-to-bottom in z-order (class, rect, visible) - for stacking bugs."""
    root = user32.FindWindowW(None, title)
    if not root:
        return 2
    lines = []

    def walk(hwnd, depth):
        child = user32.GetWindow(hwnd, 5)  # GW_CHILD: topmost child
        while child:
            buffer = ctypes.create_unicode_buffer(256)
            user32.GetClassNameW(child, buffer, 256)
            rect = wt.RECT()
            user32.GetWindowRect(child, ctypes.byref(rect))
            visible = bool(user32.IsWindowVisible(child))
            lines.append(f"{'  ' * depth}{buffer.value} hwnd={child:#x} rect=({rect.left},{rect.top},{rect.right},{rect.bottom}) visible={visible}")
            walk(child, depth + 1)
            child = user32.GetWindow(child, 2)  # GW_HWNDNEXT: next one down

    walk(root, 0)
    open(out, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "--tree":
        sys.exit(tree(sys.argv[2], sys.argv[3]))
    sys.exit(main(*sys.argv[1:4]))
