"""Check the shipped Windows mpv DLL's load-time EOF behavior without an app/account."""
import ctypes as c
import os
from pathlib import Path
import tempfile
import time


class Event(c.Structure):
    _fields_ = [("event_id", c.c_int), ("error", c.c_int),
                ("reply_userdata", c.c_ulonglong), ("data", c.c_void_p)]


class EndFile(c.Structure):
    _fields_ = [("reason", c.c_int), ("error", c.c_int)]


def main():
    runtime = Path(__file__).resolve().parents[1] / "composeApp/src/desktopMain/native/windows/runtime"
    with os.add_dll_directory(str(runtime)):
        mpv = c.CDLL(str(runtime / "libmpv-2.dll"))
    mpv.mpv_create.restype = c.c_void_p
    mpv.mpv_set_option_string.argtypes = [c.c_void_p, c.c_char_p, c.c_char_p]
    mpv.mpv_initialize.argtypes = [c.c_void_p]
    mpv.mpv_command.argtypes = [c.c_void_p, c.POINTER(c.c_char_p)]
    mpv.mpv_wait_event.argtypes = [c.c_void_p, c.c_double]
    mpv.mpv_wait_event.restype = c.POINTER(Event)
    mpv.mpv_terminate_destroy.argtypes = [c.c_void_p]
    mpv.mpv_get_property_string.argtypes = [c.c_void_p, c.c_char_p]
    mpv.mpv_get_property_string.restype = c.c_void_p
    mpv.mpv_free.argtypes = [c.c_void_p]

    def property_text(handle, name):
        value = mpv.mpv_get_property_string(handle, name.encode())
        if not value:
            return None
        try:
            return c.string_at(value).decode()
        finally:
            mpv.mpv_free(value)

    def command(handle, *args):
        argv = (c.c_char_p * (len(args) + 1))(*(arg.encode() for arg in args), None)
        assert mpv.mpv_command(handle, argv) >= 0

    def check(media, unsafe):
        handle = mpv.mpv_create()
        assert handle
        try:
            for key, value in [("config", "no"), ("vo", "null"), ("ao", "null"),
                               ("terminal", "no"), ("pause", "yes"), ("idle", "yes"), ("keep-open", "yes")]:
                assert mpv.mpv_set_option_string(handle, key.encode(), value.encode()) >= 0
            assert mpv.mpv_initialize(handle) >= 0
            # Same loadfile options as player_bridge.cpp; start=5 is beyond this 2-second file.
            if unsafe:
                command(handle, "loadfile", str(media), "replace", "-1", "start=5")
            else:
                command(handle, "loadfile", str(media))
            loaded = ended = False
            until = time.monotonic() + 3
            while time.monotonic() < until:
                event = mpv.mpv_wait_event(handle, 0.05).contents
                loaded |= event.event_id == 8  # MPV_EVENT_FILE_LOADED
                ended |= event.event_id == 7  # MPV_EVENT_END_FILE
                if ended:
                    result = c.cast(event.data, c.POINTER(EndFile)).contents
                    raise AssertionError(f"unexpected end-file: reason={result.reason}, error={result.error}")
            assert loaded, "synthetic local media did not load"
            eof = property_text(handle, "eof-reached")
            assert (eof == "yes") == unsafe, f"unsafe={unsafe}, unexpected eof-reached={eof}"
            print(f"{'Unsafe host offset' if unsafe else 'Deferred guest alignment'}: loaded={loaded}, eof-reached={eof}, position={property_text(handle, 'time-pos')}")
        finally:
            mpv.mpv_terminate_destroy(handle)

    with tempfile.TemporaryDirectory(prefix="nuvio-party-start-") as directory:
        media = Path(directory) / "two-seconds.y4m"
        media.write_bytes(b"YUV4MPEG2 W16 H16 F10:1 Ip A1:1 C420jpeg\n" +
                          (b"FRAME\n" + bytes([16]) * 256 + bytes([128]) * 128) * 20)
        check(media, unsafe=True)
        check(media, unsafe=False)


if __name__ == "__main__":
    main()
