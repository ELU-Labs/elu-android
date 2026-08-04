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
DEFAULT_LEGAL_ALLOWLIST = ROOT / "scanner" / "legal-content-allowlist.json"
DEFAULT_NETWORK_ALLOWLIST = ROOT / "scanner" / "network-allowlist.json"
TOKEN_START = "<!-- zero-brand-token-start -->"
TOKEN_END = "<!-- zero-brand-token-end -->"
ARCHIVE_SUFFIXES = {".aar", ".apk", ".jar", ".zip"}
LEGAL_BASENAME_PATTERN = re.compile(
    r"^(?:LICENSE(?:-[A-Za-z0-9][A-Za-z0-9_-]*)?|THIRD_PARTY_NOTICES)(?:\.(?:md|txt))?$"
)
SCENARIO_PATTERN = re.compile(r"^[a-z][a-z0-9-]*$")
HTTP_METHODS = {"DELETE", "GET", "PATCH", "POST", "PUT"}
APPROVED_NETWORK_ROOTS = {"elu.dev"}


@dataclasses.dataclass(frozen=True)
class Finding:
    finding_id: str
    logical_path: str
    kind: str
    count: int


@dataclasses.dataclass(frozen=True)
class LegalContentAllowlist:
    repository_files: frozenset[str]
    artifact_member_basenames: frozenset[str]


def read_token(path: pathlib.Path) -> bytes:
    text = path.read_text(encoding="utf-8")
    try:
        body = text.split(TOKEN_START, 1)[1].split(TOKEN_END, 1)[0].strip()
    except IndexError as error:
        raise SystemExit(f"scanner token markers missing in {path}") from error
    if not body or "\n" in body:
        raise SystemExit("scanner token must be exactly one non-empty line")
    return body.casefold().encode("utf-8")


