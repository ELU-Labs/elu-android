#!/usr/bin/env python3
"""Bind the signed release tag to the exact core bytes built, signed and staged for Central.

Before publication, the rebuilt core AAR must equal the SHA-256 named in the
signed tag, and the current Gradle outputs must equal the validated local
distribution. With --published, after signing and local staging, the core
artifact must be the only staged module, every payload must equal that
distribution, every signature must verify against the Maven artifact signing
key, and checksums and metadata must match. This script never uploads, signs,
tags or deploys.
"""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import os
import pathlib
import stat
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULE = "elu-analytics"
UNPUBLISHED_MODULES = ("elu-analytics-compose",)
PAYLOAD_SUFFIXES = (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")
# Public key that signs the Maven artifacts (also on keys.openpgp.org). Rotating
# it is a reviewed change to this constant and to release/README.md.
ARTIFACT_SIGNING_FINGERPRINT = "7559B8499C763DA9DAE9A48F71C7F8C6582E838B"


def load(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


TAG = load("verify-release-tag")
DIST = load("validate-compose-distribution")
require = DIST.require


def file_identity(path: pathlib.Path) -> tuple:
    value = path.lstat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def directory_identity(path: pathlib.Path) -> tuple:
    value = path.lstat()
    require(stat.S_ISDIR(value.st_mode), "publication directory missing or linked")
    return value.st_dev, value.st_ino, value.st_mtime_ns, value.st_ctime_ns


def read_regular(path: pathlib.Path, maximum: int) -> bytes:
    # No special files or symlink leaf substitution; the open file and the path
    # must still be the same regular file after the read.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum,
                "publication member must be a bounded nonempty regular file")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            data = stream.read(maximum + 1)
        after = os.fstat(fd)
        at_path = os.lstat(path)
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        require(identity(before) == identity(after) == identity(at_path), "publication changed during read")
        require(len(data) == before.st_size, "publication changed during read")
        return data
    finally:
        os.close(fd)


def payload_names(version: str) -> dict[str, str]:
    prefix = f"dev/elu/{MODULE}/{version}/{MODULE}-{version}"
    return {suffix: prefix + suffix for suffix in PAYLOAD_SUFFIXES}


def verify_signature(signature: pathlib.Path, payload: pathlib.Path) -> None:
    # Uses the public keyring imported by the release workflow; no private key is read.
    result = subprocess.run(["gpg", "--batch", "--status-fd", "1", "--verify", str(signature), str(payload)],
                            check=False, capture_output=True, text=True, timeout=15)
    require(result.returncode == 0, "artifact signature does not verify")
    signers = set()
    for line in result.stdout.splitlines():
        if line.startswith("[GNUPG:] VALIDSIG "):
            fields = line.split()[2:]
            signers.update(field.upper() for field in (fields[:1] + fields[-1:]))
    require(ARTIFACT_SIGNING_FINGERPRINT in signers, "artifact signature is not from the Maven artifact signing key")


def published_bytes(root: pathlib.Path, receipt: dict) -> None:
    """Check the local staging repository the publishing plugin uploads after a successful build.

    Rejects other modules, stale versions, extra members, links and payload
    substitutions. Signatures and checksum sidecars are new; payloads are not.
    """
    version = receipt["version"]
    for module in UNPUBLISHED_MODULES:
        staged = root / module / "build/publishing"
        require(not staged.is_symlink() and not staged.exists(), "an unpublished module was staged for Central")
    repository = root / MODULE / "build/publishing/mavenCentral"
    require(repository.resolve() == repository, "linked publication repository")
    directories = [(repository, directory_identity(repository))]
    retained, files = [], {}
    total = 0
    for path in repository.rglob("*"):
        require(not path.is_symlink(), "linked publication member")
        if path.is_dir():
            directories.append((path, directory_identity(path)))
            continue
        identity = file_identity(path)
        data = read_regular(path, DIST.MAX_FILE_BYTES)
        total += len(data)
        require(total <= DIST.MAX_TOTAL_BYTES and len(retained) < 128, "publication exceeds bound")
        require(file_identity(path) == identity, "publication changed during read")
        files[str(path.relative_to(repository))] = (path, data)
        retained.append((path, identity, hashlib.sha256(data).hexdigest()))
    payloads = set(payload_names(version).values())
    metadata = f"dev/elu/{MODULE}/maven-metadata.xml"
    expected = payloads | {name + ".asc" for name in payloads} | {metadata}
    require(expected <= files.keys(), "missing publication payload, signature or metadata")
    for name in sorted(payloads):
        data = files[name][1]
        reference = receipt["files"]["repository/" + name]
        require(len(data) == reference["bytes"] and hashlib.sha256(data).hexdigest() == reference["sha256"],
                "published payload differs from the validated distribution")
        verify_signature(files[name + ".asc"][0], files[name][0])
    raw_metadata = files[metadata][1]
    require(b"<!DOCTYPE" not in raw_metadata and b"<!ENTITY" not in raw_metadata, "publication metadata entity")
    tree = ET.fromstring(raw_metadata)
    require(tree.tag == "metadata" and tree.findtext("groupId") == "dev.elu" and
            tree.findtext("artifactId") == MODULE and tree.findtext("versioning/latest") == version and
            tree.findtext("versioning/release") == version and
            [v.text for v in tree.findall("versioning/versions/version")] == [version],
            "publication metadata contains a different version")
    for name, (_, data) in files.items():
        if name in expected:
            continue
        base, _, algorithm = name.rpartition(".")
        require(base in expected and algorithm in ("md5", "sha1", "sha256", "sha512"),
                "unexpected publication member")
        require(data.strip() == hashlib.new(algorithm, files[base][1]).hexdigest().encode(),
                "publication checksum differs")
    for path, identity in directories:
        require(directory_identity(path) == identity, "publication directory changed")
    for path, identity, digest in retained:
        require(file_identity(path) == identity and
                hashlib.sha256(read_regular(path, DIST.MAX_FILE_BYTES)).hexdigest() == digest,
                "publication changed during validation")


def candidate(tag: str) -> tuple[dict, dict]:
    binding = TAG.verify_release(tag)
    # Rejoins the current Gradle outputs with the validated staged distribution,
    # including the exact POM, Gradle metadata and SBOM checks.
    receipt = DIST.stage(ROOT, verify_only=True)
    require(receipt["version"] == binding["tag"], "distribution version differs from the signed tag")
    aar = receipt["files"]["repository/" + payload_names(receipt["version"])[".aar"]]
    require(aar["sha256"] == binding["coreAarSha256"],
            "built core AAR differs from the Core-AAR-SHA256 named in the signed tag")
    return binding, receipt


def verify(tag: str, published: bool = False) -> dict:
    binding, receipt = candidate(tag)
    if published:
        published_bytes(ROOT, receipt)
    require(TAG.verify_release(tag) == binding and DIST.stage(ROOT, verify_only=True) == receipt,
            "signed release binding or build outputs changed")
    return binding


def check_keyring() -> None:
    result = subprocess.run(["gpg", "--batch", "--with-colons", "--list-keys", ARTIFACT_SIGNING_FINGERPRINT],
                            check=False, capture_output=True, text=True, timeout=15)
    fingerprints = {line.split(":")[9].upper() for line in result.stdout.splitlines()
                    if line.startswith("fpr:") and len(line.split(":")) > 9}
    require(result.returncode == 0 and ARTIFACT_SIGNING_FINGERPRINT in fingerprints,
            f"the Maven artifact signing public key {ARTIFACT_SIGNING_FINGERPRINT} is not in the "
            "imported keyring; add it to ELU_RELEASE_SIGNING_PUBLIC_KEYS")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    selected = parser.add_mutually_exclusive_group(required=True)
    selected.add_argument("--tag", help="exact signed release tag")
    selected.add_argument("--check-keyring", action="store_true",
                          help="only require the Maven artifact signing public key in the keyring")
    parser.add_argument("--published", action="store_true", help="also check the signed local staging repository")
    args = parser.parse_args()
    if args.check_keyring:
        if args.published:
            parser.error("--published requires --tag")
        check_keyring()
        print(f"Maven artifact signing public key present: {ARTIFACT_SIGNING_FINGERPRINT}")
        return
    binding = verify(args.tag, args.published)
    print(f"{MODULE} {binding['tag']} bytes match the signed tag (core AAR {binding['coreAarSha256']})"
          + (" and the signed staging repository" if args.published else "; nothing was published"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, RecursionError, ET.ParseError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Release publication refused ({type(error).__name__}): {error}") from None
