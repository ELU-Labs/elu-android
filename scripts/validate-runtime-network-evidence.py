#!/usr/bin/env python3
"""Validate generated Android runtime-network evidence for release gating."""

from __future__ import annotations

import argparse
import json
import pathlib

EXPECTED_METHODS = {
    "config": "GET",
    "capture": "POST",
    "replay": "POST",
    "flags": "POST",
}


def parse_expectation(value: str) -> tuple[str, int]:
    if "=" not in value:
        raise argparse.ArgumentTypeError("expectations use scenario=count syntax")
    scenario, raw_count = value.split("=", 1)
    try:
        count = int(raw_count)
    except ValueError as error:
        raise argparse.ArgumentTypeError("expected count must be an integer") from error
    if not scenario or count < 1:
        raise argparse.ArgumentTypeError("scenario must be non-empty and count must be positive")
    return scenario, count


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--expect", action="append", type=parse_expectation, required=True)
    args = parser.parse_args()

    if not args.evidence.is_file():
        raise SystemExit(f"runtime network evidence is missing: {args.evidence}")
    data = json.loads(args.evidence.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise SystemExit("runtime network evidence must use schemaVersion 1")
    if data.get("evidenceKind") != "android-runtime-network-capture":
        raise SystemExit("evidenceKind must be android-runtime-network-capture")
    if data.get("runtimeEvidence") is not True:
        raise SystemExit("generated network evidence must explicitly set runtimeEvidence=true")

    expectations = dict(args.expect)
    if len(expectations) != len(args.expect):
        raise SystemExit("runtime network expectations must not repeat a scenario")
    unsupported = sorted(set(expectations).difference(EXPECTED_METHODS))
    if unsupported:
        raise SystemExit(f"unsupported runtime network scenarios: {', '.join(unsupported)}")
    requests = data.get("requests")
    if not isinstance(requests, list) or not requests:
        raise SystemExit("runtime network evidence must contain requests")

    observed = {scenario: 0 for scenario in expectations}
    for index, request in enumerate(requests):
        if not isinstance(request, dict):
            raise SystemExit(f"runtime request {index} must be an object")
        scenario = request.get("scenario")
        if scenario not in expectations:
            raise SystemExit(f"runtime request {index} has unexpected scenario: {scenario!r}")
        method = request.get("method")
        if method != EXPECTED_METHODS[scenario]:
            raise SystemExit(
                f"runtime request {index} scenario {scenario!r} requires method "
                f"{EXPECTED_METHODS[scenario]}"
            )
        url = request.get("url")
        if not isinstance(url, str) or not url:
            raise SystemExit(f"runtime request {index} must contain a URL")
        observed[scenario] += 1

    mismatches = [
        f"{scenario}: expected {expectations[scenario]}, observed {observed[scenario]}"
        for scenario in sorted(expectations)
        if observed[scenario] != expectations[scenario]
    ]
    if mismatches:
        raise SystemExit("runtime network scenario mismatch: " + "; ".join(mismatches))
    print(
        f"runtime network evidence valid: {len(requests)} requests across "
        f"{len(expectations)} scenarios"
    )


if __name__ == "__main__":
    main()
