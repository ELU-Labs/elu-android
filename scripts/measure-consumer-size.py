#!/usr/bin/env python3
"""Measure what adding the SDK costs an optimized (R8) app: APK/AAB bytes and DEX counts.

Compares two release builds of the same minimal app, one without and one with the
SDK, and reports the difference. A mapping file attributes the SDK app's DEX
classes and methods to their original packages after obfuscation.
"""
from __future__ import annotations

import argparse
import json
import pathlib
import struct
import zipfile

DEX_MAGIC = b"dex\n"
ORIGINS = (
    ("dev.elu.analytics.", "ELU SDK"),
    ("okhttp3.", "OkHttp"),
    ("okio.", "Okio"),
    ("kotlin.", "Kotlin standard library"),
    ("kotlinx.", "Kotlin standard library"),
    ("j$.", "Java library desugaring"),
    # Backported java.* interfaces only appear in the desugared library DEX.
    ("java.", "Java library desugaring"),
    ("dev.elu.fixture.", "App"),
)


def uleb128(data: bytes, offset: int) -> tuple[int, int]:
    result = shift = 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7
        if shift > 28:
            raise ValueError("malformed uleb128")


def dex_string(data: bytes, string_ids_off: int, index: int) -> str:
    (data_off,) = struct.unpack_from("<I", data, string_ids_off + 4 * index)
    _, start = uleb128(data, data_off)
    end = data.index(b"\x00", start)
    return data[start:end].decode("utf-8", errors="replace")


def parse_dex(data: bytes) -> dict:
    """Header counts plus every defined class with its defined method count."""
    if data[:4] != DEX_MAGIC or len(data) < 0x70:
        raise ValueError("not a DEX file")
    (string_ids_off,) = struct.unpack_from("<I", data, 0x3C)
    type_ids_size, type_ids_off = struct.unpack_from("<II", data, 0x40)
    method_ids_size, _ = struct.unpack_from("<II", data, 0x58)
    class_defs_size, class_defs_off = struct.unpack_from("<II", data, 0x60)
    classes = []
    for index in range(class_defs_size):
        base = class_defs_off + 32 * index
        (class_idx,) = struct.unpack_from("<I", data, base)
        (class_data_off,) = struct.unpack_from("<I", data, base + 24)
        if class_idx >= type_ids_size:
            raise ValueError("class type index out of range")
        (descriptor_idx,) = struct.unpack_from("<I", data, type_ids_off + 4 * class_idx)
        descriptor = dex_string(data, string_ids_off, descriptor_idx)
        methods = 0
        if class_data_off:
            offset = class_data_off
            counts = []
            for _ in range(4):
                value, offset = uleb128(data, offset)
                counts.append(value)
            methods = counts[2] + counts[3]
        if not (descriptor.startswith("L") and descriptor.endswith(";")):
            raise ValueError("unexpected class descriptor")
        classes.append((descriptor[1:-1].replace("/", "."), methods))
    return {"bytes": len(data), "methodReferences": method_ids_size, "classes": classes}


def read_mapping(path: pathlib.Path | None) -> dict[str, str]:
    """Obfuscated class name -> original class name from an R8 mapping file."""
    if path is None:
        return {}
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if line.startswith((" ", "#")) or " -> " not in line or not line.endswith(":"):
            continue
        original, obfuscated = line[:-1].split(" -> ", 1)
        result[obfuscated] = original
    return result


def origin(name: str) -> str:
    for prefix, label in ORIGINS:
        if name.startswith(prefix):
            return label
    return "Other (Android/AndroidX)" if name.startswith(("android.", "androidx.")) else "Other"


