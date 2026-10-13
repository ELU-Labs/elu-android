from __future__ import annotations

import os
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest

SOURCE_SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "verify-release-tag.py"
AAR = "a" * 64
RUN = "https://github.com/ELU-Labs/elu-android/actions/runs/123456789"


# CI always has GnuPG; a developer machine without it skips instead of failing.
@unittest.skipUnless(shutil.which("gpg") or os.environ.get("CI"), "GnuPG is not installed")
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
        decision = self.root / "release" / "decisions" / "1.2.3.md"
        decision.parent.mkdir(parents=True)
        decision.write_text("# 1.2.3 release decision\n", encoding="utf-8")
        self.git("init", "-q")
        self.git("config", "user.email", "test@elu.dev")
        self.git("config", "user.name", "ELU Test")
        # Fixture keys are OpenPGP regardless of the developer's global signing format.
        self.git("config", "gpg.format", "openpgp")
        self.git("add", ".")
        self.git("commit", "-qm", "fixture")
        self.head = self.git("rev-parse", "HEAD")

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

    def message(self, **overrides: str | None) -> str:
        trailers = {
            "Source-Commit": self.head,
            "CI-Run": RUN,
            "Core-AAR-SHA256": AAR,
            "Release-Decision": "release/decisions/1.2.3.md",
            "Reviewed-by": "SDK Reviewer <reviewer@elu.dev>",
        }
        for key, value in overrides.items():
            key = key.replace("_", "-")
            if value is None:
                trailers.pop(key)
            else:
                trailers[key] = value
        lines = "\n".join(f"{key}: {value}" for key, value in trailers.items())
        return f"Release 1.2.3\n\nAnalytics and feature flags.\n\n{lines}"

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

    def signed_tag_result(self, message: str) -> subprocess.CompletedProcess[str]:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", message)
        return self.verify("1.2.3", trusted=fingerprint)

    def assert_refused(self, result: subprocess.CompletedProcess[str], text: str) -> None:
        self.assertNotEqual(0, result.returncode, result.stdout)
        self.assertIn(text, result.stdout + result.stderr)

    def test_accepts_matching_reviewed_tag_signed_by_trusted_key(self) -> None:
        result = self.signed_tag_result(self.message())
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("CI run 123456789", result.stdout)
        self.assertIn(AAR, result.stdout)

    def test_rejects_each_missing_release_trailer(self) -> None:
        fingerprint = self.create_signing_key()
        for key in ("Source-Commit", "CI-Run", "Core-AAR-SHA256", "Release-Decision"):
            with self.subTest(key=key):
                self.git("tag", "-f", "-s", "1.2.3", "-m", self.message(**{key: None}))
                self.assert_refused(self.verify("1.2.3", trusted=fingerprint), f"exactly one {key}: trailer")

    def test_rejects_duplicate_release_trailer(self) -> None:
        message = self.message() + "\nCore-AAR-SHA256: " + "b" * 64
        self.assert_refused(self.signed_tag_result(message), "exactly one Core-AAR-SHA256: trailer")

    def test_rejects_source_commit_other_than_the_tagged_commit(self) -> None:
        self.assert_refused(self.signed_tag_result(self.message(Source_Commit="b" * 40)),
                            "does not name the tagged commit")

    def test_rejects_ci_run_outside_this_repository(self) -> None:
        other = "https://github.com/ELU-Labs/other/actions/runs/123456789"
        self.assert_refused(self.signed_tag_result(self.message(CI_Run=other)), "Actions run URL")

    def test_rejects_malformed_core_aar_digest(self) -> None:
        self.assert_refused(self.signed_tag_result(self.message(Core_AAR_SHA256="A" * 64)),
                            "64 lowercase hex")

    def test_rejects_decision_for_another_version_or_missing_record(self) -> None:
        self.assert_refused(self.signed_tag_result(self.message(Release_Decision="release/decisions/1.2.2.md")),
                            "must name release/decisions/1.2.3.md")
        self.git("tag", "-d", "1.2.3")
        self.git("rm", "-q", "release/decisions/1.2.3.md")
        self.git("commit", "-qm", "remove decision")
        self.head = self.git("rev-parse", "HEAD")
        self.git("tag", "-s", "1.2.3", "-m", self.message())
        trusted = self.git("config", "user.signingkey")
        self.assert_refused(self.verify("1.2.3", trusted=trusted), "missing from the tagged commit")

    def test_rejects_ref_whose_authenticated_tag_name_differs(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "other-name", "-m", self.message())
        self.git("update-ref", "refs/tags/1.2.3", "refs/tags/other-name")
        self.git("verify-tag", "refs/tags/1.2.3")
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "signed name does not match")

    def test_rejects_signed_tag_of_tag_even_when_it_peels_to_head(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-a", "inner", "-m", "Inner fixture")
        self.git("tag", "-s", "1.2.3", "inner", "-m", self.message())
        self.git("verify-tag", "refs/tags/1.2.3")
        self.assertEqual(self.git("rev-parse", "1.2.3^{commit}"), self.git("rev-parse", "HEAD"))
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "directly reference the checked-out commit")

    def replace_tag_object(self, mutate) -> None:
        original = self.git("cat-file", "tag", "refs/tags/1.2.3")
        result = subprocess.run(["git", "hash-object", "-t", "tag", "-w", "--stdin"],
            input=mutate(original), cwd=self.root, env=self.environment, check=True, capture_output=True, text=True)
        self.git("update-ref", "refs/tags/1.2.3", result.stdout.strip())

    def test_rejects_unsigned_release_trailer_after_valid_signature(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message(Core_AAR_SHA256=None))
        self.replace_tag_object(lambda raw: raw + "\n\nCore-AAR-SHA256: " + AAR + "\n")
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "unsigned text after")

    def test_rejects_unsigned_review_trailer_after_valid_signature(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message(Reviewed_by=None))
        self.replace_tag_object(lambda raw: raw + "\n\nReviewed-by: SDK Reviewer <reviewer@elu.dev>\n")
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "unsigned text after")

    def test_rejects_digest_mutated_inside_authenticated_payload(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message())
        self.replace_tag_object(lambda raw: raw.replace(AAR, "b" * 64))
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "valid cryptographic signature")

    def test_rejects_unsigned_annotated_tag(self) -> None:
        self.git("tag", "-a", "1.2.3", "-m", self.message())
        self.assert_refused(self.verify("1.2.3", trusted="0" * 40), "valid cryptographic signature")

    def test_rejects_valid_signature_from_untrusted_key(self) -> None:
        self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message())
        self.assert_refused(self.verify("1.2.3", trusted="0" * 40), "not in the trusted set")

    def test_rejects_missing_trusted_fingerprint_configuration(self) -> None:
        self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message())
        self.assert_refused(self.verify("1.2.3"), "fails closed")

    def test_rejects_untracked_worktree_content(self) -> None:
        fingerprint = self.create_signing_key()
        self.git("tag", "-s", "1.2.3", "-m", self.message())
        (self.root / "untracked.txt").write_text("not reviewed", encoding="utf-8")
        self.assert_refused(self.verify("1.2.3", trusted=fingerprint), "including untracked files")

    def test_rejects_lightweight_tag(self) -> None:
        self.git("tag", "1.2.3")
        self.assert_refused(self.verify("1.2.3", trusted="0" * 40), "lightweight tags cannot publish")

    def test_rejects_missing_review_trailer(self) -> None:
        self.assert_refused(self.signed_tag_result(self.message(Reviewed_by=None)), "Reviewed-by: trailer")

    def test_rejects_version_mismatch(self) -> None:
        self.git("tag", "-a", "1.2.4", "-m", self.message())
        self.assert_refused(self.verify("1.2.4"), "does not match EluVersion.NAME")


if __name__ == "__main__":
    unittest.main()
