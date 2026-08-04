#!/usr/bin/env python3
"""Scan source, dependency reports, archives, symbols, and network traces."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import io
import json
import pathlib
import subprocess
import sys
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_TOKEN_FILE = ROOT / "legal" / "THIRD_PARTY_NOTICES.md"
TOKEN_START = "<!-- zero-brand-token-start -->"
TOKEN_END = "<!-- zero-brand-token-end -->"
LEGAL_PREFIXES = ("license", "third_party_notices")
ARCHIVE_SUFFIXES = {".aar", ".apk", ".jar", ".zip"}


@dataclasses.dataclass(frozen=True)
class Finding:
    finding_id: str
    logical_path: str
    kind: str
    count: int


def read_token(path: pathlib.Path) -> bytes:
    text = path.read_text(encoding="utf-8")
    try:
        body = text.split(TOKEN_START, 1)[1].split(TOKEN_END, 1)[0].strip()
    except IndexError as error:
        raise SystemExit(f"scanner token markers missing in {path}") from error
    if not body or "\n" in body:
        raise SystemExit("scanner token must be exactly one non-empty line")
    return body.casefold().encode("utf-8")


def is_legal_path(logical_path: str) -> bool:
    name = pathlib.PurePosixPath(logical_path.split("!/")[-1]).name.casefold()
    return name.startswith(LEGAL_PREFIXES)


def make_finding(logical_path: str, kind: str, count: int) -> Finding:
    identity = f"{kind}\0{logical_path}".encode("utf-8")
    return Finding(hashlib.sha256(identity).hexdigest(), logical_path, kind, count)


def scan_blob(data: bytes, logical_path: str, token: bytes, findings: list[Finding]) -> None:
    if is_legal_path(logical_path):
        return
    count = data.lower().count(token)
    if count:
        findings.append(make_finding(logical_path, "content", count))

    suffix = pathlib.PurePosixPath(logical_path).suffix.casefold()
    if suffix not in ARCHIVE_SUFFIXES and not data.startswith(b"PK\x03\x04"):
        return
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith("/"):
                    continue
                nested_path = f"{logical_path}!/{name}"
                path_count = name.casefold().encode("utf-8").count(token)
                if path_count and not is_legal_path(nested_path):
                    findings.append(make_finding(nested_path, "path", path_count))
                try:
                    scan_blob(archive.read(name), nested_path, token, findings)
                except (OSError, RuntimeError, zipfile.BadZipFile):
                    continue
    except zipfile.BadZipFile:
        return


def tracked_files(root: pathlib.Path) -> list[pathlib.Path]:
    result = subprocess.run(
        ["git", "ls-files", "--cached", "--others", "--exclude-standard", "-z"],
        cwd=root,
        check=True,
        capture_output=True,
    )
    return [root / item.decode("utf-8") for item in result.stdout.split(b"\0") if item]


def scan_file(path: pathlib.Path, logical_path: str, token: bytes, findings: list[Finding]) -> None:
    normalized = logical_path.replace("\\", "/")
    if is_legal_path(normalized):
        return
    path_count = normalized.casefold().encode("utf-8").count(token)
    if path_count:
        findings.append(make_finding(normalized, "path", path_count))
    try:
        scan_blob(path.read_bytes(), normalized, token, findings)
    except (OSError, PermissionError):
        return


def scan_tree(root: pathlib.Path, token: bytes, findings: list[Finding]) -> None:
    for path in tracked_files(root):
        if path.is_file():
            scan_file(path, path.relative_to(root).as_posix(), token, findings)


def scan_input(label: str, path: pathlib.Path, token: bytes, findings: list[Finding]) -> None:
    if path.is_dir():
        for child in sorted(item for item in path.rglob("*") if item.is_file()):
            scan_file(child, f"{label}/{child.relative_to(path).as_posix()}", token, findings)
    elif path.is_file():
        scan_file(path, label, token, findings)
    else:
        raise SystemExit(f"scan input does not exist: {path}")


def collapse(findings: list[Finding]) -> list[Finding]:
    totals: dict[tuple[str, str, str], int] = {}
    for finding in findings:
        key = (finding.finding_id, finding.logical_path, finding.kind)
        totals[key] = totals.get(key, 0) + finding.count
    return [Finding(key[0], key[1], key[2], count) for key, count in sorted(totals.items())]


def emit_baseline(findings: list[Finding]) -> None:
    print(
        json.dumps(
            {
                "schemaVersion": 1,
                "historicalReference": "0.1.0",
                "allowances": {finding.finding_id: finding.count for finding in findings},
            },
            indent=2,
            sort_keys=True,
        )
    )


def check_ratchet(findings: list[Finding], baseline_path: pathlib.Path) -> list[Finding]:
    baseline = json.loads(baseline_path.read_text(encoding="utf-8"))
    if baseline.get("schemaVersion") != 1:
        raise SystemExit(f"unsupported scanner baseline: {baseline_path}")
    allowances = baseline.get("allowances", {})
    return [finding for finding in findings if finding.count > allowances.get(finding.finding_id, 0)]


def parse_input(value: str) -> tuple[str, pathlib.Path]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("inputs use label=/path syntax")
    label, raw_path = value.split("=", 1)
    if not label or not raw_path:
        raise argparse.ArgumentTypeError("input label and path must be non-empty")
    return label, pathlib.Path(raw_path).resolve()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    parser.add_argument("--token-file", type=pathlib.Path, default=DEFAULT_TOKEN_FILE)
    parser.add_argument("--input", action="append", type=parse_input, default=[])
    parser.add_argument("--skip-tree", action="store_true")
    parser.add_argument("--mode", choices=("strict", "report", "ratchet"), default="strict")
    parser.add_argument("--baseline", type=pathlib.Path)
    parser.add_argument("--emit-baseline", action="store_true")
    args = parser.parse_args()

    token = read_token(args.token_file)
    findings: list[Finding] = []
    if not args.skip_tree:
        scan_tree(args.root.resolve(), token, findings)
    for label, path in args.input:
        scan_input(label, path, token, findings)
    findings = collapse(findings)

    if args.emit_baseline:
        emit_baseline(findings)
        return

    failures = findings
    if args.mode == "ratchet":
        if args.baseline is None:
            parser.error("--mode ratchet requires --baseline")
        failures = check_ratchet(findings, args.baseline)

    for finding in findings:
        print(f"{finding.kind}: {finding.logical_path} ({finding.count})")
    print(f"zero-brand scan: {len(findings)} finding(s), {len(failures)} release-blocking")
    if args.mode != "report" and failures:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
