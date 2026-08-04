# Android `0.1.0` ownership baseline

This directory freezes the already-published wrapper release before ELU begins
runtime ownership work.

- Git tag: `0.1.0`
- Git commit: `c5bd7ff7b172748c62d099bdab02b911aee0b0d4`
- Maven coordinates: `dev.elu:elu-analytics:0.1.0`
- Gradle metadata publication: Gradle `8.13`
- Kotlin standard library: `2.1.20`
- Public minimum Android API: `23`
- Replay availability: delegated by the historical runtime, API `26+`

The Maven metadata and checksums below were read from Maven Central on
2026-08-03. Published history is immutable; these snapshots are evidence, not
inputs to a republish.

The conformance fixtures under [`../../conformance/fixtures/0.1.0`](../../conformance/fixtures/0.1.0)
are intentionally marked `provisional-pending-javascript-v1`. They record
observed wrapper behavior and must not be treated as the shared transport,
persistence, replay-envelope, or configuration v1 contract. JavaScript owns
that contract freeze.

## Contents

- `api/public-api.txt`: `javap -public` output from the published AAR.
- `maven/`: published POM and repository metadata.
- `artifact-manifest.json`: published sizes, hashes, archive entries, and
  provenance.
- `dependencies/release-runtime-classpath.txt`: resolved runtime closure from
  the tagged source.

The Gradle module metadata is represented by its published checksum and the
resolved dependency inventory. The exact immutable file remains available at
the Maven coordinate; it is not copied here because it contains transitional
dependency naming that the ownership tree must eventually remove.
