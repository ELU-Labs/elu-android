#!/usr/bin/env python3
"""Compare the public JVM surface in an AAR with the reviewed snapshots.

Two snapshots are checked:

- ``public-api.txt``: the ``javap -public`` output of the customer-facing
  facade classes, byte-for-byte against the published 0.1.0 release.
- ``jvm-classes.txt``: every public, non-synthetic JVM class in the release
  ``classes.jar``. The library is not minified, so Kotlin ``internal``
  declarations compile to public JVM classes and any new one widens the
  binary surface. Pass ``--update-classes`` to regenerate that inventory after
  a deliberate change.
"""

from __future__ import annotations

import argparse
import io
import pathlib
import struct
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
API_DIR = ROOT / "baselines" / "0.1.0" / "api"
SNAPSHOT = API_DIR / "public-api.txt"
CLASS_INVENTORY = API_DIR / "jvm-classes.txt"
PUBLIC_CLASSES = ("dev.elu.analytics.Elu", "dev.elu.analytics.EluOptions")

ACC_PUBLIC = 0x0001
ACC_SYNTHETIC = 0x1000

CLASS_INVENTORY_HEADER = (
    "# Every public, non-synthetic JVM class in the release classes.jar.\n"
    "# Regenerate with: python3 scripts/check-api-snapshot.py --update-classes <aar>\n"
)


def normalized_snapshot(path: pathlib.Path) -> str:
    lines = path.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines if not line.startswith("#")).strip()


def read_class_header(data: bytes) -> tuple[int, str]:
    """Return (access_flags, binary class name) parsed from a class file."""
    stream = io.BytesIO(data)

    def take(fmt: str) -> tuple:
        size = struct.calcsize(fmt)
        chunk = stream.read(size)
        if len(chunk) != size:
            raise ValueError("truncated class file")
        return struct.unpack(fmt, chunk)

    (magic,) = take(">I")
    if magic != 0xCAFEBABE:
        raise ValueError("not a class file")
    take(">HH")  # minor, major
    (constant_count,) = take(">H")
    utf8: dict[int, str] = {}
    class_name_index: dict[int, int] = {}
    index = 1
    while index < constant_count:
        (tag,) = take(">B")
        if tag == 1:
            (length,) = take(">H")
            utf8[index] = stream.read(length).decode("utf-8", errors="replace")
        elif tag in (3, 4):
            take(">I")
        elif tag in (5, 6):
            take(">Q")
            index += 1
        elif tag == 7:
            (name_index,) = take(">H")
            class_name_index[index] = name_index
        elif tag in (8, 16, 19, 20):
            take(">H")
        elif tag in (9, 10, 11, 12, 17, 18):
            take(">I")
        elif tag == 15:
            take(">BH")
        else:
            raise ValueError(f"unknown constant pool tag {tag}")
        index += 1
    access_flags, this_class = take(">HH")
    name = utf8[class_name_index[this_class]].replace("/", ".")
    return access_flags, name


def public_jvm_classes(classes_jar: pathlib.Path) -> list[str]:
    names: list[str] = []
    with zipfile.ZipFile(classes_jar) as archive:
        for entry in archive.namelist():
            if not entry.endswith(".class") or entry.startswith("META-INF/"):
                continue
            flags, name = read_class_header(archive.read(entry))
            if flags & ACC_PUBLIC and not flags & ACC_SYNTHETIC:
                names.append(name)
    return sorted(names)


def facade_signatures(classes_jar: pathlib.Path) -> str:
    return subprocess.run(
        ["javap", "-classpath", str(classes_jar), "-public", *PUBLIC_CLASSES],
        check=True,
        capture_output=True,
        text=True,
    ).stdout.strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=pathlib.Path)
    parser.add_argument(
        "--update-classes",
        action="store_true",
        help="rewrite jvm-classes.txt from the AAR instead of comparing against it",
    )
    args = parser.parse_args()
    if not args.aar.is_file():
        parser.error(f"AAR does not exist: {args.aar}")

    with tempfile.TemporaryDirectory(prefix="elu-api-") as temp_dir:
        classes = pathlib.Path(temp_dir) / "classes.jar"
        with zipfile.ZipFile(args.aar) as archive:
            classes.write_bytes(archive.read("classes.jar"))
        facade = facade_signatures(classes)
        inventory = public_jvm_classes(classes)

    expected_facade = normalized_snapshot(SNAPSHOT)
    if facade != expected_facade:
        raise SystemExit(
            "public API/ABI changed; review and deliberately update the snapshot\n"
            f"--- expected ---\n{expected_facade}\n--- actual ---\n{facade}"
        )

    if args.update_classes:
        CLASS_INVENTORY.write_text(
            CLASS_INVENTORY_HEADER + "\n".join(inventory) + "\n", encoding="utf-8"
        )
        print(f"wrote {len(inventory)} public JVM classes to {CLASS_INVENTORY.relative_to(ROOT)}")
        return

    expected_inventory = normalized_snapshot(CLASS_INVENTORY).splitlines()
    added = sorted(set(inventory) - set(expected_inventory))
    removed = sorted(set(expected_inventory) - set(inventory))
    if added or removed:
        lines = ["public JVM class inventory changed; review and deliberately update jvm-classes.txt"]
        lines.extend(f"+ {name}" for name in added)
        lines.extend(f"- {name}" for name in removed)
        raise SystemExit("\n".join(lines))
    print(f"public API/ABI matches 0.1.0; {len(inventory)} public JVM classes match the inventory")


if __name__ == "__main__":
    main()
