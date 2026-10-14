from __future__ import annotations

import argparse
import importlib.util
import pathlib
import struct
import tempfile
import unittest
import zipfile

REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("measure_consumer_size", REPOSITORY / "scripts/measure-consumer-size.py")
assert SPEC and SPEC.loader
SIZE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SIZE)


def dex(classes: list[tuple[str, int, int]], method_references: int) -> bytes:
    """Minimal DEX: string/type tables and class_data with (descriptor, direct, virtual) methods."""
    header = 0x70
    strings = [descriptor.encode() for descriptor, _, _ in classes]
    string_ids_off = header
    type_ids_off = string_ids_off + 4 * len(strings)
    class_defs_off = type_ids_off + 4 * len(strings)
    data_off = class_defs_off + 32 * len(classes)
    body = bytearray()
    string_offsets = []
    for value in strings:
        string_offsets.append(data_off + len(body))
        body += bytes([len(value)]) + value + b"\x00"
    class_data_offsets = []
    for _, direct, virtual in classes:
        class_data_offsets.append(data_off + len(body))
        body += bytes([0, 0, direct, virtual])
    output = bytearray(b"dex\n035\x00" + bytes(header - 8))
    struct.pack_into("<II", output, 0x38, len(strings), string_ids_off)
    struct.pack_into("<II", output, 0x40, len(strings), type_ids_off)
    struct.pack_into("<II", output, 0x58, method_references, 0)
    struct.pack_into("<II", output, 0x60, len(classes), class_defs_off)
    output += b"".join(struct.pack("<I", offset) for offset in string_offsets)
    output += b"".join(struct.pack("<I", index) for index in range(len(strings)))
    for index, offset in enumerate(class_data_offsets):
        output += struct.pack("<IIIIIIII", index, 1, 0, 0, 0, 0, offset, 0)
    return bytes(output + body)


class ConsumerSizeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="elu-consumer-size-")
        self.root = pathlib.Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def archive(self, name: str, entries: dict[str, bytes]) -> pathlib.Path:
        path = self.root / name
        with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as output:
            for entry, data in entries.items():
                output.writestr(entry, data)
        return path

    def test_delta_and_origin_attribution_through_the_r8_mapping(self) -> None:
        baseline = dex([("Ldev/elu/fixture/baseline/App;", 1, 1)], 5)
        primary = dex([("Ldev/elu/fixture/smoke/App;", 1, 1), ("La/a;", 3, 2), ("Lb/c;", 1, 0)], 40)
        desugared = dex([("Lj$/time/Instant;", 4, 4), ("Ljava/util/function/Supplier;", 0, 1)], 30)
        mapping = self.root / "mapping.txt"
        mapping.write_text("# comment\ndev.elu.analytics.Elu -> a.a:\n    void setup() -> a\nokhttp3.OkHttpClient -> b.c:\n")
        args = argparse.Namespace(
            baseline_apk=self.archive("baseline.apk", {"classes.dex": baseline}),
            baseline_aab=self.archive("baseline.aab", {"base/dex/classes.dex": baseline}),
            sdk_apk=self.archive("sdk.apk", {"classes.dex": primary, "classes2.dex": desugared}),
            sdk_aab=self.archive("sdk.aab", {"base/dex/classes.dex": primary,
                                             "BUNDLE-METADATA/com.android.tools.build.obfuscation/proguard.map": b"x" * 4096}),
            sdk_mapping=mapping)
        result = SIZE.measure(args)
        self.assertEqual(2, result["withSdk"]["dexFiles"])
        self.assertEqual(70, result["withSdk"]["methodReferences"])
        self.assertEqual(65, result["delta"]["methodReferences"])
        self.assertEqual(4, result["delta"]["classes"])
        self.assertEqual(17, result["withSdk"]["definedMethods"])
        self.assertEqual({"App": {"classes": 1, "definedMethods": 2}, "ELU SDK": {"classes": 1, "definedMethods": 5},
                          "OkHttp": {"classes": 1, "definedMethods": 1},
                          "Java library desugaring": {"classes": 2, "definedMethods": 9}},
                         result["withSdk"]["byOrigin"])
        self.assertLess(result["withSdk"]["aabBytesWithoutBundleMetadata"], result["withSdk"]["aabBytes"])
        self.assertIn("| DEX method references | 5 | 70 | 65 |", SIZE.markdown(result))

    def test_non_dex_entry_is_refused(self) -> None:
        with self.assertRaisesRegex(ValueError, "not a DEX"):
            SIZE.parse_dex(b"PK\x03\x04" + bytes(200))


if __name__ == "__main__":
    unittest.main()
