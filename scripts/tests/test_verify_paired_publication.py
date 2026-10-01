from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import subprocess
import unittest
from unittest.mock import patch

import test_validate_compose_distribution as fixtures

SPEC = importlib.util.spec_from_file_location("paired_publication", pathlib.Path(__file__).parents[1] / "verify-paired-publication.py")
PUBLICATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PUBLICATION)


class PairedPublicationTest(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.ComposeDistributionTest()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.root = self.fixture.root.resolve()
        receipt = fixtures.DIST.stage(self.root)
        self.value = {"source": {"version": "0.2.0"}, "distribution": {"files": receipt["files"]}}
        self.repositories = []
        for module in fixtures.DIST.MODULES:
            repository = self.root / module / "build/publishing/mavenCentral"
            self.repositories.append(repository)
            source = self.root / "build/compose-distribution/repository/dev/elu" / module
            for file in source.rglob("*"):
                if file.is_file():
                    dest = repository / "dev/elu" / module / file.relative_to(source)
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    dest.write_bytes(file.read_bytes())
                    dest.with_name(dest.name + ".asc").write_bytes(b"synthetic signature")
            metadata = repository / "dev/elu" / module / "maven-metadata.xml"
            metadata.write_text(f"<metadata><groupId>dev.elu</groupId><artifactId>{module}</artifactId>"
                                "<versioning><latest>0.2.0</latest><release>0.2.0</release>"
                                "<versions><version>0.2.0</version></versions></versioning></metadata>")
            for file in list(repository.rglob("*")):
                if file.is_file():
                    for algorithm in ("md5", "sha1", "sha256", "sha512"):
                        file.with_name(file.name + "." + algorithm).write_text(hashlib.new(algorithm, file.read_bytes()).hexdigest())

    def check(self):
        return PUBLICATION.published_bytes(self.root, self.value)

    def test_exact_pair_checks_all_ten_signatures_without_upload(self):
        with patch.object(PUBLICATION.subprocess, "run") as gpg:
            self.check()
        self.assertEqual(gpg.call_count, 10)
        for call in gpg.call_args_list:
            self.assertEqual(call.args[0][:3], ["gpg", "--batch", "--verify"])
            self.assertEqual(call.kwargs["timeout"], 15)
            self.assertTrue(call.kwargs["check"])

    def test_core_and_optional_payload_substitutions_are_rejected(self):
        for repository in self.repositories:
            for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar"):
                file = next(p for p in repository.rglob("*") if p.name.endswith(suffix))
                original = file.read_bytes()
                file.write_bytes(original + b"changed")
                with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "payload differs"):
                    self.check()
                file.write_bytes(original)

    def test_missing_signature_bad_checksum_extra_and_stale_versions_rejected(self):
        repository = self.repositories[1]
        signature = next(repository.rglob("*.aar.asc"))
        data = signature.read_bytes(); signature.unlink()
        with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "missing publication"):
            self.check()
        signature.write_bytes(data)
        checksum = next(repository.rglob("*.aar.sha256"))
        data = checksum.read_bytes(); checksum.write_bytes(b"0" * 64)
        with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "checksum differs"):
            self.check()
        checksum.write_bytes(data)
        for relative in ("extra.jar", "dev/elu/elu-analytics-compose/0.1.0/elu-analytics-compose-0.1.0.aar"):
            extra = repository / relative; extra.parent.mkdir(parents=True, exist_ok=True); extra.write_bytes(b"unreviewed")
            with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "unexpected publication"):
                self.check()
            extra.unlink()

    def test_linked_member_or_repository_rejected(self):
        file = next(self.repositories[0].rglob("*.aar"))
        target = self.root / "original.aar"; file.rename(target); file.symlink_to(target)
        with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "linked"):
            self.check()
        file.unlink(); target.rename(file)
        repository = self.repositories[1]; target = self.root / "moved"; repository.rename(target); repository.symlink_to(target)
        with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "linked"):
            self.check()

    def test_invalid_signature_and_verifier_timeout_refuse_publication(self):
        for error in (subprocess.CalledProcessError(1, ["gpg"]), subprocess.TimeoutExpired(["gpg"], 15)):
            with patch.object(PUBLICATION.subprocess, "run", side_effect=error), self.assertRaises(type(error)):
                self.check()

    def test_mutation_during_signature_verification_is_rejected(self):
        file = next(self.repositories[0].rglob("*.aar"))
        def mutate(*_args, **_kwargs):
            if file.read_bytes().endswith(b"changed"):
                return
            file.write_bytes(file.read_bytes() + b"changed")
        with patch.object(PUBLICATION.subprocess, "run", side_effect=mutate), self.assertRaisesRegex(ValueError, "changed during validation"):
            self.check()

    def test_old_version_in_metadata_is_rejected(self):
        file = next(self.repositories[1].rglob("maven-metadata.xml"))
        file.write_bytes(file.read_bytes().replace(b"<versions>", b"<versions><version>0.1.0</version>"))
        with patch.object(PUBLICATION.subprocess, "run"), self.assertRaisesRegex(ValueError, "different version"):
            self.check()

    def test_verify_joins_signed_export_build_outputs_and_final_staging(self):
        path = self.root / "build/reports/android-runtime-network-evidence.json"
        path.parent.mkdir(parents=True); path.write_bytes(b"synthetic signed export")
        calls = []
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.EVIDENCE, "signed_binding", side_effect=lambda tag: calls.append("tag") or {"tag": tag}), \
             patch.object(PUBLICATION.EVIDENCE, "validate", side_effect=lambda *_a, **_k: calls.append("export") or self.value), \
             patch.object(PUBLICATION.EVIDENCE, "read_distribution", side_effect=lambda *_a: calls.append("distribution")), \
             patch.object(PUBLICATION, "published_bytes", side_effect=lambda *_a: calls.append("published")), \
             patch.object(PUBLICATION.DIST, "stage", wraps=fixtures.DIST.stage):
            PUBLICATION.verify("0.2.0", True)
        self.assertEqual(calls, ["tag", "export", "published", "tag", "distribution"])
        aar = self.root / "elu-analytics-compose/build/outputs/aar/elu-analytics-compose-release.aar"
        aar.write_bytes(aar.read_bytes() + b"changed")
        with patch.object(PUBLICATION, "ROOT", self.root), \
             patch.object(PUBLICATION.EVIDENCE, "signed_binding", return_value={}), \
             patch.object(PUBLICATION.EVIDENCE, "validate", return_value=self.value), \
             patch.object(PUBLICATION, "published_bytes") as published, self.assertRaises(ValueError):
            PUBLICATION.verify("0.2.0", True)
        published.assert_not_called()