def measure_apk(path: pathlib.Path, mapping: dict[str, str]) -> dict:
    with zipfile.ZipFile(path) as archive:
        dex_entries = sorted(n for n in archive.namelist() if n.startswith("classes") and n.endswith(".dex"))
        if not dex_entries:
            raise ValueError(f"{path} has no DEX files")
        dexes = [parse_dex(archive.read(name)) for name in dex_entries]
        dex_compressed = sum(archive.getinfo(name).compress_size for name in dex_entries)
    breakdown: dict[str, dict[str, int]] = {}
    for dex in dexes:
        for name, methods in dex["classes"]:
            row = breakdown.setdefault(origin(mapping.get(name, name)), {"classes": 0, "definedMethods": 0})
            row["classes"] += 1
            row["definedMethods"] += methods
    return {
        "apkBytes": path.stat().st_size,
        "dexFiles": len(dexes),
        "dexBytes": sum(d["bytes"] for d in dexes),
        "dexCompressedBytes": dex_compressed,
        "classes": sum(len(d["classes"]) for d in dexes),
        "definedMethods": sum(m for d in dexes for _, m in d["classes"]),
        "methodReferences": sum(d["methodReferences"] for d in dexes),
        "byOrigin": dict(sorted(breakdown.items())),
    }


def measure_aab(path: pathlib.Path) -> dict:
    with zipfile.ZipFile(path) as archive:
        metadata = sum(i.compress_size for i in archive.infolist() if i.filename.startswith("BUNDLE-METADATA/"))
    size = path.stat().st_size
    # BUNDLE-METADATA carries the R8 mapping for Play deobfuscation; it is never installed.
    return {"aabBytes": size, "aabBytesWithoutBundleMetadata": size - metadata}


def measure(args: argparse.Namespace) -> dict:
    mapping = read_mapping(args.sdk_mapping)
    baseline = {**measure_apk(args.baseline_apk, {}), **measure_aab(args.baseline_aab)}
    with_sdk = {**measure_apk(args.sdk_apk, mapping), **measure_aab(args.sdk_aab)}
    keys = ("apkBytes", "aabBytes", "aabBytesWithoutBundleMetadata", "dexBytes", "dexCompressedBytes",
            "dexFiles", "classes", "definedMethods", "methodReferences")
    return {"baseline": baseline, "withSdk": with_sdk, "delta": {k: with_sdk[k] - baseline[k] for k in keys}}


def markdown(result: dict) -> str:
    labels = {"apkBytes": "Release APK bytes (signed, universal)", "aabBytes": "Release AAB bytes",
              "aabBytesWithoutBundleMetadata": "Release AAB bytes without R8 mapping metadata",
              "dexBytes": "DEX bytes (uncompressed)", "dexCompressedBytes": "DEX bytes (compressed in APK)",
              "dexFiles": "DEX files", "classes": "DEX classes", "definedMethods": "DEX defined methods",
              "methodReferences": "DEX method references"}
    lines = ["| Measure | Without SDK | With SDK | Added by SDK |", "| --- | ---: | ---: | ---: |"]
    for key, label in labels.items():
        lines.append(f"| {label} | {result['baseline'][key]:,} | {result['withSdk'][key]:,} | {result['delta'][key]:,} |")
    lines += ["", "| Origin in the app with the SDK | DEX classes | DEX defined methods |", "| --- | ---: | ---: |"]
    for name, row in result["withSdk"]["byOrigin"].items():
        lines.append(f"| {name} | {row['classes']:,} | {row['definedMethods']:,} |")
    return "\n".join(lines) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline-apk", type=pathlib.Path, required=True)
    parser.add_argument("--baseline-aab", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-apk", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-aab", type=pathlib.Path, required=True)
    parser.add_argument("--sdk-mapping", type=pathlib.Path)
    parser.add_argument("--output-json", type=pathlib.Path)
    parser.add_argument("--output-markdown", type=pathlib.Path)
    args = parser.parse_args()
    result = measure(args)
    text = markdown(result)
    for path, content in ((args.output_json, json.dumps(result, indent=2) + "\n"), (args.output_markdown, text)):
        if path is not None:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    try:
        main()
    except (OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        raise SystemExit(f"consumer size measurement failed ({type(error).__name__}): {error}") from None
