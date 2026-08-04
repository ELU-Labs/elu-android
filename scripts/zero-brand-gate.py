#!/usr/bin/env python3
"""Scan source, dependency reports, archives, symbols, and network traces."""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import io
import json
import pathlib
import re
import subprocess
import sys
import urllib.parse
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_TOKEN_FILE = ROOT / "legal" / "THIRD_PARTY_NOTICES.md"
DEFAULT_NETWORK_ALLOWLIST = ROOT / "scanner" / "network-allowlist.json"
TOKEN_START = "<!-- zero-brand-token-start -->"
TOKEN_END = "<!-- zero-brand-token-end -->"
LEGAL_PREFIXES = ("license", "third_party_notices")
ARCHIVE_SUFFIXES = {".aar", ".apk", ".jar", ".zip"}
URL_PATTERN = re.compile(r"\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s\"'<>]+")


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
    if not is_legal_path(logical_path):
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
                if path_count:
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


def network_files(path: pathlib.Path) -> list[pathlib.Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(item for item in path.rglob("*") if item.is_file())
    raise SystemExit(f"network trace does not exist: {path}")


def network_urls(path: pathlib.Path) -> set[str]:
    text = path.read_text(encoding="utf-8", errors="replace")
    return {match.group(0).rstrip(".,);]") for match in URL_PATTERN.finditer(text)}


def load_network_allowlist(path: pathlib.Path) -> list[tuple[str, bool]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise SystemExit(f"unsupported network allowlist: {path}")
    domains: list[tuple[str, bool]] = []
    for entry in data.get("domains", []):
        host = str(entry.get("host", "")).strip().casefold().rstrip(".")
        if not host or "://" in host or "/" in host:
            raise SystemExit(f"invalid allowlisted host: {host!r}")
        domains.append((host, entry.get("includeSubdomains") is True))
    if not domains:
        raise SystemExit("network allowlist must contain at least one domain")
    return domains


def host_is_allowed(host: str, domains: list[tuple[str, bool]]) -> bool:
    normalized = host.casefold().rstrip(".")
    return any(normalized == domain or (include_subdomains and normalized.endswith(f".{domain}")) for domain, include_subdomains in domains)


def validate_network_input(
    label: str,
    path: pathlib.Path,
    domains: list[tuple[str, bool]],
) -> list[str]:
    violations: list[str] = []
    traces = network_files(path)
    discovered_urls = 0
    for trace in traces:
        logical = label if path.is_file() else f"{label}/{trace.relative_to(path).as_posix()}"
        urls = network_urls(trace)
        discovered_urls += len(urls)
        for url in sorted(urls):
            parsed = urllib.parse.urlsplit(url)
            if parsed.scheme.casefold() != "https":
                violations.append(f"network: {logical}: non-HTTPS URL {url}")
                continue
            if parsed.hostname is None or not host_is_allowed(parsed.hostname, domains):
                violations.append(f"network: {logical}: non-ELU host {parsed.hostname or '<missing>'}")
    if not traces:
        violations.append(f"network: {label}: trace contains no files")
    elif discovered_urls == 0:
        violations.append(f"network: {label}: trace contains no URLs")
    return violations


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
    parser.add_argument("--network", action="append", type=parse_input, default=[])
    parser.add_argument("--network-allowlist", type=pathlib.Path, default=DEFAULT_NETWORK_ALLOWLIST)
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
    network_violations: list[str] = []
    if args.network:
        domains = load_network_allowlist(args.network_allowlist)
        for label, path in args.network:
            scan_input(f"network/{label}", path, token, findings)
            network_violations.extend(validate_network_input(label, path, domains))
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
    for violation in network_violations:
        print(violation)
    release_blocking = len(failures) + (0 if args.mode == "report" else len(network_violations))
    print(f"zero-brand scan: {len(findings)} finding(s), {len(network_violations)} network violation(s), {release_blocking} release-blocking")
    if args.mode != "report" and (failures or network_violations):
        raise SystemExit(1)


if __name__ == "__main__":
    main()
