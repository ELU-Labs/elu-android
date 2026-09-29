#!/usr/bin/env python3
"""Closed public envelope for a reviewed original Lab export; not a Lab certifier."""
from __future__ import annotations

import hashlib
import importlib.util
import json
import os
import pathlib
import re
import stat

MAX_EVIDENCE_BYTES = 65_536
MAX_AAR_BYTES = 128 * 1024 * 1024
ASSET_NAME = "android-runtime-network-evidence.json"
REPOSITORY = "ELU-Labs/elu-android"
SHA = re.compile(r"[a-f0-9]{64}")
COMMIT = re.compile(r"[a-f0-9]{40}")
RUN = re.compile(r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}")
PROOFS = (
    "installation", "installed-bytes", "events-stored", "mutations-stored",
    "flags-evaluated", "replay-decoded", "replay-stored", "replay-rendered",
    "replay-privacy", "offline-recovery", "retry-identity", "upgrade",
    "required-fault-coverage", "resource-overhead", "runtime-network",
)
ROUTES = {
    "config": ("GET", "https://elu.dev/sdk/v2/{siteKey}/config"),
    "capture": ("POST", "https://ingest.elu.dev/v1/events"),
    "flags": ("POST", "https://ingest.elu.dev/v1/flags"),
    "replay": ("POST", "https://ingest.elu.dev/v2/replay"),
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def exact(value: object, keys: tuple | list, label: str) -> dict:
    require(type(value) is dict and set(value) == set(keys), f"invalid {label} fields")
    return value


def hex_value(value: object, pattern: re.Pattern, label: str) -> None:
    require(type(value) is str and pattern.fullmatch(value) is not None, f"invalid {label}")


def positive(value: object, maximum: int, label: str) -> None:
    require(type(value) is int and 0 < value <= maximum, f"invalid {label}")


def digest_reference(value: object, label: str) -> None:
    ref = exact(value, ("sha256", "bytes"), label)
    hex_value(ref["sha256"], SHA, label + " digest")
    positive(ref["bytes"], 2**53 - 1, label + " size")


def unique_object(pairs: list) -> dict:
    result = {}
    for key, value in pairs:
        require(key not in result, "duplicate JSON field")
        result[key] = value
    return result


def decode(data: bytes) -> object:
    require(0 < len(data) <= MAX_EVIDENCE_BYTES, "evidence size exceeds bound or is empty")
    return json.loads(data.decode("utf-8"), object_pairs_hook=unique_object,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError("nonfinite JSON")))


def read_regular(path: pathlib.Path, maximum: int) -> bytes:
    # No special-file blocking or symlink leaf substitution. Compare the open file
    # and original path after reading; no raw bytes or private paths in errors.
    fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        before = os.fstat(fd)
        require(stat.S_ISREG(before.st_mode) and 0 < before.st_size <= maximum,
                "input must be a bounded nonempty regular file")
        with os.fdopen(fd, "rb", closefd=False) as stream:
            data = stream.read(maximum + 1)
        after = os.fstat(fd)
        at_path = os.lstat(path)
        identity = lambda s: (s.st_dev, s.st_ino, s.st_size, s.st_mtime_ns, s.st_ctime_ns)
        require(identity(before) == identity(after) == identity(at_path), "input changed during read")
        require(len(data) == before.st_size, "input size changed during read")
        return data
    finally:
        os.close(fd)


def signed_binding(tag: str) -> dict:
    path = pathlib.Path(__file__).with_name("verify-release-tag.py")
    spec = importlib.util.spec_from_file_location("android_signed_release", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.verify_release(tag)


def validate(data: bytes, binding: dict, aar: pathlib.Path | None = None) -> dict:
    require(hashlib.sha256(data).hexdigest() == binding["evidenceSha256"],
            "evidence digest differs from trusted signed tag")
    value = exact(decode(data), ("schemaVersion", "evidenceKind", "source", "artifact", "producer",
                                "run", "completion", "environment", "requests"), "evidence")
    require(type(value["schemaVersion"]) is int and value["schemaVersion"] == 2,
            "release evidence requires schemaVersion 2")
    require(value["evidenceKind"] == "android-release-lab-evidence", "not a completed native release export")
    source = exact(value["source"], ("repository", "commit", "version"), "source")
    require(source == {"repository": REPOSITORY, "commit": binding["sourceCommit"], "version": binding["tag"]},
            "source/version differs from checked-out signed tag")
    hex_value(source["commit"], COMMIT, "source commit")
    artifact = exact(value["artifact"], ("coordinate", "sha256", "bytes"), "artifact")
    require(artifact["coordinate"] == f"dev.elu:elu-analytics:{binding['tag']}", "wrong Maven coordinate")
    digest_reference({key: artifact[key] for key in ("sha256", "bytes")}, "AAR")
    positive(artifact["bytes"], MAX_AAR_BYTES, "AAR size")
    producer = exact(value["producer"], ("repository", "commit", "exporter"), "producer")
    require(producer["repository"] == "ELU-Labs/elu-sdk-lab" and
            producer["exporter"] == "exportCompletedNativeReleaseEvidence", "unrecognized Lab exporter")
    hex_value(producer["commit"], COMMIT, "Lab source commit")
    run = exact(value["run"], ("runId", "testRunId", "runSha256", "manifestSha256", "siteKeyHash"), "run")
    hex_value(run["runId"], RUN, "original run UUID")
    require(run["testRunId"] == f"lab-{run['runId']}-android", "wrong original Android test run")
    for field in ("runSha256", "manifestSha256", "siteKeyHash"):
        hex_value(run[field], SHA, field)
    completion = exact(value["completion"], ("kind", "receipt", "proofs"), "completion")
    require(completion["kind"] == "completed-original-native-release", "incomplete or diagnostic Lab receipt")
    digest_reference(completion["receipt"], "original completion receipt")
    proofs = exact(completion["proofs"], PROOFS, "required original proofs")
    for name, proof in proofs.items():
        digest_reference(proof, name)
    require(value["environment"] == {"kind": "default-cloud", "configOrigin": "https://elu.dev",
            "ingestOrigin": "https://ingest.elu.dev", "tls": "platform-system-trust"},
            "local/custom-origin/test-CA evidence cannot qualify the default cloud release")
    requests = value["requests"]
    require(type(requests) is list and len(requests) == len(ROUTES), "all four native channels are required")
    observed = set()
    for entry in requests:
        item = exact(entry, ("scenario", "method", "url", "urlKind", "count", "exchanges"), "request summary")
        scenario = item["scenario"]
        require(type(scenario) is str and scenario in ROUTES and scenario not in observed, "wrong/duplicate channel")
        observed.add(scenario)
        require((item["method"], item["url"]) == ROUTES[scenario] and item["urlKind"] == "sanitized-route-template",
                "request summary must use the exact sanitized native cloud route")
        positive(item["count"], 1_000_000, "observed request count")
        digest_reference(item["exchanges"], "private original exchange ledger")
    if aar is not None:
        actual = read_regular(aar, MAX_AAR_BYTES)
        require(len(actual) == artifact["bytes"] and hashlib.sha256(actual).hexdigest() == artifact["sha256"],
                "built AAR differs from the exact Lab-installed distribution")
    return value
