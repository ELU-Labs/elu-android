#!/usr/bin/env python3
"""Check signed-tag-bound Lab evidence against an explicit core-only or paired distribution."""
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
    selected = parser.add_mutually_exclusive_group(required=True)
    selected.add_argument("--aar", type=pathlib.Path, help="Schema 2 core-only AAR check")
    selected.add_argument("--distribution", type=pathlib.Path, help="Schema 3 exact paired staged distribution")
    args = parser.parse_args()
    binding = evidence.signed_binding(args.tag)
    value = evidence.validate(evidence.read_regular(args.evidence, evidence.MAX_EVIDENCE_BYTES), binding,
                              args.aar, distribution=args.distribution)
    kind = "paired distribution" if args.distribution is not None else "core-only AAR"
    print(f"reviewed export bytes bound to source and exact {kind}: {value['source']['version']}; publication not authorized by this check")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, RecursionError) as error:
        # Do not expose payload bytes, credentials, URLs or private input paths.
        raise SystemExit(f"Android release evidence rejected ({type(error).__name__})") from None
