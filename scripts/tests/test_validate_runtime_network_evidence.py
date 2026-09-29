from __future__ import annotations

import copy
import hashlib
import importlib.util
import json
import pathlib
import tempfile
import unittest

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


if __name__ == "__main__":
    unittest.main()
