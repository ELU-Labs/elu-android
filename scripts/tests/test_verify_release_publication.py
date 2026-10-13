from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import patch

import test_validate_compose_distribution as fixtures

SPEC = importlib.util.spec_from_file_location("release_publication", pathlib.Path(__file__).parents[1] / "verify-release-publication.py")
PUBLICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLICATION)
SIGNER = PUBLICATION.ARTIFACT_SIGNING_FINGERPRINT


def signed(*_args, **_kwargs) -> subprocess.CompletedProcess:
    status = f"[GNUPG:] GOODSIG 71C7F8C6582E838B ELU\n[GNUPG:] VALIDSIG {SIGNER} 2026-10-13 0 4 0 1 10 00 {SIGNER}\n"
    return subprocess.CompletedProcess(["gpg"], 0, status, "")


class ReleasePublicationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ComposeDistributionTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root.resolve()
        self.receipt = fixtures.DIST.stage(self.root)
        self.repository = self.root / "elu-analytics/build/publishing/mavenCentral"
        source = self.root / "build/compose-distribution/repository/dev/elu/elu-analytics"
        for file in source.rglob("*"):
            if file.is_file():
                dest = self.repository / "dev/elu/elu-analytics" / file.relative_to(source)
                dest.parent.mkdir(parents=True, exist_ok=True)
                dest.write_bytes(file.read_bytes())
                dest.with_name(dest.name + ".asc").write_bytes(b"synthetic signature")
        metadata = self.repository / "dev/elu/elu-analytics/maven-metadata.xml"
        metadata.write_text("<metadata><groupId>dev.elu</groupId><artifactId>elu-analytics</artifactId>"
                            "<versioning><latest>0.2.0</latest><release>0.2.0</release>"
                            "<versions><version>0.2.0</version></versions></versioning></metadata>")
        for file in list(self.repository.rglob("*")):
            if file.is_file():
                for algorithm in ("md5", "sha1", "sha256", "sha512"):
                    file.with_name(file.name + "." + algorithm).write_text(hashlib.new(algorithm, file.read_bytes()).hexdigest())
        aar = self.receipt["files"]["repository/dev/elu/elu-analytics/0.2.0/elu-analytics-0.2.0.aar"]
        self.binding = {"tag": "0.2.0", "sourceCommit": "a" * 40, "ciRunId": "1", "coreAarSha256": aar["sha256"],
                        "decision": "release/decisions/0.2.0.md", "signer": "B" * 40}

    def check(self):
        return PUBLICATION.published_bytes(self.root, self.receipt)

    def test_core_only_checks_five_signatures_from_the_artifact_key_without_upload(self):
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed) as gpg:
            self.check()
        self.assertEqual(gpg.call_count, 5)
        for call in gpg.call_args_list:
            self.assertEqual(call.args[0][:5], ["gpg", "--batch", "--status-fd", "1", "--verify"])
            self.assertEqual(call.kwargs["timeout"], 15)

    def test_signature_from_another_key_or_failed_verification_is_refused(self):
        other = subprocess.CompletedProcess(["gpg"], 0, "[GNUPG:] VALIDSIG " + "C" * 40 + "\n", "")
        with patch.object(PUBLICATION.subprocess, "run", return_value=other), \
             self.assertRaisesRegex(ValueError, "not from the Maven artifact signing key"):
            self.check()
        failed = subprocess.CompletedProcess(["gpg"], 2, "", "No public key")
        with patch.object(PUBLICATION.subprocess, "run", return_value=failed), \
             self.assertRaisesRegex(ValueError, "does not verify"):
            self.check()
        with patch.object(PUBLICATION.subprocess, "run", side_effect=subprocess.TimeoutExpired(["gpg"], 15)), \
             self.assertRaises(subprocess.TimeoutExpired):
            self.check()

    def test_payload_substitutions_are_rejected(self):
        for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar"):
            file = next(p for p in self.repository.rglob("*") if p.name.endswith(suffix))
            original = file.read_bytes()
            file.write_bytes(original + b"changed")
            with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
                 self.assertRaisesRegex(ValueError, "payload differs"):
                self.check()
            file.write_bytes(original)

    def test_staged_compose_module_is_refused(self):
        staged = self.root / "elu-analytics-compose/build/publishing/mavenCentral/dev/elu/elu-analytics-compose"
        staged.mkdir(parents=True)
        (staged / "maven-metadata.xml").write_text("<metadata/>")
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
             self.assertRaisesRegex(ValueError, "unpublished module"):
            self.check()

    def test_missing_signature_bad_checksum_extra_and_stale_versions_rejected(self):
        signature = next(self.repository.rglob("*.aar.asc"))
        data = signature.read_bytes(); signature.unlink()
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
             self.assertRaisesRegex(ValueError, "missing publication"):
            self.check()
        signature.write_bytes(data)
        checksum = next(self.repository.rglob("*.aar.sha256"))
        data = checksum.read_bytes(); checksum.write_bytes(b"0" * 64)
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
             self.assertRaisesRegex(ValueError, "checksum differs"):
            self.check()
        checksum.write_bytes(data)
        for relative in ("extra.jar", "dev/elu/elu-analytics/0.1.0/elu-analytics-0.1.0.aar",
                         "dev/elu/elu-analytics-compose/0.2.0/elu-analytics-compose-0.2.0.aar"):
            extra = self.repository / relative; extra.parent.mkdir(parents=True, exist_ok=True); extra.write_bytes(b"unreviewed")
            with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
                 self.assertRaisesRegex(ValueError, "unexpected publication"):
                self.check()
            extra.unlink()

    def test_linked_member_or_repository_rejected(self):
        file = next(self.repository.rglob("*.aar"))
        target = self.root / "original.aar"; file.rename(target); file.symlink_to(target)
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), self.assertRaisesRegex(ValueError, "linked"):
            self.check()
        file.unlink(); target.rename(file)
        moved = self.root / "moved"; self.repository.rename(moved); self.repository.symlink_to(moved)
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), self.assertRaisesRegex(ValueError, "linked"):
            self.check()

    def test_mutation_during_signature_verification_is_rejected(self):
        file = next(self.repository.rglob("*.aar"))
        def mutate(*args, **kwargs):
            if not file.read_bytes().endswith(b"changed"):
                file.write_bytes(file.read_bytes() + b"changed")
            return signed(*args, **kwargs)
        with patch.object(PUBLICATION.subprocess, "run", side_effect=mutate), \
             self.assertRaisesRegex(ValueError, "changed"):
            self.check()

    def test_old_version_in_metadata_is_rejected(self):
        file = next(self.repository.rglob("maven-metadata.xml"))
        file.write_bytes(file.read_bytes().replace(b"<versions>", b"<versions><version>0.1.0</version>"))
        with patch.object(PUBLICATION.subprocess, "run", side_effect=signed), \
             self.assertRaisesRegex(ValueError, "different version"):
            self.check()

    def test_verify_binds_signed_tag_core_aar_build_outputs_and_staging(self):
        calls = []
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.TAG, "verify_release", side_effect=lambda tag: calls.append("tag") or dict(self.binding)), \
             patch.object(PUBLICATION, "published_bytes", side_effect=lambda *_a: calls.append("published")):
            PUBLICATION.verify("0.2.0", True)
        self.assertEqual(calls, ["tag", "published", "tag"])

    def test_aar_that_differs_from_the_signed_tag_is_refused_before_staging(self):
        binding = dict(self.binding, coreAarSha256="0" * 64)
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.TAG, "verify_release", return_value=binding), \
             patch.object(PUBLICATION, "published_bytes") as published, \
             self.assertRaisesRegex(ValueError, "Core-AAR-SHA256"):
            PUBLICATION.verify("0.2.0", True)
        published.assert_not_called()

    def test_rebuilt_output_that_differs_from_the_validated_stage_is_refused(self):
        aar = self.root / "elu-analytics-compose/build/outputs/aar/elu-analytics-compose-release.aar"
        aar.write_bytes(aar.read_bytes() + b"changed")
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.TAG, "verify_release", return_value=self.binding), \
             patch.object(PUBLICATION, "published_bytes") as published, self.assertRaises(ValueError):
            PUBLICATION.verify("0.2.0", True)
        published.assert_not_called()

    def test_binding_change_after_staging_is_refused(self):
        bindings = iter([dict(self.binding), dict(self.binding, sourceCommit="b" * 40)])
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.TAG, "verify_release", side_effect=lambda _tag: next(bindings)), \
             patch.object(PUBLICATION, "published_bytes"), self.assertRaisesRegex(ValueError, "changed"):
            PUBLICATION.verify("0.2.0", True)

    def test_keyring_check_requires_the_artifact_signing_key(self):
        listing = subprocess.CompletedProcess(["gpg"], 0, f"pub:-:4096:1:71C7F8C6582E838B:::::::::\nfpr:::::::::{SIGNER}:\n", "")
        with patch.object(PUBLICATION.subprocess, "run", return_value=listing):
            PUBLICATION.check_keyring()
        missing = subprocess.CompletedProcess(["gpg"], 2, "", "")
        with patch.object(PUBLICATION.subprocess, "run", return_value=missing), \
             self.assertRaisesRegex(ValueError, "ELU_RELEASE_SIGNING_PUBLIC_KEYS"):
            PUBLICATION.check_keyring()


if __name__ == "__main__":
    unittest.main()
