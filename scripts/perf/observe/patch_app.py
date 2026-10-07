"""Copy a test app and replace only classes compiled from named Kotlin source files.

Keeps the installed launcher/runtime, resources, configuration and other classes for a fair
performance comparison. Includes generated companions and removes obsolete generated classes.
Usage: python patch_app.py BASE_APP REBUILT_JAR NEW_APP AppGate.kt ProfileSettingsSync.kt
"""
import argparse
from pathlib import Path
import shutil
import struct
from zipfile import ZipFile


def source_file(data):
    """Read the JVM class-level SourceFile attribute, rather than guessing generated names."""
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Invalid class file")
    pos = 8

    def number(size):
        nonlocal pos
        value = int.from_bytes(data[pos:pos + size], "big")
        pos += size
        return value

    pool = [None] * number(2)
    index = 1
    while index < len(pool):
        tag = number(1)
        if tag == 1:
            size = number(2)
            pool[index] = data[pos:pos + size].decode("utf-8", errors="replace")
            pos += size
        elif tag in (3, 4, 9, 10, 11, 12, 17, 18):
            pos += 4
        elif tag in (5, 6):
            pos += 8
            index += 1
        elif tag in (7, 8, 16, 19, 20):
            pos += 2
        elif tag == 15:
            pos += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    pos += 6  # access flags, this class, superclass
    interfaces = number(2)
    pos += 2 * interfaces
    for _ in range(2):  # fields and methods
        for _ in range(number(2)):
            pos += 6
            for _ in range(number(2)):
                number(2)
                size = number(4)
                pos += size
    for _ in range(number(2)):
        name = pool[number(2)]
        size = number(4)
        if name == "SourceFile":
            if size != 2:
                raise ValueError("Invalid SourceFile attribute")
            return pool[number(2)]
        pos += size
    return None


def selected_classes(jar, sources):
    selected = {}
    found = set()
    for entry in jar.infolist():
        if not entry.filename.startswith("com/nuvio/app/") or not entry.filename.endswith(".class"):
            continue
        data = jar.read(entry)
        source = source_file(data)
        if source in sources:
            if struct.unpack_from(">H", data, 6)[0] > 61:
                raise ValueError(f"Class exceeds installed Java 17: {entry.filename}")
            selected[entry.filename] = (entry, data)
            found.add(source)
    if found != sources:
        raise ValueError(f"Missing source files: {sorted(sources - found)}")
    return selected


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("base", type=Path)
    parser.add_argument("rebuilt_jar", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("sources", nargs="+")
    args = parser.parse_args()
    base, output = args.base.resolve(strict=True), args.output.resolve()
    if output.exists() or output == base or base in output.parents:
        raise ValueError("Output must be a fresh directory outside the base app")
    sources = set(args.sources)
    if any(Path(source).name != source for source in sources):
        raise ValueError("Pass source filenames, without directories")
    candidates = list((base / "app").glob("composeApp-*.jar"))
    if len(candidates) != 1:
        raise ValueError("Expected one main app jar")
    original = candidates[0]
    with ZipFile(original) as before, ZipFile(args.rebuilt_jar) as rebuilt:
        old = selected_classes(before, sources)
        new = selected_classes(rebuilt, sources)
        shutil.copytree(base, output)
        target = output / "app" / original.name
        pending = target.with_suffix(".jar.pending")
        with ZipFile(pending, "w") as patched:
            for entry in before.infolist():
                if entry.filename not in old:
                    patched.writestr(entry, before.read(entry))
            for entry, data in new.values():
                patched.writestr(entry, data)
        # Verify every retained entry's contents and require the selected classes to match.
        with ZipFile(pending) as patched:
            expected = (set(before.namelist()) - set(old)) | set(new)
            if set(patched.namelist()) != expected:
                raise ValueError("Unexpected patched jar entries")
            for entry in before.infolist():
                if entry.filename not in old and patched.read(entry.filename) != before.read(entry):
                    raise ValueError(f"Unrelated entry changed: {entry.filename}")
            for name, (_, data) in new.items():
                if patched.read(name) != data:
                    raise ValueError(f"Replacement mismatch: {name}")
        if not pending.resolve().is_relative_to(output) or not target.resolve().is_relative_to(output):
            raise ValueError("Jar replacement escaped the output directory")
        pending.replace(target)
    print(f"Copied app to {output}; replaced {len(old)} old classes with {len(new)} rebuilt classes")
    print("Source files: " + ", ".join(sorted(sources)))


if __name__ == "__main__":
    main()
