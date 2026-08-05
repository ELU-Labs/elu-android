from __future__ import annotations

import hashlib
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "normalize-android-upgrade-evidence.py"
TEST_CLASS = "dev.elu.analytics.upgradeevidence.UpgradeContinuityTest"
BASELINE_AAR_SHA256 = "aeab6cede8da582626505b019a5dc1574d06241b1f1905583ac8e3922d215b8d"
INSTRUMENTATION_FILES = {
    "anonymous-published-state.instrumentation.txt": "establishPublishedAnonymousState",
    "anonymous-published-rehydration.instrumentation.txt": "verifyPublishedAnonymousRehydration",
    "anonymous-continuity.instrumentation.txt": "verifyAnonymousReplacementContinuity",
    "identified-published-state.instrumentation.txt": "establishPublishedIdentifiedState",
    "identified-published-rehydration.instrumentation.txt": "verifyPublishedIdentifiedRehydration",
    "identified-continuity.instrumentation.txt": "verifyIdentifiedReplacementContinuity",
}
INSTALL_FILES = {
    "anonymous-published-app-install.txt",
    "anonymous-published-test-install.txt",
    "anonymous-candidate-app-replace.txt",
    "anonymous-candidate-test-replace.txt",
    "identified-published-app-install.txt",
    "identified-published-test-install.txt",
    "identified-candidate-app-replace.txt",
    "identified-candidate-test-replace.txt",
}


def successful_instrumentation(method: str) -> str:
    return f"""INSTRUMENTATION_STATUS: class={TEST_CLASS}
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=
{TEST_CLASS}:
INSTRUMENTATION_STATUS: test={method}
INSTRUMENTATION_STATUS_CODE: 1
INSTRUMENTATION_STATUS: class={TEST_CLASS}
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: stream=.
INSTRUMENTATION_STATUS: test={method}
INSTRUMENTATION_STATUS_CODE: 0
INSTRUMENTATION_RESULT: stream=

Time: 0.123

OK (1 test)

INSTRUMENTATION_CODE: -1
"""


class NormalizeAndroidUpgradeEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="elu-upgrade-evidence-test-")
        self.root = pathlib.Path(self.temp.name)
        self.raw = self.root / "raw"
        self.raw.mkdir()
        for name in INSTALL_FILES:
            (self.raw / name).write_text("Performing Streamed Install\nSuccess\n", encoding="utf-8")
        for name, method in INSTRUMENTATION_FILES.items():
            (self.raw / name).write_text(successful_instrumentation(method), encoding="utf-8")
        self.published_apk = self.root / "published.apk"
        self.candidate_apk = self.root / "candidate.apk"
        self.published_apk.write_bytes(b"published")
        self.candidate_apk.write_bytes(b"candidate")
        self.assertions = self.root / "assertions.json"
        self.assertions.write_bytes(
            (ROOT / "upgrade-evidence" / "0.1.0" / "assertions.json").read_bytes()
        )
        self.published_selection = self.root / "published-selection.json"
        self.candidate_selection = self.root / "candidate-selection.json"
        self.write_selection_reports()

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_normalizer(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                "--raw-dir",
                str(self.raw),
                "--output",
                str(self.root / "manifest.json"),
                "--digest-output",
                str(self.root / "manifest.sha256"),
                "--source-version",
                "0.1.0",
                "--candidate-revision",
                "abc123",
                "--api-level",
                "35",
                "--assertions",
                str(self.assertions),
                "--published-selection-report",
                str(self.published_selection),
                "--candidate-selection-report",
                str(self.candidate_selection),
                "--published-apk",
                str(self.published_apk),
                "--candidate-apk",
                str(self.candidate_apk),
            ],
            capture_output=True,
            text=True,
        )

    def write_selection_reports(self) -> None:
        self.published_selection.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "selection": "published",
                    "sourceVersion": "0.1.0",
                    "resolvedKind": "module",
                    "resolvedIdentity": "dev.elu:elu-analytics:0.1.0",
                    "baselineArtifactSha256": BASELINE_AAR_SHA256,
                    "fixtureApkSha256": hashlib.sha256(self.published_apk.read_bytes()).hexdigest(),
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
        self.candidate_selection.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "selection": "candidate",
                    "sourceVersion": "0.1.0",
                    "resolvedKind": "project",
                    "resolvedIdentity": ":elu-analytics",
                    "baselineArtifactSha256": None,
                    "fixtureApkSha256": hashlib.sha256(self.candidate_apk.read_bytes()).hexdigest(),
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )

    def test_writes_normalized_pass_manifest_and_digest(self) -> None:
        result = self.run_normalizer()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        manifest = json.loads((self.root / "manifest.json").read_text(encoding="utf-8"))
        self.assertEqual("0.1.0", manifest["sourceVersion"])
        self.assertEqual("passed", manifest["verificationStatus"])
        self.assertTrue(manifest["expectedContinuity"]["anonymousIdentityPreserved"])
        self.assertEqual(
            "project",
            manifest["dependencySelection"]["candidate"]["resolvedKind"],
        )
        self.assertEqual("ephemeral-ci-workspace", manifest["rawEvidence"]["custody"])
        self.assertEqual("digested-not-retained", manifest["rawEvidence"]["status"])
        self.assertEqual([], manifest["blockers"])
        self.assertRegex(
            (self.root / "manifest.sha256").read_text(encoding="utf-8").strip(),
            r"^[0-9a-f]{64}$",
        )

    def test_rejects_failure_abort_and_crash_markers_even_with_ok_footer(self) -> None:
        path = self.raw / "identified-continuity.instrumentation.txt"
        transcript = successful_instrumentation("verifyIdentifiedReplacementContinuity")
        cases = (
            ("FAILURES!!!", "TEST_FAILURE_UNCLASSIFIED"),
            ("INSTRUMENTATION_ABORTED", "RUNNER_ABORTED"),
            ("Instrumentation crashed.", "RUNNER_CRASHED"),
        )
        for marker, failure_code in cases:
            with self.subTest(marker=marker):
                path.write_text(transcript + marker + "\n", encoding="utf-8")
                result = self.run_normalizer()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(f"failureCode={failure_code}", result.stdout + result.stderr)

    def test_classifies_known_assertion_without_exposing_values(self) -> None:
        path = self.raw / "identified-continuity.instrumentation.txt"
        transcript = successful_instrumentation("verifyIdentifiedReplacementContinuity")
        path.write_text(
            transcript
            + "candidate SDK did not continue the identified identity\n"
            + "expected:<private-value-sentinel> but was:<different-value-sentinel>\n"
            + "FAILURES!!!\n",
            encoding="utf-8",
        )
        result = self.run_normalizer()
        combined = result.stdout + result.stderr
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failureCode=CANDIDATE_IDENTIFIED_CONTINUITY_FAILED", combined)
        self.assertNotIn("private-value-sentinel", combined)
        self.assertNotIn("different-value-sentinel", combined)

    def test_rejects_wrong_instrumentation_method(self) -> None:
        (self.raw / "anonymous-continuity.instrumentation.txt").write_text(
            successful_instrumentation("establishPublishedAnonymousState"),
            encoding="utf-8",
        )
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failureCode=TRANSCRIPT_METHOD_MISMATCH", result.stdout + result.stderr)

    def test_rejects_wrong_instrumentation_class(self) -> None:
        path = self.raw / "anonymous-continuity.instrumentation.txt"
        transcript = successful_instrumentation("verifyAnonymousReplacementContinuity")
        path.write_text(transcript.replace(TEST_CLASS, "example.WrongTest"), encoding="utf-8")
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failureCode=TRANSCRIPT_CLASS_MISMATCH", result.stdout + result.stderr)

    def test_rejects_duplicate_instrumentation_transcript(self) -> None:
        path = self.raw / "anonymous-continuity.instrumentation.txt"
        transcript = successful_instrumentation("verifyAnonymousReplacementContinuity")
        path.write_text(transcript + transcript, encoding="utf-8")
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("failureCode=TRANSCRIPT_CLASS_MISMATCH", result.stdout + result.stderr)

    def test_rejects_unexpected_instrumentation_transcript(self) -> None:
        (self.raw / "unexpected.instrumentation.txt").write_text(
            successful_instrumentation("verifyAnonymousReplacementContinuity"),
            encoding="utf-8",
        )
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("file set does not match exactly", result.stdout + result.stderr)

    def test_rejects_failed_replacement_install(self) -> None:
        (self.raw / "identified-candidate-app-replace.txt").write_text(
            "Failure\n",
            encoding="utf-8",
        )
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("replacement install did not succeed", result.stdout + result.stderr)

    def test_rejects_non_exact_assertion_contract(self) -> None:
        original = json.loads(self.assertions.read_text(encoding="utf-8"))
        mutations = [
            lambda data: data["operations"].reverse(),
            lambda data: data["expectedContinuity"].__setitem__(
                "anonymousIdentityPreserved", False
            ),
            lambda data: data["expectedContinuity"].__setitem__(
                "anonymousIdentityPreserved", 1
            ),
            lambda data: data["expectedContinuity"].pop("applicationDataPreserved"),
            lambda data: data["expectedContinuity"].__setitem__("unsupportedClaim", True),
            lambda data: data["rawEvidence"].__setitem__("status", "retained"),
            lambda data: data.__setitem__("extra", True),
        ]
        for mutation in mutations:
            with self.subTest(mutation=mutation):
                data = json.loads(json.dumps(original))
                mutation(data)
                self.assertions.write_text(json.dumps(data), encoding="utf-8")
                result = self.run_normalizer()
                self.assertNotEqual(0, result.returncode)
        self.assertions.write_text(json.dumps(original), encoding="utf-8")

    def test_rejects_selection_report_mismatch(self) -> None:
        report = json.loads(self.candidate_selection.read_text(encoding="utf-8"))
        report["resolvedKind"] = "module"
        self.candidate_selection.write_text(json.dumps(report), encoding="utf-8")
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("candidate dependency selection mismatch", result.stdout + result.stderr)

    def test_rejects_selection_report_not_bound_to_apk(self) -> None:
        self.candidate_apk.write_bytes(b"different-candidate")
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("not bound to its fixture APK", result.stdout + result.stderr)

    def test_rejects_published_baseline_digest_mismatch(self) -> None:
        report = json.loads(self.published_selection.read_text(encoding="utf-8"))
        report["baselineArtifactSha256"] = "0" * 64
        self.published_selection.write_text(json.dumps(report), encoding="utf-8")
        result = self.run_normalizer()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("published dependency selection mismatch", result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
