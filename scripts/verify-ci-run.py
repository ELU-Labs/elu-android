#!/usr/bin/env python3
"""Require the CI run named in the signed release tag to be green on the tagged commit.

The run must be this repository's CI workflow, triggered by a push to main, for
exactly the tagged commit, completed successfully with every job successful.
Its retained `android-sdk-reports` artifact must contain the core AAR whose
SHA-256 the tag names. Reads GitHub with the `gh` CLI and GH_TOKEN; never writes.
"""

from __future__ import annotations

import argparse
import hashlib
import importlib.util
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
REPOSITORY = "ELU-Labs/elu-android"
WORKFLOW_PATH = ".github/workflows/ci.yml"
REQUIRED_JOB = "Android SDK checks"
ARTIFACT = "android-sdk-reports"
CORE_AAR = pathlib.PurePosixPath("elu-analytics/build/outputs/aar/elu-analytics-release.aar")
MAX_AAR_BYTES = 64 * 1024 * 1024


def load_tag_verifier():
    spec = importlib.util.spec_from_file_location("android_release_tag", ROOT / "scripts" / "verify-release-tag.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def gh_json(path: str) -> dict:
    result = subprocess.run(["gh", "api", "-H", "Accept: application/vnd.github+json", path],
                            check=False, capture_output=True, text=True, timeout=60)
    if result.returncode != 0:
        raise SystemExit(f"GitHub read failed for {path}")
    value = json.loads(result.stdout)
    if not isinstance(value, dict):
        raise SystemExit(f"unexpected GitHub response for {path}")
    return value


def check_run(run: dict, run_id: str, source_commit: str) -> None:
    expected = {
        "id": int(run_id),
        "head_sha": source_commit,
        "path": WORKFLOW_PATH,
        "event": "push",
        "head_branch": "main",
        "status": "completed",
        "conclusion": "success",
    }
    for key, value in expected.items():
        if run.get(key) != value:
            raise SystemExit(f"CI run {run_id} {key} is {run.get(key)!r}, expected {value!r}")
    for key in ("repository", "head_repository"):
        if (run.get(key) or {}).get("full_name") != REPOSITORY:
            raise SystemExit(f"CI run {run_id} does not belong to {REPOSITORY}")


def check_jobs(jobs: dict, run_id: str) -> None:
    entries = jobs.get("jobs")
    if not isinstance(entries, list) or not entries or jobs.get("total_count") != len(entries):
        raise SystemExit(f"CI run {run_id} job list is incomplete")
    names = set()
    for job in entries:
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            raise SystemExit(f"CI run {run_id} job {job.get('name')!r} did not succeed")
        names.add(job.get("name"))
    if REQUIRED_JOB not in names:
        raise SystemExit(f"CI run {run_id} has no {REQUIRED_JOB!r} job")


def check_artifact(directory: pathlib.Path, expected_sha256: str) -> None:
    aar = directory.joinpath(*CORE_AAR.parts)
    if aar.is_symlink() or not aar.is_file() or not 0 < aar.stat().st_size <= MAX_AAR_BYTES:
        raise SystemExit(f"{ARTIFACT} does not contain {CORE_AAR}")
    digest = hashlib.sha256(aar.read_bytes()).hexdigest()
    if digest != expected_sha256:
        raise SystemExit(f"CI-built core AAR is {digest}, but the signed tag names {expected_sha256}")


def download_artifact(run_id: str, destination: pathlib.Path) -> None:
    result = subprocess.run(["gh", "run", "download", run_id, "-R", REPOSITORY, "-n", ARTIFACT,
                             "-D", str(destination)],
                            check=False, capture_output=True, text=True, timeout=300)
    if result.returncode != 0:
        raise SystemExit(f"could not download {ARTIFACT} from CI run {run_id}")


def verify(tag: str) -> dict[str, str]:
    binding = load_tag_verifier().verify_release(tag)
    run_id = binding["ciRunId"]
    check_run(gh_json(f"repos/{REPOSITORY}/actions/runs/{run_id}"), run_id, binding["sourceCommit"])
    check_jobs(gh_json(f"repos/{REPOSITORY}/actions/runs/{run_id}/jobs?filter=latest&per_page=100"), run_id)
    with tempfile.TemporaryDirectory(prefix="elu-ci-run-") as directory:
        download_artifact(run_id, pathlib.Path(directory))
        check_artifact(pathlib.Path(directory), binding["coreAarSha256"])
    return binding


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag")
    args = parser.parse_args()
    binding = verify(args.tag)
    print(f"CI run {binding['ciRunId']} passed on {binding['sourceCommit']} and built core AAR "
          f"{binding['coreAarSha256']}")


if __name__ == "__main__":
    main()
