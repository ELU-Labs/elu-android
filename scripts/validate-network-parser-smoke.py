#!/usr/bin/env python3
"""Validate the static URL-parser smoke fixture without treating it as evidence."""

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


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("fixture", type=pathlib.Path)
    args = parser.parse_args()

    data = json.loads(args.fixture.read_text(encoding="utf-8"))
    if data.get("schemaVersion") != 1:
        raise SystemExit("parser smoke must use schemaVersion 1")
    if data.get("fixtureKind") != "network-parser-smoke":
        raise SystemExit("fixtureKind must be network-parser-smoke")
    if data.get("runtimeEvidence") is not False:
        raise SystemExit("the static parser smoke must explicitly set runtimeEvidence=false")

    requests = data.get("requests")
    if not isinstance(requests, list) or not requests:
        raise SystemExit("parser smoke must contain requests")
    expected_count = data.get("expectedRequestCount")
    if isinstance(expected_count, bool) or not isinstance(expected_count, int):
        raise SystemExit("expectedRequestCount must be an integer")
    if len(requests) != expected_count:
        raise SystemExit(
            f"parser smoke expected {expected_count} request(s) but contains {len(requests)}"
        )

    scenarios: list[str] = []
    for index, request in enumerate(requests):
        if not isinstance(request, dict):
            raise SystemExit(f"request {index} must be an object")
        scenario = request.get("scenario")
        method = request.get("method")
        url = request.get("url")
        if scenario not in EXPECTED_METHODS:
            raise SystemExit(f"request {index} has unsupported scenario: {scenario!r}")
        if method != EXPECTED_METHODS[scenario]:
            raise SystemExit(
                f"request {index} scenario {scenario!r} requires method "
                f"{EXPECTED_METHODS[scenario]}"
            )
        if not isinstance(url, str) or not url:
            raise SystemExit(f"request {index} must contain a URL")
        scenarios.append(scenario)

    if len(set(scenarios)) != len(scenarios):
        raise SystemExit("parser smoke scenarios must be unique")
    if set(scenarios) != set(EXPECTED_METHODS):
        missing = sorted(set(EXPECTED_METHODS).difference(scenarios))
        raise SystemExit(f"parser smoke is missing scenarios: {', '.join(missing)}")
    print(f"network parser smoke valid: {len(requests)} requests across {len(scenarios)} scenarios")


if __name__ == "__main__":
    main()