def tracked_regular_file(root: pathlib.Path, relative_path: str) -> bool:
    result = subprocess.run(
        ["git", "ls-files", "--stage", "--error-unmatch", "--", relative_path],
        cwd=root,
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return False
    mode = result.stdout.split(maxsplit=1)[0] if result.stdout else ""
    path = root / relative_path
    return mode.startswith("100") and path.is_file() and not path.is_symlink()


def validate_legal_basename(name: object, *, source: str) -> str:
    if not isinstance(name, str) or not name:
        raise SystemExit(f"{source} must contain non-empty strings")
    if pathlib.PurePosixPath(name).name != name or "\\" in name:
        raise SystemExit(f"{source} entry must be an exact basename: {name!r}")
    if LEGAL_BASENAME_PATTERN.fullmatch(name) is None:
        raise SystemExit(f"{source} entry is not an approved legal basename: {name!r}")
    return name


def load_legal_allowlist(root: pathlib.Path, path: pathlib.Path) -> LegalContentAllowlist:
    root = root.resolve()
    expected_path = root / "scanner" / "legal-content-allowlist.json"
    if path.resolve() != expected_path.resolve():
        raise SystemExit(f"legal allowlist must be the repository-owned file: {expected_path}")
    if not tracked_regular_file(root, "scanner/legal-content-allowlist.json"):
        raise SystemExit("legal allowlist must be a tracked regular non-symlink file")
    lock_relative = "scanner/legal-content-allowlist.sha256"
    lock_path = root / lock_relative
    if not tracked_regular_file(root, lock_relative):
        raise SystemExit("legal allowlist lock must be a tracked regular non-symlink file")
    reviewed_digest = lock_path.read_text(encoding="utf-8").strip().lower()
    if re.fullmatch(r"[0-9a-f]{64}", reviewed_digest) is None:
        raise SystemExit("legal allowlist lock must contain exactly one SHA-256 digest")
    actual_digest = hashlib.sha256(path.read_bytes()).hexdigest()
    if actual_digest != reviewed_digest:
        raise SystemExit("legal allowlist digest does not match its reviewed lock")

    data = json.loads(path.read_text(encoding="utf-8"))
    expected_keys = {"schemaVersion", "repositoryFiles", "artifactMemberBasenames"}
    if not isinstance(data, dict) or set(data) != expected_keys or data.get("schemaVersion") != 1:
        raise SystemExit(f"unsupported legal allowlist: {path}")

    raw_repository_files = data.get("repositoryFiles")
    if not isinstance(raw_repository_files, list) or not raw_repository_files:
        raise SystemExit("legal allowlist repositoryFiles must be a non-empty list")
    if raw_repository_files != sorted(set(raw_repository_files)):
        raise SystemExit("legal allowlist repositoryFiles must be unique and sorted")

    repository_files: set[str] = set()
    for raw_path in raw_repository_files:
        if not isinstance(raw_path, str):
            raise SystemExit("legal allowlist repository paths must be strings")
        pure = pathlib.PurePosixPath(raw_path)
        if (
            not raw_path
            or pure.is_absolute()
            or pure.as_posix() != raw_path
            or any(part in {"", ".", ".."} for part in pure.parts)
        ):
            raise SystemExit(f"legal repository path is not canonical: {raw_path!r}")
        approved_location = len(pure.parts) == 1 or (
            len(pure.parts) == 2 and pure.parts[0] == "legal"
        )
        if not approved_location:
            raise SystemExit(f"legal repository path is outside approved locations: {raw_path!r}")
        validate_legal_basename(pure.name, source="repositoryFiles")
        if not tracked_regular_file(root, raw_path):
            raise SystemExit(
                f"legal repository path must be tracked, regular, and non-symlink: {raw_path}"
            )
        repository_files.add(raw_path)

    raw_member_basenames = data.get("artifactMemberBasenames")
    if not isinstance(raw_member_basenames, list) or not raw_member_basenames:
        raise SystemExit("legal allowlist artifactMemberBasenames must be a non-empty list")
    if raw_member_basenames != sorted(set(raw_member_basenames)):
        raise SystemExit("legal allowlist artifactMemberBasenames must be unique and sorted")
    member_basenames = {
        validate_legal_basename(name, source="artifactMemberBasenames")
        for name in raw_member_basenames
    }
    return LegalContentAllowlist(frozenset(repository_files), frozenset(member_basenames))


def make_finding(logical_path: str, kind: str, count: int) -> Finding:
    identity = f"{kind}\0{logical_path}".encode("utf-8")
    return Finding(hashlib.sha256(identity).hexdigest(), logical_path, kind, count)


def scan_blob(
    data: bytes,
    logical_path: str,
    token: bytes,
    findings: list[Finding],
    legal_allowlist: LegalContentAllowlist,
    *,
    legal_content: bool = False,
) -> None:
    if not legal_content:
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
                    member_basename = pathlib.PurePosixPath(name).name
                    scan_blob(
                        archive.read(name),
                        nested_path,
                        token,
                        findings,
                        legal_allowlist,
                        legal_content=(
                            member_basename in legal_allowlist.artifact_member_basenames
                        ),
                    )
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


def scan_file(
    path: pathlib.Path,
    logical_path: str,
    token: bytes,
    findings: list[Finding],
    legal_allowlist: LegalContentAllowlist,
    *,
    legal_content: bool = False,
) -> None:
    normalized = logical_path.replace("\\", "/")
    path_count = normalized.casefold().encode("utf-8").count(token)
    if path_count:
        findings.append(make_finding(normalized, "path", path_count))
    try:
        scan_blob(
            path.read_bytes(),
            normalized,
            token,
            findings,
            legal_allowlist,
            legal_content=legal_content,
        )
    except (OSError, PermissionError):
        return


def scan_tree(
    root: pathlib.Path,
    token: bytes,
    findings: list[Finding],
    legal_allowlist: LegalContentAllowlist,
) -> None:
    for path in tracked_files(root):
        if path.is_file():
            logical_path = path.relative_to(root).as_posix()
            scan_file(
                path,
                logical_path,
                token,
                findings,
                legal_allowlist,
                legal_content=(logical_path in legal_allowlist.repository_files),
            )


def scan_input(
    label: str,
    path: pathlib.Path,
    token: bytes,
    findings: list[Finding],
    legal_allowlist: LegalContentAllowlist,
) -> None:
    if path.is_dir():
        for child in sorted(item for item in path.rglob("*") if item.is_file()):
            scan_file(
                child,
                f"{label}/{child.relative_to(path).as_posix()}",
                token,
                findings,
                legal_allowlist,
            )
    elif path.is_file():
        scan_file(path, label, token, findings, legal_allowlist)
    else:
        raise SystemExit(f"scan input does not exist: {path}")


def network_files(path: pathlib.Path) -> list[pathlib.Path]:
    if path.is_file():
        return [path]
    if path.is_dir():
        return sorted(item for item in path.rglob("*") if item.is_file())
    raise SystemExit(f"network trace does not exist: {path}")


def load_network_allowlist(path: pathlib.Path) -> list[tuple[str, bool]]:
    data = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(data, dict) or data.get("schemaVersion") != 1:
        raise SystemExit(f"unsupported network allowlist: {path}")
    domains: list[tuple[str, bool]] = []
    entries = data.get("domains")
    if not isinstance(entries, list):
        raise SystemExit("network allowlist domains must be a list")
    for entry in entries:
        if not isinstance(entry, dict) or set(entry) != {"host", "includeSubdomains"}:
            raise SystemExit("network allowlist entries must contain host and includeSubdomains")
        raw_host = entry.get("host")
        host = raw_host.casefold().rstrip(".") if isinstance(raw_host, str) else ""
        if host not in APPROVED_NETWORK_ROOTS:
            raise SystemExit(f"allowlisted host is not an approved ELU root: {raw_host!r}")
        include_subdomains = entry.get("includeSubdomains")
        if not isinstance(include_subdomains, bool):
            raise SystemExit("includeSubdomains must be boolean")
        domains.append((host, include_subdomains))
    if not domains:
        raise SystemExit("network allowlist must contain at least one domain")
    if len(domains) != len(set(domains)):
        raise SystemExit("network allowlist entries must be unique")
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
    for trace in traces:
        logical = label if path.is_file() else f"{label}/{trace.relative_to(path).as_posix()}"
        try:
            document = json.loads(trace.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, json.JSONDecodeError) as error:
            violations.append(f"network: {logical}: invalid JSON ({error})")
            continue
        if not isinstance(document, dict):
            violations.append(f"network: {logical}: document must be an object")
            continue
        requests = document.get("requests")
        if not isinstance(requests, list) or not requests:
            violations.append(f"network: {logical}: requests must be a non-empty list")
            continue
        for index, request in enumerate(requests):
            request_label = f"{logical}: requests[{index}]"
            if not isinstance(request, dict):
                violations.append(f"network: {request_label} must be an object")
                continue
            scenario = request.get("scenario")
            if not isinstance(scenario, str) or SCENARIO_PATTERN.fullmatch(scenario) is None:
                violations.append(f"network: {request_label}.scenario is invalid")
            method = request.get("method")
            if method not in HTTP_METHODS:
                violations.append(f"network: {request_label}.method is invalid")
            url = request.get("url")
            if not isinstance(url, str) or not url:
                violations.append(f"network: {request_label}.url must be a non-empty string")
                continue
            try:
                parsed = urllib.parse.urlsplit(url)
                port = parsed.port
            except ValueError as error:
                violations.append(f"network: {request_label}.url is invalid ({error})")
                continue
            if parsed.scheme.casefold() != "https":
                violations.append(f"network: {request_label}.url is not HTTPS: {url}")
                continue
            if parsed.username is not None or parsed.password is not None:
                violations.append(f"network: {request_label}.url must not contain credentials")
                continue
            if port not in {None, 443}:
                violations.append(f"network: {request_label}.url uses non-HTTPS port {port}")
                continue
            if parsed.hostname is None or not host_is_allowed(parsed.hostname, domains):
                violations.append(
                    f"network: {request_label}.url has non-ELU host "
                    f"{parsed.hostname or '<missing>'}"
                )
    if not traces:
        violations.append(f"network: {label}: trace contains no files")
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
                "compatibilityReference": "0.1.0",
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
    parser.add_argument("--legal-allowlist", type=pathlib.Path, default=DEFAULT_LEGAL_ALLOWLIST)
    parser.add_argument("--input", action="append", type=parse_input, default=[])
    parser.add_argument("--network", action="append", type=parse_input, default=[])
    parser.add_argument("--network-allowlist", type=pathlib.Path, default=DEFAULT_NETWORK_ALLOWLIST)
    parser.add_argument("--skip-tree", action="store_true")
    parser.add_argument("--mode", choices=("strict", "report", "ratchet"), default="strict")
    parser.add_argument("--baseline", type=pathlib.Path)
    parser.add_argument("--emit-baseline", action="store_true")
    args = parser.parse_args()

    root = args.root.resolve()
    legal_allowlist = load_legal_allowlist(root, args.legal_allowlist)
    try:
        token_relative = args.token_file.resolve().relative_to(root).as_posix()
    except ValueError as error:
        raise SystemExit("scanner token file must be inside the repository root") from error
    if token_relative not in legal_allowlist.repository_files:
        raise SystemExit("scanner token file must be an explicitly approved legal repository file")
    token = read_token(args.token_file)
    findings: list[Finding] = []
    if not args.skip_tree:
        scan_tree(root, token, findings, legal_allowlist)
    for label, path in args.input:
        scan_input(label, path, token, findings, legal_allowlist)
    network_violations: list[str] = []
    if args.network:
        domains = load_network_allowlist(args.network_allowlist)
        for label, path in args.network:
            scan_input(f"network/{label}", path, token, findings, legal_allowlist)
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
