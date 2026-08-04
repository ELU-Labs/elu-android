from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "validate-network-parser-smoke.py"
FIXTURE = ROOT / "scanner" / "fixtures" / "network-parser-smoke.json"


class ValidateNetworkParserSmokeTest(unittest.TestCase):
    def run_validator(self, fixture: pathlib.Path) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(fixture)],
            capture_output=True,
            text=True,
        )

    def test_repository_fixture_is_valid(self) -> None:
        result = self.run_validator(FIXTURE)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_request_count_mismatch_is_rejected(self) -> None:
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        data["expectedRequestCount"] = 5
        with tempfile.TemporaryDirectory(prefix="elu-network-smoke-test-") as directory:
            fixture = pathlib.Path(directory) / "fixture.json"
            fixture.write_text(json.dumps(data), encoding="utf-8")
            result = self.run_validator(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("expected 5 request(s)", result.stdout + result.stderr)

    def test_runtime_evidence_claim_is_rejected(self) -> None:
        data = json.loads(FIXTURE.read_text(encoding="utf-8"))
        data["runtimeEvidence"] = True
        with tempfile.TemporaryDirectory(prefix="elu-network-smoke-test-") as directory:
            fixture = pathlib.Path(directory) / "fixture.json"
            fixture.write_text(json.dumps(data), encoding="utf-8")
            result = self.run_validator(fixture)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("runtimeEvidence=false", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
