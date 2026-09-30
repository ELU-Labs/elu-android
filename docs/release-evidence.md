# Android release evidence handoff

The manual Maven workflow requires a completed **original ELU SDK Lab Android
run** against the exact release AAR, together with a reviewed signed tag. The
runtime-network file is a small sanitized export, not raw traffic and not a
replacement for original Lab proof. Unit tests use protocol doubles only.

**There is no completed native release export yet.** Lab's current
`harness/candidates/final-gate.mjs` and local native supervisor do not grant full
native release qualification. Their incomplete/diagnostic JSON, local engine
runs, HTTP successes, stored blobs and component tests cannot produce this
export. Until the maintained original-owner completion/export seam exists and
the actual run completes, this release gate stays closed.

## Original Lab producer contract

The prospective maintained entry point is
`exportCompletedNativeReleaseEvidence(originalCompletedOwner, scope)`. It must
accept the original live completion owner, recheck its installation/run/artifact
and proof ownership before and after every digest read, and check that owner
again synchronously before returning. No portable receipt, caller key, callback
return value or supplied Boolean can stand in for that owner. This function is
not implemented in the Android repository; implementation belongs in Lab when
its native completion gate can actually settle successfully.

The producer must join actual clean installation/installed APK and AAR linkage,
engine events/mutations/flag evaluation, native decode/storage, customer-player
rendering, readable ordinary text/hidden inputs/excluded blocked content,
offline recovery, retries/lost acknowledgments, supported owned upgrades,
required process/lifecycle/configuration/identity/consent faults, and reviewed
resource measurements. It preserves the original private evidence and emits
only digests, byte counts and the fixed public summary below. Reviewers must
inspect that original evidence and the exact maintained exporter commit before
signing; the consumer validates the reviewed export's identity and structure,
not the truth of raw observations it does not possess.

The closed schema is enforced in `scripts/android-release-evidence.py`:

- `schemaVersion: 2`, `evidenceKind: "android-release-lab-evidence"`.
- `source`: exact `ELU-Labs/elu-android` repository, 40-hex commit and version.
- `artifact`: `dev.elu:elu-analytics:<version>`, exact AAR SHA-256 and byte size.
- `producer`: `ELU-Labs/elu-sdk-lab`, its reviewed 40-hex commit and the exporter
  name above. Naming that exporter alone is not evidence that it ran.
- `run`: original UUID, matching `lab-<UUID>-android` test run, original run and
  manifest SHA-256, and the maintained domain-separated `siteKeyHash`.
- `completion`: kind `completed-original-native-release`, original completion
  receipt `{sha256, bytes}`, and an exact proof map. The required keys are
  `installation`, `installed-bytes`, `events-stored`, `mutations-stored`,
  `flags-evaluated`, `replay-decoded`, `replay-stored`, `replay-rendered`,
  `replay-privacy`, `offline-recovery`, `retry-identity`, `upgrade`,
  `required-fault-coverage`, `resource-overhead` and `runtime-network`.
  Each value is a digest/byte reference to the original joined evidence, with
  no filesystem path or copied private payload.
- `environment`: kind `default-cloud`, `https://elu.dev` config origin,
  `https://ingest.elu.dev` ingest origin, `platform-system-trust` TLS. A local
  engine, custom origin or private CA remains separate diagnostic evidence.
- `requests`: exactly one summary each for config GET, capture POST, flags POST
  and native replay POST, with positive observed `count` and original private
  exchange-ledger `{sha256, bytes}`. Each `urlKind` is explicitly
  `sanitized-route-template`; URLs are respectively
  `https://elu.dev/sdk/v2/{siteKey}/config`,
  `https://ingest.elu.dev/v1/events`, `https://ingest.elu.dev/v1/flags` and
  `https://ingest.elu.dev/v2/replay`. These templates are **not original wire
  URLs**. The producer verifies the actual original HTTPS exchanges before
  removing the config path credential. No key, request/response body, header,
  query, signed URL, private path or private input text enters the public export.

Unknown fields, duplicate JSON keys, missing proofs, wrong channels, legacy
formats and evidence above 64 KiB fail closed. Source and AAR provenance must
come from the candidate's real build/install chain; the minimal four-artifact
Lab manifest alone does not establish source provenance or completion.

