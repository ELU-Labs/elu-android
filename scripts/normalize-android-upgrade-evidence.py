#!/usr/bin/env python3
"""Validate raw Android replacement-test output and write normalized evidence."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re

EXPECTED_TEST_CLASS = "dev.elu.analytics.upgradeevidence.UpgradeContinuityTest"
EXPECTED_INSTRUMENTATION = {
    "anonymous-published-state.instrumentation.txt": "establishPublishedAnonymousState",
    "anonymous-continuity.instrumentation.txt": "verifyAnonymousReplacementContinuity",
    "identified-published-state.instrumentation.txt": "establishPublishedIdentifiedState",
    "identified-continuity.instrumentation.txt": "verifyIdentifiedReplacementContinuity",
}
EXPECTED_INSTALL_FILES = {
    "anonymous-published-app-install.txt",
    "anonymous-published-test-install.txt",
    "anonymous-candidate-app-replace.txt",
    "anonymous-candidate-test-replace.txt",
    "identified-published-app-install.txt",
    "identified-published-test-install.txt",
    "identified-candidate-app-replace.txt",
    "identified-candidate-test-replace.txt",
}
EXPECTED_RAW_FILES = EXPECTED_INSTALL_FILES | set(EXPECTED_INSTRUMENTATION)
EXPECTED_OPERATIONS = [
    "establish-anonymous-identity",
    "replace-anonymous-install-with-candidate",
    "verify-anonymous-identity-continuity",
    "establish-identified-identity",
    "replace-identified-install-with-candidate",
    "verify-identified-identity-continuity",
]
EXPECTED_CONTINUITY = {
    "anonymousIdentityEstablished": True,
    "anonymousIdentityPreserved": True,
    "identifiedIdentityEstablished": True,
    "identifiedIdentityPreserved": True,
    "applicationDataPreserved": True,
}
EXPECTED_RAW_POLICY = {
    "custody": "ephemeral-ci-workspace",
    "status": "digested-not-retained",
}
EXPECTED_ASSERTION_KEYS = {
    "schemaVersion",
    "sourceVersion",
    "operations",
    "expectedContinuity",
    "rawEvidence",
}
BASELINE_AAR_SHA256 = "aeab6cede8da582626505b019a5dc1574d06241b1f1905583ac8e3922d215b8d"
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
FAILURE_MARKER = re.compile(
    r"FAILURES!!!|FATAL EXCEPTION|INSTRUMENTATION_RESULT: shortMsg=|"
    r"INSTRUMENTATION_(?:FAILED|ABORTED)|"
    r"\b(?:failure|failed|abort|aborted|crash|crashed)\b",
    flags=re.IGNORECASE,
)


def sha256_file(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def digest_raw_files(raw_dir: pathlib.Path) -> str:
    digest = hashlib.sha256()
    for name in sorted(EXPECTED_RAW_FILES):
        data = (raw_dir / name).read_bytes()
        digest.update(name.encode("utf-8"))
        digest.update(b"\0")
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    return digest.hexdigest()


def validate_instrumentation(name: str, text: str, expected_method: str) -> None:
    if FAILURE_MARKER.search(text):
        raise SystemExit(f"instrumentation transcript contains a failure marker: {name}")

    classes = re.findall(r"^INSTRUMENTATION_STATUS: class=(.+)$", text, flags=re.MULTILINE)
    methods = re.findall(r"^INSTRUMENTATION_STATUS: test=(.+)$", text, flags=re.MULTILINE)
    status_codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$", text, flags=re.MULTILINE)
    terminal_codes = re.findall(r"^INSTRUMENTATION_CODE: (-?\d+)$", text, flags=re.MULTILINE)
    ok_footers = re.findall(r"^OK \(1 test\)$", text, flags=re.MULTILINE)

    if classes != [EXPECTED_TEST_CLASS, EXPECTED_TEST_CLASS]:
        raise SystemExit(f"instrumentation class did not match exactly once: {name}")
    if methods != [expected_method, expected_method]:
        raise SystemExit(f"instrumentation method did not match exactly once: {name}")
    if status_codes != ["1", "0"]:
        raise SystemExit(f"instrumentation status sequence was not start/pass: {name}")
    if terminal_codes != ["-1"] or len(ok_footers) != 1:
        raise SystemExit(f"instrumentation did not have one successful terminal result: {name}")


def validate_raw_files(raw_dir: pathlib.Path) -> None:
    if not raw_dir.is_dir():
        raise SystemExit("raw upgrade evidence directory is missing")
    observed_names = {entry.name for entry in raw_dir.iterdir()}
    if observed_names != EXPECTED_RAW_FILES:
        raise SystemExit("raw upgrade evidence file set does not match exactly")
    for name in sorted(EXPECTED_RAW_FILES):
        path = raw_dir / name
        if not path.is_file() or path.is_symlink():
            raise SystemExit(f"raw upgrade evidence is missing: {name}")
        text = path.read_text(encoding="utf-8", errors="replace")
        if name in EXPECTED_INSTALL_FILES:
            lines = text.splitlines()
            if not lines or lines[-1] != "Success" or lines.count("Success") != 1:
                raise SystemExit(f"replacement install did not succeed: {name}")
            if FAILURE_MARKER.search(text):
                raise SystemExit(f"replacement install contains a failure marker: {name}")
        if name in EXPECTED_INSTRUMENTATION:
            validate_instrumentation(name, text, EXPECTED_INSTRUMENTATION[name])


def validate_assertions(path: pathlib.Path, source_version: str) -> None:
    assertions = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(assertions, dict) or set(assertions) != EXPECTED_ASSERTION_KEYS:
        raise SystemExit("upgrade assertions must contain exactly the reviewed contract fields")
    if type(assertions["schemaVersion"]) is not int or assertions["schemaVersion"] != 1:
        raise SystemExit("upgrade assertions must use schemaVersion 1")
    if not isinstance(assertions["sourceVersion"], str) or assertions["sourceVersion"] != source_version:
        raise SystemExit("upgrade assertions do not match the source version")
    if assertions["operations"] != EXPECTED_OPERATIONS:
        raise SystemExit("upgrade assertions operations do not match the reviewed order")
    continuity = assertions["expectedContinuity"]
    if (
        not isinstance(continuity, dict)
        or set(continuity) != set(EXPECTED_CONTINUITY)
        or any(value is not True for value in continuity.values())
    ):
        raise SystemExit("upgrade assertions continuity claims must be exact and true")
    if assertions["rawEvidence"] != EXPECTED_RAW_POLICY:
        raise SystemExit("upgrade assertions raw evidence policy does not match")


def validate_selection_report(
    path: pathlib.Path,
    *,
    selection: str,
    source_version: str,
    fixture_apk: pathlib.Path,
) -> dict[str, object]:
    report = json.loads(path.read_text(encoding="utf-8"))
    expected_keys = {
        "schemaVersion",
        "selection",
        "sourceVersion",
        "resolvedKind",
        "resolvedIdentity",
        "baselineArtifactSha256",
        "fixtureApkSha256",
    }
    if not isinstance(report, dict) or set(report) != expected_keys:
        raise SystemExit(f"{selection} dependency selection report fields do not match")
    if type(report["schemaVersion"]) is not int:
        raise SystemExit(f"{selection} dependency selection schemaVersion must be an integer")

    expected_static: dict[str, object]
    if selection == "published":
        expected_static = {
            "schemaVersion": 1,
            "selection": "published",
            "sourceVersion": source_version,
            "resolvedKind": "module",
            "resolvedIdentity": f"dev.elu:elu-analytics:{source_version}",
            "baselineArtifactSha256": BASELINE_AAR_SHA256,
        }
    elif selection == "candidate":
        expected_static = {
            "schemaVersion": 1,
            "selection": "candidate",
            "sourceVersion": source_version,
            "resolvedKind": "project",
            "resolvedIdentity": ":elu-analytics",
            "baselineArtifactSha256": None,
        }
    else:
        raise AssertionError(f"unsupported dependency selection: {selection}")

    for key, expected in expected_static.items():
        if report[key] != expected:
            raise SystemExit(f"{selection} dependency selection mismatch for {key}")
    apk_digest = sha256_file(fixture_apk)
    if report["fixtureApkSha256"] != apk_digest:
        raise SystemExit(f"{selection} dependency selection report is not bound to its fixture APK")
    return report


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--raw-dir", required=True, type=pathlib.Path)
    parser.add_argument("--output", required=True, type=pathlib.Path)
    parser.add_argument("--digest-output", required=True, type=pathlib.Path)
    parser.add_argument("--source-version", required=True)
    parser.add_argument("--candidate-revision", required=True)
    parser.add_argument("--api-level", required=True, type=int)
    parser.add_argument("--assertions", required=True, type=pathlib.Path)
    parser.add_argument("--published-selection-report", required=True, type=pathlib.Path)
    parser.add_argument("--candidate-selection-report", required=True, type=pathlib.Path)
    parser.add_argument("--published-apk", required=True, type=pathlib.Path)
    parser.add_argument("--candidate-apk", required=True, type=pathlib.Path)
    args = parser.parse_args()

    if args.api_level < 23:
        raise SystemExit("upgrade evidence requires Android API 23 or newer")
    if not args.source_version or not args.candidate_revision:
        raise SystemExit("source version and candidate revision must be non-empty")
    for apk in (args.published_apk, args.candidate_apk):
        if not apk.is_file():
            raise SystemExit(f"upgrade APK is missing: {apk}")

    validate_assertions(args.assertions, args.source_version)
    published_selection = validate_selection_report(
        args.published_selection_report,
        selection="published",
        source_version=args.source_version,
        fixture_apk=args.published_apk,
    )
    candidate_selection = validate_selection_report(
        args.candidate_selection_report,
        selection="candidate",
        source_version=args.source_version,
        fixture_apk=args.candidate_apk,
    )
    if (
        published_selection["resolvedKind"],
        published_selection["resolvedIdentity"],
    ) == (
        candidate_selection["resolvedKind"],
        candidate_selection["resolvedIdentity"],
    ):
        raise SystemExit("published and candidate dependency selections are not distinct")

    validate_raw_files(args.raw_dir)
    raw_digest = digest_raw_files(args.raw_dir)
    if SHA256_PATTERN.fullmatch(raw_digest) is None:
        raise SystemExit("raw evidence digest is invalid")

    manifest = {
        "schemaVersion": 1,
        "sourceVersion": args.source_version,
        "candidateRevision": args.candidate_revision,
        "environment": {
            "platform": "android",
            "apiLevel": args.api_level,
            "applicationId": "dev.elu.analytics.upgradeevidence",
            "installMode": "replace-preserving-application-data",
        },
        "operations": [{"name": name, "status": "passed"} for name in EXPECTED_OPERATIONS],
        "expectedContinuity": EXPECTED_CONTINUITY,
        "dependencySelection": {
            "published": published_selection,
            "candidate": candidate_selection,
            "distinctionBasis": "resolved-component-kind-and-identity",
        },
        "artifacts": {
            "assertionsSha256": sha256_file(args.assertions),
            "publishedSelectionReportSha256": sha256_file(args.published_selection_report),
            "candidateSelectionReportSha256": sha256_file(args.candidate_selection_report),
            "publishedApkSha256": sha256_file(args.published_apk),
            "candidateApkSha256": sha256_file(args.candidate_apk),
        },
        "rawEvidence": {
            "sha256": raw_digest,
            "custody": EXPECTED_RAW_POLICY["custody"],
            "status": EXPECTED_RAW_POLICY["status"],
        },
        "verificationStatus": "passed",
        "blockers": [],
    }

    args.output.parent.mkdir(parents=True, exist_ok=True)
    serialized = json.dumps(manifest, indent=2, sort_keys=False) + "\n"
    args.output.write_text(serialized, encoding="utf-8")
    args.digest_output.write_text(
        hashlib.sha256(serialized.encode("utf-8")).hexdigest() + "\n",
        encoding="utf-8",
    )
    print(f"Android {args.source_version} replacement continuity evidence passed")


if __name__ == "__main__":
    main()
