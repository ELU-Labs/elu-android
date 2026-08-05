#!/usr/bin/env python3
"""Fail closed if the unwired feature-flag runtime crosses its release boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys


PINNED_FILES = {
    # Existing public/provider/config surfaces and the runtime dependency manifest.
    "elu-analytics/src/main/kotlin/dev/elu/analytics/Elu.kt":
        "541dafbcf2735de5f879ecb0951b7ff368675a2ba453ac502a643f725f418475",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluCore.kt":
        "a1f497a7576936d91f0b413fc9ad558a86662a1732ddadddf14944d7f4458ccc",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluConfigClient.kt":
        "ed9e65335829cf348ee992059efc03a523e61c79ef000575814f3e81f4eae642",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluOptions.kt":
        "4aab165f3a94b7ec5a3493440e2dadaff7c1c3258709ec068c07cae48d8f9136",
    "elu-analytics/build.gradle.kts":
        "25e842753cc622e80b3942688a587f72877bd59867c78a02f11a234bf5774d4f",
    # These files carry the explicit no-wire release status.
    "elu-analytics/src/test/resources/contracts/v1/manifest.json":
        "98152d8725c286f29402ba3e420bda8dd364200fb6fdf1cfe49b2da9b8f63e54",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/transport-policy.json":
        "992900180683af04f69d5e459b7c0c9e68edf92c6ebf320136ed36dbae8b60ce",
}

CONTRACT_FILES = {
    "elu-analytics/src/test/resources/contracts/v1/schemas/flags-request.schema.json":
        "aef0ae186355db81806561abb4b1c89885ee5024eb3d99a587531a9c7430a770",
    "elu-analytics/src/test/resources/contracts/v1/schemas/flags-response.schema.json":
        "723161b3c0f3a448d679faa7a0723cb819cdb162e62ba605fc7935e15df69db2",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/flags-request.json":
        "19b4f681c8f2c059d39403a5621c0c60a4b6b4328e2bbe8ae28341724604238a",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/flags-response.json":
        "ae943a59d4362cd297e2ea6d7838f5075ad1f949d1401d07d5585db0102326be",
    "elu-analytics/src/test/resources/contracts/v1/test-vectors/feature-flag-activity.json":
        "dbceaa7bee48caf8bf54b73e494fb3f28460eeaf366bbe26f606b659c62a47c4",
}

MAIN_KOTLIN = pathlib.Path("elu-analytics/src/main/kotlin")
CLIENT = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/flags/AndroidFeatureFlagClient.kt"
)
OWNER = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/RuntimeQueueOwner.kt"
)
DATABASE_INTERFACE = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/RuntimeQueueDatabase.kt"
)
SQLITE_DATABASE = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/AndroidSQLiteRuntimeDatabase.kt"
)

FORBIDDEN_EGRESS = re.compile(
    r"(?i)(java\.net\.(?:http|urlconnection|httpurlconnection)|"
    r"android\.net\.http|org\.apache\.http|okhttp|ktor\.client|cronet)"
)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_text(root: pathlib.Path, relative: pathlib.Path | str) -> str:
    path = root / relative
    if not path.is_file():
        raise ValueError(f"required file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def verify_pins(root: pathlib.Path, errors: list[str]) -> None:
    for relative, expected in {**PINNED_FILES, **CONTRACT_FILES}.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"required pinned file is missing: {relative}")
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(f"pinned feature-flag boundary changed: {relative} ({actual} != {expected})")


def verify_contract_status(root: pathlib.Path, errors: list[str]) -> None:
    try:
        manifest = json.loads(
            load_text(root, "elu-analytics/src/test/resources/contracts/v1/manifest.json")
        )
        policy = json.loads(
            load_text(
                root,
                "elu-analytics/src/test/resources/contracts/v1/fixtures/transport-policy.json",
            )
        )
    except (ValueError, json.JSONDecodeError) as error:
        errors.append(str(error))
        return
    if manifest.get("transport", {}).get("status") != "specified-not-wired":
        errors.append("contract manifest transport status must remain specified-not-wired")
    if manifest.get("transport", {}).get("runtimeBehavior") != "unchanged":
        errors.append("contract manifest runtime behavior must remain unchanged")
    if policy.get("transportStatus") != "specified-not-wired":
        errors.append("transport policy must remain specified-not-wired")


def verify_no_wiring(root: pathlib.Path, errors: list[str]) -> None:
    source_root = root / MAIN_KOTLIN
    if not source_root.is_dir():
        errors.append(f"production source root is missing: {MAIN_KOTLIN}")
        return
    sources: dict[pathlib.Path, str] = {}
    for path in sorted(source_root.rglob("*.kt")):
        relative = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        sources[relative] = text
        if relative != CLIENT and re.search(r"\bAndroidFeatureFlagClient\b", text):
            errors.append(f"production feature-flag client reference is forbidden outside its internal file: {relative}")
        if relative != CLIENT and re.search(r"\bFlagTransport\b", text):
            errors.append(f"production FlagTransport reference/conformer is forbidden: {relative}")
        if relative.parts[-3:-1] == ("internal", "flags") and FORBIDDEN_EGRESS.search(text):
            errors.append(f"concrete network/HTTP code is forbidden in the unwired flags package: {relative}")

    client = sources.get(CLIENT)
    if client is None:
        errors.append(f"unwired client source is missing: {CLIENT}")
    else:
        if client.count("AndroidFeatureFlagClient") != 1:
            errors.append("AndroidFeatureFlagClient must have one internal declaration and no production construction")
        if len(re.findall(r"\bFlagTransport\b", client)) != 2:
            errors.append("FlagTransport must remain an injected interface with no production conformer")
        if "internal fun interface FlagTransport" not in client:
            errors.append("FlagTransport must remain module-internal")
        if "internal class AndroidFeatureFlagClient" not in client:
            errors.append("AndroidFeatureFlagClient must remain module-internal")
        if re.search(r"(?:class|object)\s+\w+[^\n{]*:\s*FlagTransport\b", client):
            errors.append("a concrete production FlagTransport conformer was added")
        if re.search(r"(?<!interface )\bFlagTransport\s*\{", client):
            errors.append("a production FlagTransport lambda/conformer was added")

    allowed_activation = {CLIENT, OWNER}
    allowed_schema = {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE}
    for relative, text in sources.items():
        if "ensureFeatureFlagRuntime" in text and relative not in allowed_activation:
            errors.append(f"lazy flag activation escaped its internal boundary: {relative}")
        if "ensureFlagSchema" in text and relative not in allowed_schema:
            errors.append(f"flag schema migration escaped its internal boundary: {relative}")

    activation_occurrences = sum(text.count("ensureFeatureFlagRuntime") for text in sources.values())
    schema_occurrences = sum(text.count("ensureFlagSchema") for text in sources.values())
    if activation_occurrences != 2:
        errors.append(
            f"expected only the internal activation declaration and unwired-client call; found {activation_occurrences}"
        )
    if schema_occurrences != 3:
        errors.append(
            f"expected only the schema interface, implementation, and activation call; found {schema_occurrences}"
        )
    owner = sources.get(OWNER, "")
    activation_match = re.search(
        r"internal\s+fun\s+ensureFeatureFlagRuntime\s*\(\s*\)\s*:[^=]+="
        r"(?P<body>.*?)(?=\n\s*internal\s+fun\s+)",
        owner,
        re.DOTALL,
    )
    if activation_match is None or "database().ensureFlagSchema(" not in activation_match.group("body"):
        errors.append("SQLite v2 migration must remain inside explicit ensureFeatureFlagRuntime only")


def verify(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    verify_pins(root, errors)
    verify_contract_status(root, errors)
    verify_no_wiring(root, errors)
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = verify(args.root.resolve())
    if errors:
        for error in errors:
            print(f"feature-flag boundary failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("feature-flag isolation verified: contracts pinned, no public wiring, no concrete transport")


if __name__ == "__main__":
    main()
