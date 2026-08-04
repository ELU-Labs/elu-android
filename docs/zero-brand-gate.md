# Zero-brand release gate

`scripts/zero-brand-gate.py` loads the forbidden identifier from the marked
section of `legal/THIRD_PARTY_NOTICES.md`; the scanner source does not duplicate
it. The only content allowlist is a basename beginning with `LICENSE` or
`THIRD_PARTY_NOTICES`, case-insensitively.

The scanner covers tracked and untracked source-tree files, path names, nested
ZIP/JAR/AAR/APK entries, binary strings/symbols, generated POM and Gradle
metadata, resolved dependency reports, SBOMs, source/docs archives, and supplied
network traces. Every external input receives a stable logical label so CI can
compare independently generated artifacts.

Modes:

- `strict` blocks on every non-legal occurrence and is the ownership-release
  target.
- `ratchet` permits only the hashed occurrence/count inventory frozen from the
  historical wrapper and rejects new debt without reproducing the identifier
  in the baseline.
- `report` inventories transitional artifacts without claiming compliance.

The published `0.1.0` tag and Maven files are immutable historical evidence.
They are not rewritten. The first owned runtime release must pass `strict`
across the clean source checkout, resolved graph, AAR, sources/docs archives,
POM/module metadata, DEX/symbols, SBOM, and scripted network trace.

Run the scanner tests with:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```
