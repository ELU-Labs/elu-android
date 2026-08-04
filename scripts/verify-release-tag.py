#!/usr/bin/env python3
"""Require an exact annotated, reviewed release tag matching the SDK version."""

from __future__ import annotations

import argparse
import pathlib
import re
import subprocess

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION_SOURCE = ROOT / "elu-analytics" / "src" / "main" / "kotlin" / "dev" / "elu" / "analytics" / "EluVersion.kt"
VERSION_PATTERN = re.compile(r'const val NAME: String = "([^"]+)"')
TAG_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(?:[-+][0-9A-Za-z.-]+)?$")
REVIEW_PATTERN = re.compile(r"^Reviewed-by:\s+\S.+$", re.IGNORECASE | re.MULTILINE)


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()


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
        raise SystemExit("release tag must be annotated; lightweight tags cannot publish")
    if git("rev-parse", f"{ref}^{{commit}}") != git("rev-parse", "HEAD"):
        raise SystemExit("release tag does not point at the checked-out commit")
    message = git("for-each-ref", ref, "--format=%(contents)")
    if REVIEW_PATTERN.search(message) is None:
        raise SystemExit("annotated release tag must contain a Reviewed-by: trailer")
    if git("status", "--porcelain", "--untracked-files=no"):
        raise SystemExit("tracked worktree changes are not publishable")
    print(f"reviewed release tag verified: {tag}")


if __name__ == "__main__":
    main()
