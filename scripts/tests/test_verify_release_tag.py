from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE_SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "verify-release-tag.py"


class VerifyReleaseTagTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="elu-tag-test-")
        self.root = pathlib.Path(self.temp.name)
        script = self.root / "scripts" / "verify-release-tag.py"
        script.parent.mkdir(parents=True)
        shutil.copy2(SOURCE_SCRIPT, script)
        version = self.root / "elu-analytics" / "src" / "main" / "kotlin" / "dev" / "elu" / "analytics" / "EluVersion.kt"
        version.parent.mkdir(parents=True)
        version.write_text('internal object EluVersion { const val NAME: String = "1.2.3" }\n', encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.email", "test@elu.dev")
        self.git("config", "user.name", "ELU Test")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")

    def tearDown(self) -> None:
        self.temp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(["git", *args], cwd=self.root, check=True, capture_output=True, text=True).stdout.strip()

    def verify(self, tag: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run([sys.executable, "scripts/verify-release-tag.py", tag], cwd=self.root, capture_output=True, text=True)

    def test_accepts_matching_annotated_reviewed_tag(self) -> None:
        self.git("tag", "-a", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        self.assertEqual(0, self.verify("1.2.3").returncode)

    def test_rejects_lightweight_tag(self) -> None:
        self.git("tag", "1.2.3")
        self.assertNotEqual(0, self.verify("1.2.3").returncode)

    def test_rejects_missing_review_trailer(self) -> None:
        self.git("tag", "-a", "1.2.3", "-m", "Release 1.2.3")
        self.assertNotEqual(0, self.verify("1.2.3").returncode)

    def test_rejects_version_mismatch(self) -> None:
        self.git("tag", "-a", "1.2.4", "-m", "Release\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        self.assertNotEqual(0, self.verify("1.2.4").returncode)


if __name__ == "__main__":
    unittest.main()
