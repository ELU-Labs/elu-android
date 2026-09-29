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


def verify_release(tag: str) -> dict[str, str]:
    if not TAG_PATTERN.fullmatch(tag):
        raise SystemExit(f"release tag is not a supported semantic version: {tag}")

    match = VERSION_PATTERN.search(VERSION_SOURCE.read_text(encoding="utf-8"))
    if match is None:
        raise SystemExit("EluVersion.NAME was not found")
    version = match.group(1)
    if tag != version:
        raise SystemExit(f"tag {tag} does not match EluVersion.NAME {version}")

    ref = f"refs/tags/{tag}"
    # Resolve once, then read and verify the same immutable tag object throughout.
    tag_object = git("rev-parse", "--verify", ref)
    if re.fullmatch(r"[a-f0-9]{40}(?:[a-f0-9]{24})?", tag_object) is None:
        raise SystemExit("invalid release tag object identity")
    if git("cat-file", "-t", tag_object) != "tag":
        raise SystemExit("release tag must be a signed tag object; lightweight tags cannot publish")
    source_commit = git("rev-parse", f"{tag_object}^{{commit}}")
    if source_commit != git("rev-parse", "HEAD"):
        raise SystemExit("release tag does not point at the checked-out commit")
    raw_tag = git("cat-file", "tag", tag_object)
    trusted = trusted_fingerprints()
    observed = verified_signature_fingerprints(tag_object)
    if trusted.isdisjoint(observed):
        raise SystemExit(
            "release tag signature is valid but its signer fingerprint is not in the trusted set"
        )
    message = signed_message(raw_tag)
    if REVIEW_PATTERN.search(message) is None:
        raise SystemExit("signed release tag must contain a Reviewed-by: trailer")
    if git("status", "--porcelain"):
        raise SystemExit("worktree changes, including untracked files, are not publishable")
    # Parse the signed message, never an unsigned workflow input or release body.
    trailers = [line for line in message.splitlines()
                if line.lower().startswith("android-lab-evidence-sha256:")]
    if len(trailers) != 1 or re.fullmatch(
        r"Android-Lab-Evidence-SHA256: [a-f0-9]{64}", trailers[0]
    ) is None:
        raise SystemExit("signed tag requires exactly one Android-Lab-Evidence-SHA256 trailer")
    return {"tag": tag, "sourceCommit": source_commit,
            "evidenceSha256": trailers[0].split(": ", 1)[1],
            "signer": sorted(trusted.intersection(observed))[0]}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    args = parser.parse_args()
    binding = verify_release(args.tag)
    print(f"reviewed signed release tag verified: {binding['tag']} ({binding['signer']})")


if __name__ == "__main__":
    main()
