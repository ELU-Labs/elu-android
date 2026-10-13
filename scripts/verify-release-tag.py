#!/usr/bin/env python3
"""Require an exact reviewed release tag signed by an explicitly trusted OpenPGP key.

The signed tag message must carry these trailers, each exactly once:

  Source-Commit: <40-hex commit the tag points at>
  CI-Run: https://github.com/ELU-Labs/elu-android/actions/runs/<run id>
  Core-AAR-SHA256: <64-hex SHA-256 of the AAR built by that CI run>
  Release-Decision: release/decisions/<tag>.md
  Reviewed-by: <reviewer>

The decision record must exist in the tagged commit.
"""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import shutil
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION_SOURCE = ROOT / "elu-analytics" / "src" / "main" / "kotlin" / "dev" / "elu" / "analytics" / "EluVersion.kt"
VERSION_PATTERN = re.compile(r'const val NAME: String = "([^"]+)"')
TAG_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z.-]+)?$")
REVIEW_PATTERN = re.compile(r"^Reviewed-by:[ \t]+\S(?:.*\S)?$", re.IGNORECASE)
FINGERPRINT_PATTERN = re.compile(r"^[0-9A-F]{40}(?:[0-9A-F]{24})?$")
TRUSTED_FINGERPRINTS_ENV = "ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS"
REPOSITORY = "ELU-Labs/elu-android"
CI_RUN_PATTERN = re.compile(r"https://github\.com/" + re.escape(REPOSITORY) + r"/actions/runs/([1-9][0-9]{0,19})")
SHA256_PATTERN = re.compile(r"[a-f0-9]{64}")
COMMIT_PATTERN = re.compile(r"[a-f0-9]{40}(?:[a-f0-9]{24})?")


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


def trusted_fingerprints() -> set[str]:
    raw = os.environ.get(TRUSTED_FINGERPRINTS_ENV, "")
    fingerprints = {item.upper() for item in re.split(r"[\s,]+", raw.strip()) if item}
    if not fingerprints:
        raise SystemExit(
            f"{TRUSTED_FINGERPRINTS_ENV} is not configured; release signing trust fails closed"
        )
    invalid = sorted(item for item in fingerprints if FINGERPRINT_PATTERN.fullmatch(item) is None)
    if invalid:
        raise SystemExit(
            f"{TRUSTED_FINGERPRINTS_ENV} must contain full 40- or 64-hex fingerprints only"
        )
    return fingerprints


