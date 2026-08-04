from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "zero-brand-gate.py"
TOKEN = "VendorWord"


class ZeroBrandGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="elu-scanner-test-")
        self.root = pathlib.Path(self.temp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        legal = self.root / "legal" / "THIRD_PARTY_NOTICES.md"
        legal.parent.mkdir()
        legal.write_text(
            "<!-- zero-brand-token-start -->\n"
            f"{TOKEN}\n"
            "<!-- zero-brand-token-end -->\n",
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_gate(self, *args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root), "--token-file", str(self.root / "legal" / "THIRD_PARTY_NOTICES.md"), *args],
            cwd=self.root,
            check=check,
            capture_output=True,
            text=True,
        )

    def test_strict_rejects_source_but_allows_legal_file(self) -> None:
        subprocess.run(["git", "add", "legal/THIRD_PARTY_NOTICES.md"], cwd=self.root, check=True)
        self.assertEqual(0, self.run_gate().returncode)

        (self.root / "source.txt").write_text(TOKEN, encoding="utf-8")
        result = self.run_gate()
        self.assertEqual(1, result.returncode)
        self.assertIn("source.txt", result.stdout)

    def test_nested_archive_content_is_scanned(self) -> None:
        archive = self.root / "artifact.aar"
        with zipfile.ZipFile(archive, "w") as outer:
            outer.writestr("classes.txt", f"symbol:{TOKEN}")

        result = self.run_gate("--skip-tree", "--input", f"aar={archive}")
        self.assertEqual(1, result.returncode)
        self.assertIn("aar!/classes.txt", result.stdout)

    def test_ratchet_accepts_known_debt_and_rejects_new_debt(self) -> None:
        (self.root / "legacy.txt").write_text(TOKEN, encoding="utf-8")
        emitted = self.run_gate("--emit-baseline", check=True)
        baseline = self.root / "baseline.json"
        baseline.write_text(emitted.stdout, encoding="utf-8")

        self.assertEqual(0, self.run_gate("--mode", "ratchet", "--baseline", str(baseline)).returncode)
        (self.root / "new.txt").write_text(TOKEN, encoding="utf-8")
        self.assertEqual(1, self.run_gate("--mode", "ratchet", "--baseline", str(baseline)).returncode)
        self.assertEqual(1, json.loads(emitted.stdout)["schemaVersion"])


if __name__ == "__main__":
    unittest.main()
