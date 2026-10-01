#!/usr/bin/env python3
"""Read one bounded same-repository draft asset pinned by a trusted signed tag."""
from __future__ import annotations

import argparse
import hashlib
import http.client
import importlib.util
import json
import os
import pathlib
import ssl
import time
from urllib.parse import quote, urlsplit

spec = importlib.util.spec_from_file_location("android_release_evidence", pathlib.Path(__file__).with_name("android-release-evidence.py"))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)
API = f"https://api.github.com/repos/{evidence.REPOSITORY}"
MAX_METADATA_BYTES = 1_048_576
ASSET_HOSTS = {"release-assets.githubusercontent.com", "objects.githubusercontent.com"}


class GitHubReader:
    def __init__(self, token: str):
        evidence.require(bool(token) and "\n" not in token and "\r" not in token, "GitHub read token required")
        self.token = token
        self.deadline = time.monotonic() + 90

    def __call__(self, url: str, *, binary: bool = False) -> bytes:
        maximum = evidence.MAX_EVIDENCE_BYTES if binary else MAX_METADATA_BYTES
        # No ambient proxy, caller URL, or Authorization forwarding to object storage.
        for redirect in range(2):
            parsed = urlsplit(url)
            allowed = parsed.hostname == "api.github.com" if redirect == 0 else binary and parsed.hostname in ASSET_HOSTS
            evidence.require(allowed and parsed.scheme == "https" and parsed.port in (None, 443)
                             and parsed.username is None and parsed.password is None and not parsed.fragment,
                             "unexpected GitHub asset origin")
            remaining = self.deadline - time.monotonic()
            evidence.require(remaining > 0, "GitHub acquisition deadline exceeded")
            connection = http.client.HTTPSConnection(parsed.hostname, timeout=min(10, remaining), context=ssl.create_default_context())
            try:
                headers = {"Accept": "application/octet-stream" if binary else "application/vnd.github+json",
                           "User-Agent": "elu-android-release-evidence", "X-GitHub-Api-Version": "2022-11-28",
                           "Accept-Encoding": "identity"}
                if redirect == 0:
                    headers["Authorization"] = "Bearer " + self.token
                connection.request("GET", parsed.path + ("?" + parsed.query if parsed.query else ""), headers=headers)
                response = connection.getresponse()
                if response.status == 302 and binary and redirect == 0:
                    url = response.getheader("Location", "")
                    continue
                evidence.require(response.status == 200, "GitHub evidence fetch failed")
                evidence.require(response.getheader("Content-Encoding", "identity") == "identity", "encoded asset refused")
                length = response.getheader("Content-Length")
                if length is not None:
                    evidence.require(length.isdigit() and 0 < int(length) <= maximum, "GitHub response size exceeds bound")
                chunks = bytearray()
                while True:
                    remaining = self.deadline - time.monotonic()
                    evidence.require(remaining > 0, "GitHub acquisition deadline exceeded")
                    # read1 returns after one socket read, so a trickled body cannot
                    # extend the total deadline by filling an entire large read.
                    if connection.sock is not None:
                        connection.sock.settimeout(min(10, remaining))
                    chunk = response.read1(min(8192, maximum + 1 - len(chunks)))
                    if not chunk:
                        break
                    chunks.extend(chunk)
                    evidence.require(len(chunks) <= maximum, "GitHub response size exceeds bound")
                evidence.require(bool(chunks) and (length is None or len(chunks) == int(length)), "truncated GitHub response")
                return bytes(chunks)
            finally:
                connection.close()
        raise ValueError("unexpected GitHub redirect")


def asset_for_release(raw: bytes, tag: str) -> tuple:
    evidence.require(0 < len(raw) <= MAX_METADATA_BYTES, "release metadata size exceeds bound")
    release = json.loads(raw, object_pairs_hook=evidence.unique_object)
    evidence.require(type(release) is dict and release.get("tag_name") == tag and release.get("draft") is True,
                     "matching same-repository draft release required")
    release_id = release.get("id")
    evidence.positive(release_id, 2**53 - 1, "release ID")
    evidence.require(release.get("url") == f"{API}/releases/{release_id}", "wrong release repository")
    assets = release.get("assets")
    evidence.require(type(assets) is list and len(assets) <= 100, "bounded release assets required")
    matches = [asset for asset in assets if type(asset) is dict and asset.get("name") == evidence.ASSET_NAME]
    evidence.require(len(matches) == 1, "exactly one named evidence asset required")
    asset = matches[0]
    asset_id = asset.get("id")
    evidence.positive(asset_id, 2**53 - 1, "asset ID")
    evidence.positive(asset.get("size"), evidence.MAX_EVIDENCE_BYTES, "asset size")
    evidence.require(asset.get("state") == "uploaded" and asset.get("content_type") == "application/json"
                     and asset.get("url") == f"{API}/releases/assets/{asset_id}", "asset is not an uploaded repository JSON asset")
    return release_id, asset_id, asset["size"], asset.get("digest")


def acquire(binding: dict, destination: pathlib.Path, fetch) -> None:
    # binding comes only from verify_release, not dispatch-supplied digests/URLs.
    metadata_url = f"{API}/releases/tags/{quote(binding['tag'], safe='')}"
    original = asset_for_release(fetch(metadata_url), binding["tag"])
    _, asset_id, size, digest = original
    evidence.require(digest in (None, "sha256:" + binding["evidenceSha256"]), "GitHub digest disagrees with signed tag")
    data = fetch(f"{API}/releases/assets/{asset_id}", binary=True)
    evidence.require(len(data) == size, "downloaded asset size differs from metadata")
    evidence.validate(data, binding)
    evidence.require(asset_for_release(fetch(metadata_url), binding["tag"]) == original,
                     "draft asset changed during acquisition")
    # Create only after every check; never overwrite a prior run's evidence.
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(destination, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
    try:
        with os.fdopen(fd, "wb", closefd=False) as stream:
            stream.write(data)
            stream.flush()
            os.fsync(fd)
    except BaseException:
        destination.unlink()
        raise
    finally:
        os.close(fd)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("tag")
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args()
    evidence.require(os.environ.get("GITHUB_REPOSITORY") == evidence.REPOSITORY, "wrong workflow repository")
    binding = evidence.signed_binding(args.tag)
    acquire(binding, args.output, GitHubReader(os.environ.get("GH_TOKEN", "")))
    print("Acquired reviewed draft Lab evidence; actual built AAR validation remains mandatory")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RecursionError, http.client.HTTPException):
        # urllib/http errors can include private signed URLs: never print them.
        raise SystemExit("Android release evidence acquisition failed") from None
