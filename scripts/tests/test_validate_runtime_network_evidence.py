from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "validate-runtime-network-evidence.py"
EXPECTATIONS = ("config=1", "capture=1", "replay=1", "flags=1")


class ValidateRuntimeNetworkEvidenceTest(unittest.TestCase):
    def run_validator(self, fixture: pathlib.Path) -> subprocess.CompletedProcess[str]:
        command = [sys.executable, str(SCRIPT), str(fixture)]
        for expectation in EXPECTATIONS:
            command.extend(("--expect", expectation))
        return subprocess.run(command, capture_output=True, text=True)

    def write_fixture(self, directory: str, *, runtime_evidence: bool = True) -> pathlib.Path:
        fixture = pathlib.Path(directory) / "runtime-network.json"
        fixture.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "evidenceKind": "android-runtime-network-capture",
                    "runtimeEvidence": runtime_evidence,
                    "requests": [
                        {"scenario": "config", "url": "https://elu.dev/v1/config"},
                        {"scenario": "capture", "url": "https://ingest.elu.dev/v1/events"},
                        {"scenario": "replay", "url": "https://ingest.elu.dev/v1/replay"},
                        {"scenario": "flags", "url": "https://ingest.elu.dev/v1/flags"},
                    ],
                }
            ),
            encoding="utf-8",
        )
        return fixture

    def test_matching_generated_evidence_is_valid(self) -> None:
        with tempfile.TemporaryDirectory(prefix="elu-runtime-network-test-") as directory:
            result = self.run_validator(self.write_fixture(directory))
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_static_fixture_cannot_claim_runtime_evidence(self) -> None:
        with tempfile.TemporaryDirectory(prefix="elu-runtime-network-test-") as directory:
            result = self.run_validator(self.write_fixture(directory, runtime_evidence=False))
        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtimeEvidence=true", result.stdout + result.stderr)

    def test_missing_scenario_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="elu-runtime-network-test-") as directory:
            fixture = self.write_fixture(directory)
            data = json.loads(fixture.read_text(encoding="utf-8"))
            data["requests"].pop()
            fixture.write_text(json.dumps(data), encoding="utf-8")
            result = self.run_validator(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("flags: expected 1, observed 0", result.stdout + result.stderr)

    def test_missing_file_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory(prefix="elu-runtime-network-test-") as directory:
            result = self.run_validator(pathlib.Path(directory) / "missing.json")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("evidence is missing", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
