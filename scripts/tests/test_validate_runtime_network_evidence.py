from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import pathlib
import shutil
import tempfile
import unittest
from unittest.mock import patch
import test_validate_compose_distribution as distribution_fixture

SCRIPTS = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("release_evidence_tested", SCRIPTS / "android-release-evidence.py")
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def protocol_fixture():
    """Synthetic parser double only; never a Lab receipt or release artifact."""
    ref = {"sha256": "a" * 64, "bytes": 12}
    return {
        "schemaVersion": 2, "evidenceKind": "android-release-lab-evidence",
        "source": {"repository": evidence.REPOSITORY, "commit": "b" * 40, "version": "0.2.0"},
        "artifact": {"coordinate": "dev.elu:elu-analytics:0.2.0", "sha256": hashlib.sha256(b"unit-aar").hexdigest(), "bytes": 8},
        "producer": {"repository": "ELU-Labs/elu-sdk-lab", "commit": "c" * 40, "exporter": "exportCompletedNativeReleaseEvidence"},
        "run": {"runId": "12345678-1234-1234-1234-123456789abc", "testRunId": "lab-12345678-1234-1234-1234-123456789abc-android",
                "runSha256": "d" * 64, "manifestSha256": "e" * 64, "siteKeyHash": "f" * 64},
        "completion": {"kind": "completed-original-native-release", "receipt": copy.deepcopy(ref),
                       "proofs": {key: copy.deepcopy(ref) for key in evidence.PROOFS}},
        "environment": {"kind": "default-cloud", "configOrigin": "https://elu.dev", "ingestOrigin": "https://ingest.elu.dev", "tls": "platform-system-trust"},
        "requests": [{"scenario": key, "method": method, "url": url, "urlKind": "sanitized-route-template", "count": 3,
                      "exchanges": copy.deepcopy(ref)} for key, (method, url) in evidence.ROUTES.items()],
    }


def encoded(value):
    return json.dumps(value, separators=(",", ":")).encode()


def binding(data):
    # Unit-only substitute for already verified signed-tag identity.
    return {"tag": "0.2.0", "sourceCommit": "b" * 40, "evidenceSha256": hashlib.sha256(data).hexdigest()}


def reference(data):
    return {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}


def paired_protocol_fixture(catalogue=None, descriptor=None):
    """Synthetic signed-envelope double, never original Lab completion evidence."""
    value = protocol_fixture()
    del value["artifact"]
    value["schemaVersion"] = 3
    if catalogue is None:
        catalogue = {"kind": "local-distribution-only", "runtimeQualified": False, "version": "0.2.0",
                     "files": {name: reference(("unit-only:" + name).encode())
                               for paths in evidence.member_paths("0.2.0").values() for name in paths.values()}}
    descriptor = encoded(catalogue) if descriptor is None else descriptor
    value["distribution"] = {"descriptor": reference(descriptor), "files": copy.deepcopy(catalogue["files"])}
    value["artifacts"] = {role: {"coordinate": f"dev.elu:{module}:0.2.0",
        **copy.deepcopy(catalogue["files"][evidence.member_paths("0.2.0")[role][".aar"]])}
        for role, module in evidence.MODULES.items()}
    value["scope"] = {"profile": evidence.PROFILE, "profiles": {}}
    for index, (name, framework) in enumerate(evidence.FRAMEWORKS.items(), start=1):
        run = copy.deepcopy(value["run"])
        run["runId"] = f"12345678-1234-1234-1234-{index:012d}"
        run["testRunId"] = f"lab-{run['runId']}-android"
        run["runSha256"] = str(index) * 64
        run["manifestSha256"] = str(index + 2) * 64
        value["scope"]["profiles"][name] = {
            "framework": framework, "cohort": "lab" if index == 1 else "replay",
            "sourceCommit": value["source"]["commit"],
            "coreAarSha256": value["artifacts"]["core"]["sha256"],
            "composeAarSha256": value["artifacts"]["compose"]["sha256"],
            "distributionSha256": reference(descriptor)["sha256"],
            "run": run, "completionReceiptSha256": value["completion"]["receipt"]["sha256"],
            **{field: reference((name + ":" + field).encode()) for field in ("configuration", "policy", "observations")},
        }
    value["requests"] = [{"scenario": name, "method": method, "url": url,
        "urlKind": "sanitized-route-template", "count": 1, "exchanges": reference(name.encode())}
        for name, (method, url) in evidence.PAIRED_ROUTES.items()]
    return value


