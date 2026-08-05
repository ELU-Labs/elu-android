#!/usr/bin/env python3
"""Compare the public JVM surface in an AAR with the reviewed 0.1.0 snapshot."""

from __future__ import annotations

import argparse
import pathlib
import subprocess
import tempfile
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
SNAPSHOT = ROOT / "baselines" / "0.1.0" / "api" / "public-api.txt"
PUBLIC_CLASSES = ("dev.elu.analytics.Elu", "dev.elu.analytics.EluOptions")


def normalized_snapshot() -> str:
    lines = SNAPSHOT.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines if not line.startswith("#")).strip()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("aar", type=pathlib.Path)
    args = parser.parse_args()
    if not args.aar.is_file():
        parser.error(f"AAR does not exist: {args.aar}")

    with tempfile.TemporaryDirectory(prefix="elu-api-") as temp_dir:
        classes = pathlib.Path(temp_dir) / "classes.jar"
        with zipfile.ZipFile(args.aar) as archive:
            classes.write_bytes(archive.read("classes.jar"))
        result = subprocess.run(
            ["javap", "-classpath", str(classes), "-public", *PUBLIC_CLASSES],
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    expected = normalized_snapshot()
    if result != expected:
        raise SystemExit(
            "public API/ABI changed; review and deliberately update the snapshot\n"
            f"--- expected ---\n{expected}\n--- actual ---\n{result}"
        )
    print("public API/ABI matches 0.1.0")


if __name__ == "__main__":
    main()