def verified_signature_fingerprints(ref: str) -> set[str]:
    gpg_program = shutil.which("gpg")
    if gpg_program is None:
        raise SystemExit("GPG is required to verify a release tag")
    # Pin the OpenPGP verifier so a local SSH/X.509 signing configuration cannot apply.
    result = subprocess.run(
        ["git", "-c", "gpg.format=openpgp", "-c", f"gpg.program={gpg_program}",
         "-c", f"gpg.openpgp.program={gpg_program}", "verify-tag", "--raw", ref],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit("release tag must carry a valid cryptographic signature")

    fingerprints: set[str] = set()
    marker = "[GNUPG:] VALIDSIG "
    for line in f"{result.stdout}\n{result.stderr}".splitlines():
        if not line.startswith(marker):
            continue
        fields = line[len(marker):].split()
        if fields and FINGERPRINT_PATTERN.fullmatch(fields[0].upper()):
            fingerprints.add(fields[0].upper())
        if len(fields) >= 10 and FINGERPRINT_PATTERN.fullmatch(fields[-1].upper()):
            fingerprints.add(fields[-1].upper())
    if not fingerprints:
        raise SystemExit("git verified the tag but did not report a full signer fingerprint")
    return fingerprints


def signed_message(raw_tag: str) -> str:
    # Git/GPG can authenticate the prefix while ignoring text after ASCII armor.
    # Only the message BEFORE one final signature can supply release trailers.
    headers, separator, body = raw_tag.partition("\n\n")
    begin = "-----BEGIN PGP SIGNATURE-----"
    end = "-----END PGP SIGNATURE-----"
    lines = body.splitlines(keepends=True)
    starts = [index for index, line in enumerate(lines) if line.rstrip("\r\n") == begin]
    ends = [index for index, line in enumerate(lines) if line.rstrip("\r\n") == end]
    if not separator or not headers or len(starts) != 1 or len(ends) != 1 or starts[0] >= ends[0]:
        raise SystemExit("release tag requires exactly one final OpenPGP signature")
    if "".join(lines[ends[0] + 1:]).strip():
        raise SystemExit("unsigned text after the release signature is not permitted")
    return "".join(lines[:starts[0]])


def single_trailer(message: str, key: str) -> str:
    """Return the value of exactly one `Key: value` line in the signed message."""
    prefix = key.lower() + ":"
    values = [line for line in message.splitlines() if line.lower().startswith(prefix)]
    if len(values) != 1:
        raise SystemExit(f"signed release tag requires exactly one {key}: trailer")
    line = values[0]
    if not line.startswith(key + ": ") or line != line.strip():
        raise SystemExit(f"signed release tag has a malformed {key}: trailer")
    return line[len(key) + 2:]


def verify_release(tag: str) -> dict[str, str]:
    if not TAG_PATTERN.fullmatch(tag):
        raise SystemExit(f"release tag is not a supported semantic version: {tag}")

    matches = VERSION_PATTERN.findall(VERSION_SOURCE.read_text(encoding="utf-8"))
    if len(matches) != 1:
        raise SystemExit("expected exactly one EluVersion.NAME declaration")
    version = matches[0]
    if tag != version:
        raise SystemExit(f"tag {tag} does not match EluVersion.NAME {version}")

    ref = f"refs/tags/{tag}"
    # Resolve once, then read and verify the same immutable tag object throughout.
    try:
        tag_object = git("rev-parse", "--verify", ref)
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"release tag does not exist: {tag}") from error
    if re.fullmatch(r"[a-f0-9]{40}(?:[a-f0-9]{24})?", tag_object) is None:
        raise SystemExit("invalid release tag object identity")
    if git("cat-file", "-t", tag_object) != "tag":
        raise SystemExit("release tag must be a signed tag object; lightweight tags cannot publish")
    source_commit = git("rev-parse", f"{tag_object}^{{commit}}")
    if source_commit != git("rev-parse", "HEAD"):
        raise SystemExit("release tag does not point at the checked-out commit")
    raw_tag = git("cat-file", "tag", tag_object)
    headers: dict[str, str] = {}
    for line in raw_tag.partition("\n\n")[0].splitlines():
        key, separator, value = line.partition(" ")
        if not separator or key in headers:
            raise SystemExit("release tag requires unambiguous signed object headers")
        headers[key] = value
    if headers.get("tag") != tag:
        raise SystemExit("release tag object's signed name does not match the requested tag")
    if headers.get("type") != "commit" or headers.get("object") != source_commit:
        raise SystemExit("release tag must directly reference the checked-out commit")
    trusted = trusted_fingerprints()
    observed = verified_signature_fingerprints(tag_object)
    if trusted.isdisjoint(observed):
        raise SystemExit(
            "release tag signature is valid but its signer fingerprint is not in the trusted set"
        )
    # Parse the signed message, never an unsigned workflow input or release body.
    message = signed_message(raw_tag)
    if not any(REVIEW_PATTERN.fullmatch(line) for line in message.splitlines()):
        raise SystemExit("signed release tag must contain a Reviewed-by: trailer")
    if git("status", "--porcelain=v1", "--untracked-files=all"):
        raise SystemExit("worktree changes, including untracked files, are not publishable")

    named_commit = single_trailer(message, "Source-Commit")
    if COMMIT_PATTERN.fullmatch(named_commit) is None or named_commit != source_commit:
        raise SystemExit("Source-Commit trailer does not name the tagged commit")
    ci_run = CI_RUN_PATTERN.fullmatch(single_trailer(message, "CI-Run"))
    if ci_run is None:
        raise SystemExit(f"CI-Run trailer must be a {REPOSITORY} Actions run URL")
    core_aar = single_trailer(message, "Core-AAR-SHA256")
    if SHA256_PATTERN.fullmatch(core_aar) is None:
        raise SystemExit("Core-AAR-SHA256 trailer must be 64 lowercase hex characters")
    decision = single_trailer(message, "Release-Decision")
    if decision != f"release/decisions/{tag}.md":
        raise SystemExit(f"Release-Decision trailer must name release/decisions/{tag}.md")
    try:
        record_type = git("cat-file", "-t", f"{source_commit}:{decision}")
    except subprocess.CalledProcessError as error:
        raise SystemExit("Release-Decision record is missing from the tagged commit") from error
    if record_type != "blob":
        raise SystemExit("Release-Decision record is not a file in the tagged commit")
    if tag not in git("cat-file", "blob", f"{source_commit}:{decision}"):
        raise SystemExit("Release-Decision record does not name the tagged version")

    return {"tag": tag, "sourceCommit": source_commit, "ciRunId": ci_run.group(1),
            "coreAarSha256": core_aar, "decision": decision,
            "signer": sorted(trusted.intersection(observed))[0]}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("tag")
    args = parser.parse_args()
    binding = verify_release(args.tag)
    print(f"reviewed signed release tag verified: {binding['tag']} ({binding['signer']}); "
          f"CI run {binding['ciRunId']}, core AAR {binding['coreAarSha256']}, {binding['decision']}")


if __name__ == "__main__":
    main()
