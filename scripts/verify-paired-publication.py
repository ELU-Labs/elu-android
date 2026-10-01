#!/usr/bin/env python3
"""Recheck signed paired Lab evidence and the exact bytes staged for Central."""
from __future__ import annotations

import argparse
import hashlib
import importlib.util
import pathlib
import subprocess
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]


def load(name: str):
    spec = importlib.util.spec_from_file_location(name.replace("-", "_"), ROOT / "scripts" / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


EVIDENCE = load("android-release-evidence")
DIST = load("validate-compose-distribution")
require = EVIDENCE.require


def file_identity(path: pathlib.Path) -> tuple:
    value = path.lstat()
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def published_bytes(root: pathlib.Path, value: dict) -> None:
    """The plugin reads these two directories into one bundle after build success.

    Reject stale versions, extra publications, links and payload substitutions.
    Signatures and checksum sidecars may differ from Lab; payloads may not.
    """
    version = value["source"]["version"]
    retained, directories = [], []
    total = 0
    for module in DIST.MODULES:
        repository = root / module / "build/publishing/mavenCentral"
        require(repository.resolve() == repository, "linked publication repository")
        original_root = EVIDENCE.directory_identity(repository)
        directories.append((repository, original_root))
        files = {}
        for path in repository.rglob("*"):
            require(not path.is_symlink(), "linked publication member")
            if path.is_dir():
                directories.append((path, EVIDENCE.directory_identity(path)))
            else:
                identity = file_identity(path)
                data = EVIDENCE.read_regular(path, DIST.MAX_FILE_BYTES)
                total += len(data)
                require(total <= DIST.MAX_TOTAL_BYTES and len(retained) < 128, "publication exceeds bound")
                require(file_identity(path) == identity, "publication changed during read")
                files[str(path.relative_to(repository))] = (path, data)
                retained.append((path, identity, hashlib.sha256(data).hexdigest()))
        prefix = f"dev/elu/{module}/{version}/{module}-{version}"
        payloads = {prefix + suffix for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")}
        metadata = f"dev/elu/{module}/maven-metadata.xml"
        expected = payloads | {name + ".asc" for name in payloads} | {metadata}
        require(expected <= files.keys(), "missing publication payload, signature or metadata")
        for name in payloads:
            data = files[name][1]
            reference = value["distribution"]["files"]["repository/" + name]
            require(len(data) == reference["bytes"] and hashlib.sha256(data).hexdigest() == reference["sha256"],
                    "published payload differs from exact Lab distribution")
            # Use only the public keyring already imported by the reviewed workflow.
            subprocess.run(["gpg", "--batch", "--verify", str(files[name + ".asc"][0]), str(files[name][0])],
                           check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=15)
        raw_metadata = files[metadata][1]
        require(b"<!DOCTYPE" not in raw_metadata and b"<!ENTITY" not in raw_metadata, "publication metadata entity")
        tree = ET.fromstring(raw_metadata)
        require(tree.tag == "metadata" and tree.findtext("groupId") == "dev.elu" and
                tree.findtext("artifactId") == module and tree.findtext("versioning/latest") == version and
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
        require(EVIDENCE.directory_identity(path) == identity, "publication directory changed")
    for path, identity, digest in retained:
        require(file_identity(path) == identity and
                hashlib.sha256(EVIDENCE.read_regular(path, DIST.MAX_FILE_BYTES)).hexdigest() == digest,
                "publication changed during validation")


def verify(tag: str, published: bool = False) -> None:
    binding = EVIDENCE.signed_binding(tag)
    path = ROOT / "build/reports/android-runtime-network-evidence.json"
    data = EVIDENCE.read_regular(path, EVIDENCE.MAX_EVIDENCE_BYTES)
    value = EVIDENCE.validate(data, binding, distribution=ROOT / "build/compose-distribution")
    # Rejoin current Gradle outputs as well as the copied distribution. This also
    # rejects a build that regenerated a different source/Javadoc/metadata file.
    DIST.stage(ROOT, verify_only=True)
    if published:
        published_bytes(ROOT, value)
    require(EVIDENCE.signed_binding(tag) == binding and
            EVIDENCE.read_regular(path, EVIDENCE.MAX_EVIDENCE_BYTES) == data, "signed release binding changed")
    EVIDENCE.read_distribution(ROOT / "build/compose-distribution", value)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--published", action="store_true")
    args = parser.parse_args()
    verify(args.tag, args.published)
    print("Signed paired release bytes verified" + (" against actual publication staging" if args.published else " before publication"))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, RecursionError, ET.ParseError, subprocess.SubprocessError) as error:
        raise SystemExit(f"Paired publication refused ({type(error).__name__})") from None