## Reviewed release process

After successful original qualification and independent review:

1. Preserve the original completion evidence privately and obtain the exact
   sanitized export bytes from the maintained owner. Compute their SHA-256.
2. The trusted release signer creates the exact annotated version tag, pointing
   at the qualified source commit, with the existing `Reviewed-by:` trailer and
   exactly one `Android-Lab-Evidence-SHA256: <64 lowercase hex>` trailer inside
   the signed message. No workflow input can supply or override this digest.
3. A release maintainer creates a same-repository **draft** release for that tag
   and uploads exactly one `android-runtime-network-evidence.json` asset as
   `application/json`. Upload/tag/publication remain reviewed maintainer actions;
   these scripts do none of them. Do not publish private traces as release assets.
4. Run the protected `maven-central-reviewed` workflow. It verifies the trusted
   signed tag/version/source/review and unchanged worktree, downloads only that
   draft asset through the repository API, checks its exact signed digest and
   structure, and rechecks the original asset identity. Acquisition uses the
   workflow's read-only repository token, bounded size/time and system HTTPS;
   it never forwards the token to a redirected asset host or accepts a caller URL.
5. Release checks build the AAR. The validator verifies the tag again and requires
   the built AAR's actual hash and size to equal the Lab-installed artifact before
   the existing API, legal-identifier, dependency, SBOM and publication gates.
   A non-reproducible rebuild is a failure, not permission to substitute a similar
   artifact. Dry-run skips only publication and still requires all this evidence.

A missing draft/token access, missing or changed asset, incomplete Lab run,
wrong origin/TLS, wrong source/tag, or any AAR byte mismatch blocks publication.
The GitHub release must remain a draft through acquisition. The workflow does
not change release state or expose raw evidence in logs.

This handoff does not itself deploy the engine, activate a registry, or verify a
published package in production. Coordinated deployment and fresh production
verification remain separately required after reviewed publication.

## Optional Compose distribution preparation

The optional `dev.elu:elu-analytics-compose:<version>` artifact now has local
publication metadata, sources/Javadoc and a runtime SBOM. Both versions come
from `EluVersion.NAME`; its POM and Gradle metadata must retain the exact matching
core dependency, Compose UI API dependency and Foundation runtime dependency.
This is candidate preparation, not a released package or functioning recorder.

CI generates both distributions and validates their exact bytes using
`scripts/validate-compose-distribution.py`. The checker copies the original
validated AAR/POM/module/source/Javadoc bytes to the fresh
`build/compose-distribution/repository` layout; it does not invoke Gradle publish,
reconstruct metadata, alter core signing requirements or overwrite a previous
stage. A partial stage must be investigated and removed by its owner before a
new attempt. The staged receipt explicitly denies runtime qualification.

The independent `fixtures/compose-consumer` build resolves only Maven coordinates
from that exclusive local `dev.elu` repository. It compiles the actual public
annotation API with both Gradle metadata and POM-only resolution, verifies both
resolved ELU AAR hashes against the checked stage, and has no SDK project or
composite-build dependency. CI passes `-Pandroid.useAndroidX=true`; Java/Kotlin
output remains 11, toolchain 17, with the required desugaring configuration.
The existing Views-only and historical 0.1.0 consumer checks remain separate.

The optional base publishing plugin has **no repository, Central deployment or
signing registration**. Any optional Gradle Maven publishing task is rejected
before execution. Core publication configuration and its signed single-core Lab
evidence validator remain unchanged; that receipt cannot authorize optional
publication. Both artifacts' metadata/dependencies/SBOM are scanned in CI, but
these build checks do not replace exact installed-artifact Lab qualification.

Before enabling optional publication, a separately reviewed Lab/exporter and
release-consumer change must bind both exact AARs, their source/distribution
closure and original installed use, including the new raster policy/routes and
actual customer rendering/privacy. The protected release workflow must then gate
the entire two-artifact publication set. Current candidate versions must not be
advertised as available until actual package publication and clean-consumer
verification finish. Annotation registration on API 23 does not change the
current API 29+ replay eligibility or waive the API 26–28 gap.
