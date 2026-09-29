#!/usr/bin/env python3
"""Check the signed-tag-bound sanitized Lab export against the actual built AAR."""
from __future__ import annotations
import argparse
import importlib.util
import pathlib

spec = importlib.util.spec_from_file_location("android_release_evidence", pathlib.Path(__file__).with_name("android-release-evidence.py"))
evidence = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evidence)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("evidence", type=pathlib.Path)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--aar", required=True, type=pathlib.Path)
    args = parser.parse_args()
    binding = evidence.signed_binding(args.tag)
    value = evidence.validate(evidence.read_regular(args.evidence, evidence.MAX_EVIDENCE_BYTES), binding, args.aar)
    print(f"reviewed Lab export bound to source and exact AAR: {value['source']['version']}")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RecursionError) as error:
        # Do not expose payload bytes, credentials, URLs or private input paths.
        raise SystemExit(f"Android release evidence rejected ({type(error).__name__})") from None
