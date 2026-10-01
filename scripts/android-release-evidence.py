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
PAIRED_ROUTES = {
    "config-v2": ROUTES["config"],
    "config-v3": ("GET", "https://elu.dev/sdk/v3/{siteKey}/config"),
    "capture": ROUTES["capture"],
    "flags": ROUTES["flags"],
    "replay-v2": ROUTES["replay"],
    "replay-v3": ("POST", "https://ingest.elu.dev/v3/replay"),
}
MODULES = {"core": "elu-analytics", "compose": "elu-analytics-compose"}
MAX_MEMBER_BYTES = 32 * 1024 * 1024
MAX_DISTRIBUTION_BYTES = 128 * 1024 * 1024
PROFILE = "android-views-and-declared-compose-v1"
FRAMEWORKS = {"views": "Views", "declaredCompose": "Compose"}
REPLAY_COHORTS = ("lab", "replay", "replay-performance")


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


def validate_run(value: object) -> dict:
    run = exact(value, ("runId", "testRunId", "runSha256", "manifestSha256", "siteKeyHash"), "run")
    hex_value(run["runId"], RUN, "original run UUID")
    require(run["testRunId"] == f"lab-{run['runId']}-android", "wrong original Android test run")
    for field in ("runSha256", "manifestSha256", "siteKeyHash"):
        hex_value(run[field], SHA, field)
    return run


def member_paths(version: str) -> dict[str, dict[str, str]]:
    require(type(version) is str and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", version),
            "invalid distribution version")
    return {role: {
        **{suffix: f"repository/dev/elu/{module}/{version}/{module}-{version}{suffix}"
           for suffix in (".aar", ".pom", ".module", "-sources.jar", "-javadoc.jar")},
        "sbom": f"evidence/{module}-sbom.json",
    } for role, module in MODULES.items()}


def validate_pair(value: dict, binding: dict) -> None:
    artifacts = exact(value["artifacts"], MODULES, "paired artifacts")
    distribution = exact(value["distribution"], ("descriptor", "files"), "distribution")
    digest_reference(distribution["descriptor"], "distribution descriptor")
    positive(distribution["descriptor"]["bytes"], MAX_EVIDENCE_BYTES, "distribution descriptor size")
    members = member_paths(binding["tag"])
    files = exact(distribution["files"], [p for paths in members.values() for p in paths.values()],
                  "distribution member set")
    total = 0
    for name, ref in files.items():
        digest_reference(ref, "distribution member")
        positive(ref["bytes"], MAX_MEMBER_BYTES, "distribution member size")
        total += ref["bytes"]
    require(total <= MAX_DISTRIBUTION_BYTES, "aggregate distribution bytes")
    for role, module in MODULES.items():
        artifact = exact(artifacts[role], ("coordinate", "sha256", "bytes"), "paired artifact")
        require(artifact["coordinate"] == f"dev.elu:{module}:{binding['tag']}", "wrong paired Maven coordinate")
        ref = {key: artifact[key] for key in ("sha256", "bytes")}
        digest_reference(ref, "paired AAR")
        require(ref == files[members[role][".aar"]], "paired AAR differs from distribution member")
    scope = exact(value["scope"], ("profile", "profiles"), "paired scope")
    require(scope["profile"] == PROFILE, "unsupported paired profile")
    profiles = exact(scope["profiles"], FRAMEWORKS, "framework profiles")
    for name, framework in FRAMEWORKS.items():
        profile = exact(profiles[name], ("framework", "cohort", "sourceCommit", "coreAarSha256",
            "composeAarSha256", "distributionSha256", "configuration", "policy", "observations", "run",
            "completionReceiptSha256"), "framework profile")
        require(profile["framework"] == framework and profile["cohort"] in REPLAY_COHORTS,
                "unsupported framework or replay cohort")
        require(profile["sourceCommit"] == value["source"]["commit"] and
                profile["coreAarSha256"] == artifacts["core"]["sha256"] and
                profile["composeAarSha256"] == artifacts["compose"]["sha256"] and
                profile["distributionSha256"] == distribution["descriptor"]["sha256"] and
                profile["completionReceiptSha256"] == value["completion"]["receipt"]["sha256"],
                "orphan framework source, distribution or completion reference")
        # Case runs may differ. Only the original completion owner can establish
        # that these private observations really belong to its final completion.
        validate_run(profile["run"])
        for field in ("configuration", "policy", "observations"):
            digest_reference(profile[field], "original framework " + field)


