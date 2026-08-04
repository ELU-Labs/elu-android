from __future__ import annotations

import os
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
        self.gnupg_temp = tempfile.TemporaryDirectory(prefix="elu-tag-gpg-test-")
        self.gnupg = pathlib.Path(self.gnupg_temp.name)
        self.gnupg.chmod(0o700)
        self.environment = os.environ.copy()
        self.environment["GNUPGHOME"] = str(self.gnupg)
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
        self.gnupg_temp.cleanup()

    def git(self, *args: str) -> str:
        return subprocess.run(
            ["git", *args],
            cwd=self.root,
            env=self.environment,
            check=True,
            capture_output=True,
            text=True,
        ).stdout.strip()

    def create_signing_key(self) -> str:
        subprocess.run(
            [
                "gpg",
                "--batch",
                "--pinentry-mode",
                "loopback",
                "--passphrase",
                "",
                "--quick-generate-key",
                "ELU Release Test <release-test@elu.dev>",
                "ed25519",
                "sign",
                "0",
            ],
            env=self.environment,
            check=True,
            capture_output=True,
            text=True,
        )
        listing = subprocess.run(
            ["gpg", "--batch", "--with-colons", "--list-secret-keys"],
            env=self.environment,
            check=True,
            capture_output=True,
            text=True,
        ).stdout
        fingerprint = next(
            line.split(":")[9]
            for line in listing.splitlines()
            if line.startswith("fpr:")
        )
        self.git("config", "user.signingkey", fingerprint)
        return fingerprint

    def verify(self, tag: str, trusted: str | None = None) -> subprocess.CompletedProcess[str]:
        environment = self.environment.copy()
        environment.pop("ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS", None)
        if trusted is not None:
            environment["ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS"] = trusted
        return subprocess.run(
            [sys.executable, "scripts/verify-release-tag.py", tag],
            cwd=self.root,
            env=environment,
            capture_output=True,
            text=True,
        )

    def test_accepts_matching_reviewed_tag_signed_by_trusted_key(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        result = self.verify("1.2.3", trusted=fingerprint)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)

    def test_rejects_unsigned_annotated_tag(self) -> None:
        self.git("tag", "-a", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        result = self.verify("1.2.3", trusted="0" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("valid cryptographic signature", result.stdout + result.stderr)

    def test_rejects_valid_signature_from_untrusted_key(self) -> None:
        self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        result = self.verify("1.2.3", trusted="0" * 40)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not in the trusted set", result.stdout + result.stderr)

    def test_rejects_missing_trusted_fingerprint_configuration(self) -> None:
        self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        result = self.verify("1.2.3")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("fails closed", result.stdout + result.stderr)

    def test_rejects_untracked_worktree_content(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", "Release 1.2.3\n\nReviewed-by: SDK Owner <owner@elu.dev>")
        (self.root / "untracked.txt").write_text("not reviewed", encoding="utf-8")
        result = self.verify("1.2.3", trusted=fingerprint)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("including untracked files", result.stdout + result.stderr)

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
