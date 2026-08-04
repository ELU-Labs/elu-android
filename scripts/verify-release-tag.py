#!/usr/bin/env python3
"""Require an exact reviewed and trusted cryptographically signed release tag."""

from __future__ import annotations

import argparse
import os
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION_SOURCE = ROOT / "elu-analytics" / "src" / "main" / "kotlin" / "dev" / "elu" / "analytics" / "EluVersion.kt"
VERSION_PATTERN = re.compile(r'const val NAME: String = "([^"]+)"')
TAG_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
REVIEW_PATTERN = re.compile(r"^Reviewed-by:\s+\S.+$", re.IGNORECASE | re.MULTILINE)
FINGERPRINT_PATTERN = re.compile(r"^[0-9A-F]{40}(?:[0-9A-F]{24})?$")
TRUSTED_FINGERPRINTS_ENV = "ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS"


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
    result = subprocess.run(
        ["git", "verify-tag", "--raw", ref],
        cwd=ROOT,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        raise SystemExit("release tag must carry a valid cryptographic signature")

    fingerprints: set[str] = set()
    for line in f"{result.stdout}\n{result.stderr}".splitlines():
        marker = "[GNUPG:] VALIDSIG "
        if marker not in line:
            continue
        fields = line.split(marker, 1)[1].split()
        if fields and FINGERPRINT_PATTERN.fullmatch(fields[0].upper()):
            fingerprints.add(fields[0].upper())
        if len(fields) > 10 and FINGERPRINT_PATTERN.fullmatch(fields[-1].upper()):
            fingerprints.add(fields[-1].upper())
    if not fingerprints:
        raise SystemExit("git verified the tag but did not report a full signer fingerprint")
    return fingerprints


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    args = parser.parse_args()
    tag = args.tag
    if not TAG_PATTERN.fullmatch(tag):
        raise SystemExit(f"release tag is not a supported semantic version: {tag}")

    match = VERSION_PATTERN.search(VERSION_SOURCE.read_text(encoding="utf-8"))
    if match is None:
        raise SystemExit("EluVersion.NAME was not found")
    version = match.group(1)
    if tag != version:
        raise SystemExit(f"tag {tag} does not match EluVersion.NAME {version}")

    ref = f"refs/tags/{tag}"
    if git("cat-file", "-t", ref) != "tag":
        raise SystemExit("release tag must be a signed tag object; lightweight tags cannot publish")
    if git("rev-parse", f"{ref}^{{commit}}") != git("rev-parse", "HEAD"):
        raise SystemExit("release tag does not point at the checked-out commit")
    message = git("for-each-ref", ref, "--format=%(contents)")
    if REVIEW_PATTERN.search(message) is None:
        raise SystemExit("signed release tag must contain a Reviewed-by: trailer")
    trusted = trusted_fingerprints()
    observed = verified_signature_fingerprints(ref)
    if trusted.isdisjoint(observed):
        raise SystemExit(
            "release tag signature is valid but its signer fingerprint is not in the trusted set"
        )
    if git("status", "--porcelain"):
        raise SystemExit("worktree changes, including untracked files, are not publishable")
    signer = sorted(trusted.intersection(observed))[0]
    print(f"reviewed signed release tag verified: {tag} ({signer})")


if __name__ == "__main__":
    main()
