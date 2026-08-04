#!/usr/bin/env python3
"""Validate the dependency-free subset of the provisional fixture schema."""

from __future__ import annotations

import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "conformance" / "fixtures" / "0.1.0"
EXPECTED_DOMAINS = {"identity-reset", "groups-flags", "events-lifecycle", "replay-privacy", "persistence-network"}


def fail(message: str) -> None:
    print(f"fixture validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    paths = sorted(FIXTURES.glob("*.json"))
    domains: set[str] = set()
    ids: set[str] = set()
    if not paths:
        fail("no fixtures found")
    for path in paths:
        data = json.loads(path.read_text(encoding="utf-8"))
        expected_baseline = {"platform": "android", "version": "0.1.0", "tag": "0.1.0", "commit": "c5bd7ff7b172748c62d099bdab02b911aee0b0d4", "contractStatus": "provisional-pending-javascript-v1"}
        if data.get("schemaVersion") != 1 or data.get("baseline") != expected_baseline:
            fail(f"{path.name}: baseline must remain pinned and provisional")
        if data.get("normative") is not False:
            fail(f"{path.name}: mobile fixtures cannot become normative before JavaScript v1")
        domain = data.get("domain")
        if domain not in EXPECTED_DOMAINS:
            fail(f"{path.name}: unknown domain {domain!r}")
        domains.add(domain)
        observations = data.get("observations")
        if not isinstance(observations, list) or not observations:
            fail(f"{path.name}: observations must be non-empty")
        for observation in observations:
            observation_id = observation.get("id")
            if not isinstance(observation_id, str) or not observation_id or observation_id in ids:
                fail(f"{path.name}: invalid or duplicate observation id {observation_id!r}")
            ids.add(observation_id)
            for key in ("given", "operations", "expected", "evidence"):
                if not isinstance(observation.get(key), list) or not observation[key]:
                    fail(f"{path.name}/{observation_id}: {key} must be non-empty")
    if domains != EXPECTED_DOMAINS:
        fail(f"domain coverage mismatch: {sorted(domains)}")
    print(f"validated {len(paths)} provisional fixtures ({len(ids)} observations)")


if __name__ == "__main__":
    main()