def directory_identity(path: pathlib.Path) -> tuple:
    value = path.lstat()
    require(stat.S_ISDIR(value.st_mode), "distribution directory missing or linked")
    return value.st_dev, value.st_ino, value.st_mtime_ns, value.st_ctime_ns


def read_distribution(root: pathlib.Path, value: dict) -> None:
    """Compare exact original staged bytes; never publish, reconstruct or certify them."""
    members = member_paths(value["source"]["version"])
    expected = set(value["distribution"]["files"]) | {"distribution.json", "version.txt"}
    directories = {"."} | {str(p) for name in expected for p in pathlib.PurePosixPath(name).parents}
    directory_pins = {}
    file_pins = {}
    def file_identity(path: pathlib.Path) -> tuple:
        observed = path.lstat()
        return observed.st_dev, observed.st_ino, observed.st_size, observed.st_mtime_ns, observed.st_ctime_ns
    def read(name: str, maximum: int) -> bytes:
        path = root / name
        before = file_identity(path)
        data = read_regular(path, maximum)
        require(file_identity(path) == before, "distribution member changed during read")
        file_pins[name] = before
        return data
    def visit(relative: str) -> None:
        path = root / relative
        directory_pins[relative] = directory_identity(path)
        with os.scandir(path) as entries:
            for entry in entries:
                child = str(pathlib.PurePosixPath(relative) / entry.name)
                if child in directories:
                    visit(child)
                else:
                    require(child in expected and entry.is_file(follow_symlinks=False),
                            "unexpected or linked distribution member")
    visit(".")
    descriptor = read("distribution.json", MAX_EVIDENCE_BYTES)
    reference = value["distribution"]["descriptor"]
    require(len(descriptor) == reference["bytes"] and hashlib.sha256(descriptor).hexdigest() == reference["sha256"],
            "original distribution descriptor changed")
    # This descriptor remains identity-only. Its false flag must never be
    # promoted into an asserted runtime qualification by this consumer.
    catalogue = exact(decode(descriptor), ("kind", "version", "runtimeQualified", "files"), "distribution catalogue")
    exact(catalogue["files"], value["distribution"]["files"], "catalogue member set")
    for ref in catalogue["files"].values():
        digest_reference(ref, "catalogue member")
    require(catalogue["kind"] == "local-distribution-only" and catalogue["runtimeQualified"] is False and
            catalogue["version"] == value["source"]["version"] and
            catalogue["files"] == value["distribution"]["files"], "distribution catalogue differs")
    require(read("version.txt", 128) == (value["source"]["version"] + "\n").encode(),
            "distribution version differs")
    spec = importlib.util.spec_from_file_location("paired_release_distribution",
        pathlib.Path(__file__).with_name("validate-compose-distribution.py"))
    distribution = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(distribution)
    for role, module in MODULES.items():
        blobs = {}
        for suffix, name in members[role].items():
            content = read(name, MAX_MEMBER_BYTES)
            ref = value["distribution"]["files"][name]
            require(len(content) == ref["bytes"] and hashlib.sha256(content).hexdigest() == ref["sha256"],
                    "staged distribution differs from original paired evidence")
            blobs[suffix] = content
        version = value["source"]["version"]
        distribution.validate_pom(blobs[".pom"], module, version)
        distribution.validate_module(blobs[".module"], module, version,
            {f"{module}-{version}{suffix}": content for suffix, content in blobs.items() if suffix != "sbom"})
        distribution.validate_sbom(blobs["sbom"], module, version)
    for relative, original in directory_pins.items():
        require(directory_identity(root / relative) == original, "distribution directory changed during read")
    for relative, original in file_pins.items():
        require(file_identity(root / relative) == original, "distribution member changed during validation")


