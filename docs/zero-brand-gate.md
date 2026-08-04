# Zero-brand release gate

`scripts/zero-brand-gate.py` loads the forbidden identifier from the marked
section of `legal/THIRD_PARTY_NOTICES.md`; the scanner source does not duplicate
it. Content exemptions come only from
`scanner/legal-content-allowlist.json`. Repository entries must be canonical,
tracked regular non-symlink legal files at the repository root or direct
`legal/` directory. Archive exemptions use exact reviewed member basenames;
legal-looking binaries such as `LICENSE-Runtime.class` are not exempt. Every
path is scanned even when its file content is an approved legal notice. The
JSON must match `scanner/legal-content-allowlist.sha256`; changing the reviewed
allowlist therefore requires an explicit lock update in the same review.

The scanner covers tracked and untracked source-tree files, path names, nested
ZIP/JAR/AAR/APK entries, binary strings/symbols, generated POM and Gradle
metadata, resolved dependency reports, SBOMs, source/docs archives, and supplied
network traces. Every external input receives a stable logical label so CI can
compare independently generated artifacts.

Inputs passed with `--network label=path` must be structural JSON evidence with
a non-empty `requests` array. Every request must contain a valid lowercase
scenario, HTTP method, and string `url`; JSON escaping is decoded before URL
validation. Only `requests[i].url` is evidence—unrelated prose cannot mask a
bad or missing request URL. Each request URL must use HTTPS and its host must
equal an entry in
`scanner/network-allowlist.json` or be a true label-boundary subdomain when the
entry permits subdomains. Lookalike suffixes, credentials, non-HTTPS ports,
insecure schemes, malformed requests, empty inputs, and empty directories all
fail.

`scanner/fixtures/network-parser-smoke.json` is a static parser smoke only. It
sets `runtimeEvidence: false`; its scenario set and declared request count are
checked independently by `scripts/validate-network-parser-smoke.py`. It proves
that the allowlist parser accepts representative ELU-owned URLs and nothing
about what the Android runtime actually contacted.

Publication instead requires the device/instrumentation harness to generate
`build/reports/android-runtime-network-evidence.json` with
`evidenceKind: android-runtime-network-capture`, `runtimeEvidence: true`, and
the exact config/capture/replay/flags scenario counts and methods enforced by
`scripts/validate-runtime-network-evidence.py`. That generated file then goes
through the same HTTPS and ELU label-boundary host validator. No checked-in
static fixture can satisfy the runtime-evidence step.

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
POM/module metadata, DEX/symbols, SBOM, and generated runtime network capture.
The release workflow always materializes and strictly scans
`releaseRuntimeClasspath`; it also discovers and scans SBOM/BOM outputs when a
build plugin produces them. There is no SBOM producer configured in this Phase
0/1 repository yet.

Run the scanner tests with:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_*.py'
```
