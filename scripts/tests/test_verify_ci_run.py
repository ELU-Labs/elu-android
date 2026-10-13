from __future__ import annotations

import hashlib
import importlib.util
import pathlib
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("verify_ci_run", pathlib.Path(__file__).parents[1] / "verify-ci-run.py")
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)
COMMIT = "c" * 40
AAR_BYTES = b"core aar bytes"
AAR_SHA = hashlib.sha256(AAR_BYTES).hexdigest()


def good_run() -> dict:
    return {"id": 42, "head_sha": COMMIT, "path": ".github/workflows/ci.yml", "event": "push",
            "head_branch": "main", "status": "completed", "conclusion": "success",
            "repository": {"full_name": "ELU-Labs/elu-android"},
            "head_repository": {"full_name": "ELU-Labs/elu-android"}}


def good_jobs() -> dict:
    names = ("Android SDK checks", "Android owned runtime instrumentation (API 29)",
             "Android owned runtime instrumentation (API 35)")
    return {"total_count": 3, "jobs": [{"name": name, "status": "completed", "conclusion": "success"} for name in names]}


class VerifyCiRunTest(unittest.TestCase):
    def test_accepts_green_push_run_on_main_for_the_tagged_commit(self) -> None:
        CI.check_run(good_run(), "42", COMMIT)
        CI.check_jobs(good_jobs(), "42")

    def test_rejects_any_run_mismatch(self) -> None:
        cases = {
            "id": 43, "head_sha": "d" * 40, "path": ".github/workflows/release.yml", "event": "pull_request",
            "head_branch": "sdk/owned-android-runtime", "status": "in_progress", "conclusion": "failure",
        }
        for key, value in cases.items():
            with self.subTest(key=key):
                run = good_run(); run[key] = value
                with self.assertRaisesRegex(SystemExit, key):
                    CI.check_run(run, "42", COMMIT)
        for key in ("repository", "head_repository"):
            with self.subTest(key=key):
                run = good_run(); run[key] = {"full_name": "someone/fork"}
                with self.assertRaisesRegex(SystemExit, "does not belong"):
                    CI.check_run(run, "42", COMMIT)

    def test_rejects_failed_skipped_missing_or_incomplete_jobs(self) -> None:
        for conclusion in ("failure", "skipped", "cancelled"):
            with self.subTest(conclusion=conclusion):
                jobs = good_jobs(); jobs["jobs"][1]["conclusion"] = conclusion
                with self.assertRaisesRegex(SystemExit, "did not succeed"):
                    CI.check_jobs(jobs, "42")
        jobs = good_jobs(); jobs["jobs"] = jobs["jobs"][1:]; jobs["total_count"] = 2
        with self.assertRaisesRegex(SystemExit, "Android SDK checks"):
            CI.check_jobs(jobs, "42")
        jobs = good_jobs(); jobs["total_count"] = 120
        with self.assertRaisesRegex(SystemExit, "incomplete"):
            CI.check_jobs(jobs, "42")

    def test_artifact_aar_must_match_the_signed_tag(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            with self.assertRaisesRegex(SystemExit, "does not contain"):
                CI.check_artifact(root, AAR_SHA)
            aar = root / "elu-analytics/build/outputs/aar/elu-analytics-release.aar"
            aar.parent.mkdir(parents=True)
            aar.write_bytes(AAR_BYTES)
            CI.check_artifact(root, AAR_SHA)
            with self.assertRaisesRegex(SystemExit, "signed tag names"):
                CI.check_artifact(root, "0" * 64)

    def test_verify_reads_the_run_named_by_the_signed_tag(self) -> None:
        binding = {"tag": "0.2.0", "sourceCommit": COMMIT, "ciRunId": "42", "coreAarSha256": AAR_SHA}
        paths = []

        class Tag:
            @staticmethod
            def verify_release(tag):
                self.assertEqual(tag, "0.2.0")
                return binding

        def gh_json(path):
            paths.append(path)
            return good_jobs() if path.endswith("per_page=100") else good_run()

        def download(run_id, destination):
            self.assertEqual(run_id, "42")
            aar = destination / "elu-analytics/build/outputs/aar/elu-analytics-release.aar"
            aar.parent.mkdir(parents=True)
            aar.write_bytes(AAR_BYTES)

        with patch.object(CI, "load_tag_verifier", return_value=Tag), patch.object(CI, "gh_json", side_effect=gh_json), \
             patch.object(CI, "download_artifact", side_effect=download):
            self.assertEqual(CI.verify("0.2.0"), binding)
        self.assertEqual(paths, ["repos/ELU-Labs/elu-android/actions/runs/42",
                                 "repos/ELU-Labs/elu-android/actions/runs/42/jobs?filter=latest&per_page=100"])


if __name__ == "__main__":
    unittest.main()