def validate(data: bytes, binding: dict, aar: pathlib.Path | None = None,
             *, distribution: pathlib.Path | None = None) -> dict:
    require(aar is None or distribution is None, "choose core-only AAR or paired distribution")
    require(hashlib.sha256(data).hexdigest() == binding["evidenceSha256"],
            "evidence digest differs from trusted signed tag")
    value = decode(data)
    require(type(value) is dict and type(value.get("schemaVersion")) is int and value["schemaVersion"] in (2, 3),
            "release evidence requires explicit schemaVersion 2 or 3")
    paired = value["schemaVersion"] == 3
    exact(value, ("schemaVersion", "evidenceKind", "source", "producer", "run", "completion", "environment", "requests") +
          (("artifacts", "distribution", "scope") if paired else ("artifact",)), "evidence")
    require(aar is None or not paired, "schema3 cannot qualify a core-only AAR check")
    require(distribution is None or paired, "schema2 is core-only and cannot qualify paired distribution")
    require(value["evidenceKind"] == "android-release-lab-evidence", "not a completed native release export")
    source = exact(value["source"], ("repository", "commit", "version"), "source")
    require(source == {"repository": REPOSITORY, "commit": binding["sourceCommit"], "version": binding["tag"]},
            "source/version differs from checked-out signed tag")
    hex_value(source["commit"], COMMIT, "source commit")
    if not paired:
        artifact = exact(value["artifact"], ("coordinate", "sha256", "bytes"), "artifact")
        require(artifact["coordinate"] == f"dev.elu:elu-analytics:{binding['tag']}", "wrong Maven coordinate")
        digest_reference({key: artifact[key] for key in ("sha256", "bytes")}, "AAR")
        positive(artifact["bytes"], MAX_AAR_BYTES, "AAR size")
    producer = exact(value["producer"], ("repository", "commit", "exporter"), "producer")
    require(producer["repository"] == "ELU-Labs/elu-sdk-lab" and
            producer["exporter"] == "exportCompletedNativeReleaseEvidence", "unrecognized Lab exporter")
    hex_value(producer["commit"], COMMIT, "Lab source commit")
    validate_run(value["run"])
    completion = exact(value["completion"], ("kind", "receipt", "proofs"), "completion")
    require(completion["kind"] == "completed-original-native-release", "incomplete or diagnostic Lab receipt")
    digest_reference(completion["receipt"], "original completion receipt")
    proofs = exact(completion["proofs"], PROOFS, "required original proofs")
    for name, proof in proofs.items():
        digest_reference(proof, name)
    if paired:
        validate_pair(value, binding)
    require(value["environment"] == {"kind": "default-cloud", "configOrigin": "https://elu.dev",
            "ingestOrigin": "https://ingest.elu.dev", "tls": "platform-system-trust"},
            "local/custom-origin/test-CA evidence cannot qualify the default cloud release")
    requests = value["requests"]
    routes = PAIRED_ROUTES if paired else ROUTES
    require(type(requests) is list and len(requests) == len(routes), "all profile native channels are required")
    observed = set()
    for entry in requests:
        item = exact(entry, ("scenario", "method", "url", "urlKind", "count", "exchanges"), "request summary")
        scenario = item["scenario"]
        require(type(scenario) is str and scenario in routes and scenario not in observed, "wrong/duplicate channel")
        observed.add(scenario)
        require((item["method"], item["url"]) == routes[scenario] and item["urlKind"] == "sanitized-route-template",
                "request summary must use the exact sanitized native cloud route")
        positive(item["count"], 1_000_000, "observed request count")
        digest_reference(item["exchanges"], "private original exchange ledger")
    if aar is not None:
        actual = read_regular(aar, MAX_AAR_BYTES)
        require(len(actual) == artifact["bytes"] and hashlib.sha256(actual).hexdigest() == artifact["sha256"],
                "built AAR differs from the exact Lab-installed distribution")
    if distribution is not None:
        read_distribution(distribution, value)
    return value
