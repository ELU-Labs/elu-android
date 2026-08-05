# Android `0.1.0` compatibility baseline

This directory records the published `0.1.0` release for reproducible API,
artifact, dependency, and consumer compatibility checks.

- Git tag: `0.1.0`
- Git commit: `c5bd7ff7b172748c62d099bdab02b911aee0b0d4`
- Maven coordinates: `dev.elu:elu-analytics:0.1.0`
- Gradle metadata publication: Gradle `8.13`
- Kotlin standard library: `2.1.20`
- Public minimum Android API: `23`
- Replay availability: supplied by the declared runtime dependency, API `26+`

The Maven metadata and checksums below were read from Maven Central on
2026-08-03. Published versions are immutable; these snapshots verify
compatibility and are never inputs to republish an existing version.

The conformance fixtures under [`../../conformance/fixtures/0.1.0`](../../conformance/fixtures/0.1.0)
are observational and non-normative. They record behavior of the published
artifact and are not the transport, persistence, replay-envelope, or remote
configuration protocol.

## Contents

- `api/public-api.txt`: `javap -public` output from the published AAR.
- `maven/`: normalized POM representation and published repository metadata.
- `artifact-manifest.json`: published sizes, hashes, and archive entries.
- `dependencies/release-runtime-classpath.txt`: normalized summary of the
  resolved runtime closure from the tagged source.

The two legacy-family dependency coordinates are not silently omitted: their
exact group, artifact, version, source tag, and source commit are recorded in
[`../../legal/THIRD_PARTY_NOTICES.md`](../../legal/THIRD_PARTY_NOTICES.md).
The files here use explicit legal-inventory references so this directory is a
normalized compatibility view, not a verbatim dependency publication. The
exact published POM and Gradle module metadata remain available at the Maven
coordinate and are covered by the recorded checksums.

## Consumer reproduction

Compile the maintained sample against the immutable published baseline and
verify its AAR checksum:

```bash
./gradlew :sample:assembleDebug :sample:verifyBaselineArtifact -PeluBaselineVersion=0.1.0
```

Compile the same source against the current project with:

```bash
./gradlew :sample:assembleDebug
```