def staged_protocol_fixture(root):
    original = distribution_fixture.ComposeDistributionTest()
    original.setUp()
    try:
        distribution_fixture.DIST.stage(original.root)
        shutil.copytree(original.root / "build/compose-distribution", root)
    finally:
        original.tearDown()
    descriptor = (root / "distribution.json").read_bytes()
    return paired_protocol_fixture(json.loads(descriptor), descriptor)


class ValidateRuntimeNetworkEvidenceTest(unittest.TestCase):
    def reject(self, value, match=None):
        data = encoded(value)
        with self.assertRaisesRegex(ValueError, match or "."):
            evidence.validate(data, binding(data))

    def test_closed_protocol_shape_and_actual_bytes(self):
        data = encoded(protocol_fixture())
        with tempfile.TemporaryDirectory() as directory:
            aar = pathlib.Path(directory) / "unit.aar"
            aar.write_bytes(b"unit-aar")
            evidence.validate(data, binding(data), aar)
            aar.write_bytes(b"wrongaar")
            with self.assertRaisesRegex(ValueError, "built AAR differs"):
                evidence.validate(data, binding(data), aar)
            aar.write_bytes(b"short")
            with self.assertRaisesRegex(ValueError, "built AAR differs"):
                evidence.validate(data, binding(data), aar)

    def test_untrusted_digest_cannot_be_replaced_by_embedded_claim(self):
        data = encoded(protocol_fixture())
        trust = binding(data)
        trust["evidenceSha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "trusted signed tag"):
            evidence.validate(data, trust)

    def test_each_required_proof_is_mandatory(self):
        for name in evidence.PROOFS:
            with self.subTest(name=name):
                value = protocol_fixture()
                del value["completion"]["proofs"][name]
                self.reject(value, "required original proofs")

    def test_legacy_pass_flags_and_diagnostic_receipts_rejected(self):
        for value in ({"schemaVersion": 1, "runtimeEvidence": True, "requests": [{}]},
                      {"kind": "candidate-final-readiness", "finalCertification": False}):
            self.reject(value)
        for kind in ("incomplete", "fail", "candidate-final-readiness", "local-native-runtime"):
            value = protocol_fixture()
            value["completion"]["kind"] = kind
            self.reject(value)

    def test_source_version_and_original_run_binding(self):
        for field, wrong in (("commit", "a" * 40), ("version", "0.1.0"), ("repository", "other/repo")):
            value = protocol_fixture()
            value["source"][field] = wrong
            self.reject(value)
        for field, wrong in (("testRunId", "lab-other-android"), ("runId", "not-original"),
                             ("siteKeyHash", "elu_pk_private"), ("manifestSha256", "bad")):
            value = protocol_fixture()
            value["run"][field] = wrong
            self.reject(value)

    def test_custom_local_and_test_ca_cannot_qualify_default_cloud(self):
        for field, wrong in (("kind", "local"), ("configOrigin", "https://local.example"),
                             ("ingestOrigin", "http://127.0.0.1"), ("tls", "private-ca")):
            value = protocol_fixture()
            value["environment"][field] = wrong
            self.reject(value)

    def test_no_secret_or_arbitrary_fields_at_any_level(self):
        for path in ((), ("source",), ("artifact",), ("producer",), ("run",), ("completion",),
                     ("environment",), ("completion", "receipt"), ("completion", "proofs", "installation")):
            value = protocol_fixture()
            target = value
            for key in path:
                target = target[key]
            target["privateHeaders"] = {"Authorization": "secret"}
            self.reject(value)
        value = protocol_fixture()
        value["requests"][0]["body"] = "secret"
        self.reject(value)

    def test_native_route_privacy_and_channels(self):
        for index in range(4):
            for field, wrong in (("url", "https://elu.dev/sdk/v2/elu_pk_secret/config"),
                                 ("url", "https://ingest.elu.dev/v1/replay"), ("method", "PUT"),
                                 ("count", True), ("count", 0), ("urlKind", "actual-wire-url")):
                value = protocol_fixture()
                value["requests"][index][field] = wrong
                self.reject(value)
        for mutation in (lambda x: x.pop(), lambda x: x.append(x[0]), lambda x: x.__setitem__(1, x[0])):
            value = protocol_fixture()
            mutation(value["requests"])
            self.reject(value)

    def test_duplicate_nonfinite_oversized_and_malformed_json(self):
        for data in (b'{"schemaVersion":2,"schemaVersion":2}', b'{"a":NaN}',
                     b' ' * (evidence.MAX_EVIDENCE_BYTES + 1), b'\xff', b'{'):
            with self.assertRaises((ValueError, UnicodeError)):
                evidence.validate(data, binding(data))

    def test_regular_file_reader_rejects_missing_symlink_empty_oversized_and_fifo(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            file = root / "file"
            file.write_bytes(b"1234")
            link = root / "link"
            link.symlink_to(file)
            empty = root / "empty"
            empty.touch()
            import os
            fifo = root / "fifo"
            os.mkfifo(fifo)
            for target in (root / "missing", link, empty, fifo):
                with self.assertRaises((OSError, ValueError)):
                    evidence.read_regular(target, 10)
            with self.assertRaises(ValueError):
                evidence.read_regular(file, 3)
            self.assertEqual(b"1234", evidence.read_regular(file, 4))


class PairedRuntimeNetworkEvidenceTest(unittest.TestCase):
    def reject(self, value, match="."):
        data = encoded(value)
        with self.assertRaisesRegex(ValueError, match):
            evidence.validate(data, binding(data))

    def test_two_named_frameworks_preserve_distinct_original_runs_and_eligible_cohorts(self):
        for cohort in evidence.REPLAY_COHORTS:
            value = paired_protocol_fixture()
            value["scope"]["profiles"]["declaredCompose"]["cohort"] = cohort
            data = encoded(value)
            result = evidence.validate(data, binding(data))
            profiles = result["scope"]["profiles"]
            self.assertNotEqual(profiles["views"]["run"], profiles["declaredCompose"]["run"])
            self.assertEqual(result, value)

    def test_crossed_core_and_paired_validation_never_read_candidate_paths(self):
        for value, kwargs in ((protocol_fixture(), {"distribution": pathlib.Path("not-read")}),
                              (paired_protocol_fixture(), {"aar": pathlib.Path("not-read")})):
            data = encoded(value)
            with patch.object(evidence, "read_regular", side_effect=AssertionError("no candidate read")), \
                    self.assertRaisesRegex(ValueError, "core-only"):
                evidence.validate(data, binding(data), **kwargs)
        data = encoded(paired_protocol_fixture())
        with self.assertRaisesRegex(ValueError, "choose core-only"):
            evidence.validate(data, binding(data), pathlib.Path("core"), distribution=pathlib.Path("pair"))

    def test_exact_pair_and_all_twelve_distribution_members_are_mandatory(self):
        original = paired_protocol_fixture()
        for name in original["distribution"]["files"]:
            value = copy.deepcopy(original)
            del value["distribution"]["files"][name]
            self.reject(value, "member set")
        for role in evidence.MODULES:
            for field, wrong in (("coordinate", "dev.elu:other:0.2.0"), ("sha256", "0" * 64), ("bytes", 1)):
                value = paired_protocol_fixture(); value["artifacts"][role][field] = wrong
                self.reject(value)
        for name in ("../outside", "repository/dev/elu/other/0.2.0/other.aar"):
            value = paired_protocol_fixture(); value["distribution"]["files"][name] = reference(b"extra")
            self.reject(value, "member set")

    def test_member_and_aggregate_bounds_reject_before_read(self):
        for size in (True, 0, evidence.MAX_MEMBER_BYTES + 1):
            value = paired_protocol_fixture()
            value["distribution"]["files"][next(iter(value["distribution"]["files"]))]["bytes"] = size
            self.reject(value)
        value = paired_protocol_fixture()
        for ref in value["distribution"]["files"].values(): ref["bytes"] = evidence.MAX_MEMBER_BYTES
        self.reject(value, "aggregate")

    def test_no_profile_can_be_omitted_relabelled_or_join_another_completion(self):
        for name in evidence.FRAMEWORKS:
            value = paired_protocol_fixture(); del value["scope"]["profiles"][name]
            self.reject(value, "framework profiles")
            for field, wrong in (("framework", "SwiftUI"), ("cohort", "baseline"), ("cohort", "analytics"),
                    ("sourceCommit", "0" * 40), ("coreAarSha256", "0" * 64), ("composeAarSha256", "0" * 64),
                    ("distributionSha256", "0" * 64), ("completionReceiptSha256", "0" * 64)):
                value = paired_protocol_fixture(); value["scope"]["profiles"][name][field] = wrong
                self.reject(value)
        value = paired_protocol_fixture(); value["scope"]["profile"] = "core-only"
        self.reject(value, "unsupported paired")

    def test_each_profile_requires_its_original_run_and_sanitized_evidence_references(self):
        for name in evidence.FRAMEWORKS:
            for field in ("configuration", "policy", "observations", "run"):
                value = paired_protocol_fixture(); del value["scope"]["profiles"][name][field]
                self.reject(value, "framework profile")
            for field in ("runId", "testRunId", "manifestSha256", "runSha256", "siteKeyHash"):
                value = paired_protocol_fixture(); value["scope"]["profiles"][name]["run"][field] = "bad"
                self.reject(value)
        for path in (("artifacts",), ("artifacts", "compose"), ("distribution",), ("distribution", "descriptor"),
                     ("scope",), ("scope", "profiles"), ("scope", "profiles", "views"),
                     ("scope", "profiles", "views", "run"), ("scope", "profiles", "views", "policy")):
            value = paired_protocol_fixture(); target = value
            for key in path: target = target[key]
            target["privatePath"] = "/private/do-not-export"
            self.reject(value)

    def test_existing_fifteen_proofs_remain_required_for_paired_export(self):
        for proof in evidence.PROOFS:
            value = paired_protocol_fixture(); del value["completion"]["proofs"][proof]
            self.reject(value, "required original proofs")
        value = paired_protocol_fixture(); value["completion"]["proofs"]["inventedQualification"] = reference(b"fake")
        self.reject(value, "required original proofs")

    def test_all_six_actual_cloud_route_summaries_required_without_private_urls(self):
        for index in range(6):
            value = paired_protocol_fixture(); value["requests"].pop(index)
            self.reject(value, "channels")
            for field, wrong in (("count", 0), ("count", True), ("urlKind", "actual-wire-url"),
                                 ("url", "https://elu.dev/sdk/v3/elu_pk_private/config"), ("method", "PUT")):
                value = paired_protocol_fixture(); value["requests"][index][field] = wrong
                self.reject(value)
        value = paired_protocol_fixture(); value["requests"][1] = copy.deepcopy(value["requests"][0])
        self.reject(value, "duplicate channel")
        for field, wrong in (("kind", "local"), ("tls", "private-ca"), ("ingestOrigin", "https://test.local")):
            value = paired_protocol_fixture(); value["environment"][field] = wrong
            self.reject(value)

    def test_actual_staged_pair_checks_all_original_bytes_without_promoting_descriptor(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "stage"
            value = staged_protocol_fixture(root); data = encoded(value)
            before = {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()}
            self.assertEqual(value, evidence.validate(data, binding(data), distribution=root))
            self.assertFalse(json.loads((root / "distribution.json").read_bytes())["runtimeQualified"])
            self.assertEqual(before, {str(p.relative_to(root)): p.read_bytes() for p in root.rglob("*") if p.is_file()})
            for name in value["distribution"]["files"]:
                path = root / name; original = path.read_bytes(); path.write_bytes(original + b"changed")
                with self.assertRaisesRegex(ValueError, "staged distribution differs"):
                    evidence.validate(data, binding(data), distribution=root)
                path.write_bytes(original)

    def test_stage_rejects_extra_member_symlink_directory_or_file_and_missing_member(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "stage"; value = staged_protocol_fixture(root); data = encoded(value)
            extra = root / "unexpected"; extra.write_bytes(b"extra")
            with self.assertRaisesRegex(ValueError, "unexpected"):
                evidence.validate(data, binding(data), distribution=root)
            extra.unlink()
            target = root / next(iter(value["distribution"]["files"])); original = target.read_bytes(); target.unlink()
            with self.assertRaises(OSError): evidence.validate(data, binding(data), distribution=root)
            target.symlink_to(root / "version.txt")
            with self.assertRaisesRegex(ValueError, "linked"):
                evidence.validate(data, binding(data), distribution=root)
            target.unlink(); target.write_bytes(original)
            moved = root / "repo-original"; (root / "repository").rename(moved); (root / "repository").symlink_to(moved)
            with self.assertRaises(ValueError): evidence.validate(data, binding(data), distribution=root)

    def test_descriptor_false_flag_exact_membership_and_version_are_not_qualification(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "stage"; original = staged_protocol_fixture(root)
            descriptor = root / "distribution.json"; saved = descriptor.read_bytes()
            for mutate in (lambda v: v.update(runtimeQualified=True), lambda v: v.update(version="0.1.0"),
                           lambda v: v.update(kind="completed-native-release"), lambda v: v.update(qualification=True)):
                changed = json.loads(saved); mutate(changed); raw = encoded(changed); descriptor.write_bytes(raw)
                value = copy.deepcopy(original); value["distribution"]["descriptor"] = reference(raw)
                for p in value["scope"]["profiles"].values(): p["distributionSha256"] = reference(raw)["sha256"]
                data = encoded(value)
                with self.assertRaises(ValueError): evidence.validate(data, binding(data), distribution=root)

    def test_signed_bad_dependency_metadata_still_fails_original_distribution_validator(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "stage"; value = staged_protocol_fixture(root)
            name = evidence.member_paths("0.2.0")["compose"][".pom"]
            path = root / name
            path.write_bytes(path.read_bytes().replace(b"<artifactId>elu-analytics</artifactId><version>0.2.0",
                                                       b"<artifactId>elu-analytics</artifactId><version>0.1.0"))
            descriptor = json.loads((root / "distribution.json").read_bytes())
            descriptor["files"][name] = reference(path.read_bytes())
            raw = encoded(descriptor); (root / "distribution.json").write_bytes(raw)
            value = paired_protocol_fixture(descriptor, raw); data = encoded(value)
            with self.assertRaisesRegex(ValueError, "POM exact dependency"):
                evidence.validate(data, binding(data), distribution=root)

    def test_member_modified_after_initial_read_is_detected_before_return(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory) / "stage"; value = staged_protocol_fixture(root); data = encoded(value)
            original_reader = evidence.read_regular
            changed = root / evidence.member_paths("0.2.0")["core"][".aar"]
            def read(path, maximum):
                content = original_reader(path, maximum)
                if path.name == "elu-analytics-compose-sbom.json": changed.write_bytes(b"changed after read")
                return content
            with patch.object(evidence, "read_regular", read), self.assertRaisesRegex(ValueError, "changed during validation"):
                evidence.validate(data, binding(data), distribution=root)

    def test_schema3_strict_json_and_trusted_digest_precede_distribution_reads(self):
        good = encoded(paired_protocol_fixture())
        for data in (good.replace(b'"schemaVersion":3', b'"schemaVersion":3,"schemaVersion":3', 1),
                     b'{"schemaVersion":NaN}', b'\xff', b' ' * (evidence.MAX_EVIDENCE_BYTES + 1)):
            with self.assertRaises((ValueError, UnicodeError)):
                evidence.validate(data, binding(data), distribution=pathlib.Path("never-read"))
        with self.assertRaisesRegex(ValueError, "trusted signed tag"):
            evidence.validate(good, {**binding(good), "evidenceSha256": "0" * 64}, distribution=pathlib.Path("never-read"))


if __name__ == "__main__":
    unittest.main()
