from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts" / "verify-feature-flag-boundary.py"
SPEC = importlib.util.spec_from_file_location("verify_feature_flag_boundary", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
BOUNDARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOUNDARY)


class FeatureFlagBoundaryGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="elu-flag-boundary-")
        self.root = pathlib.Path(self.temporary.name)
        main_source = REPOSITORY / BOUNDARY.MAIN_KOTLIN
        shutil.copytree(main_source, self.root / BOUNDARY.MAIN_KOTLIN)
        for relative in {*BOUNDARY.PINNED_FILES, *BOUNDARY.CONTRACT_FILES}:
            source = REPOSITORY / relative
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_guard(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root)],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_clean_unwired_boundary_passes(self) -> None:
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_public_facade_mutation_fails(self) -> None:
        facade = self.root / "elu-analytics/src/main/kotlin/dev/elu/analytics/Elu.kt"
        facade.write_text(facade.read_text(encoding="utf-8") + "\n// accidental public cutover\n", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("pinned feature-flag boundary changed", result.stderr)

    def test_concrete_transport_fails(self) -> None:
        conformer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/WiredTransport.kt"
        conformer.write_text(
            """package dev.elu.analytics.internal.flags

internal class WiredTransport : FlagTransport {
    override fun send(request: FlagTransportRequest) = error("wired")
}
""",
            encoding="utf-8",
        )
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("production FlagTransport reference/conformer is forbidden", result.stderr)

    def test_contract_status_mutation_fails(self) -> None:
        manifest_path = self.root / "elu-analytics/src/test/resources/contracts/v1/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["transport"]["status"] = "wired"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("transport status must remain specified-not-wired", result.stderr)


if __name__ == "__main__":
    unittest.main()
