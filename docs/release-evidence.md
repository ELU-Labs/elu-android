# Android release evidence handoff

The manual Maven workflow requires a completed **original ELU SDK Lab Android
run** against the exact core and Compose distribution, together with a reviewed signed tag. The
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

The historical **core-only** schema is enforced in `scripts/android-release-evidence.py`:

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
5. Release checks build both modules, stage the distribution and compile clean
   Maven-coordinate consumers using Gradle metadata and POM-only resolution. The
   schema-3 validator verifies the tag again and requires every distribution byte
   to equal the Lab-qualified pair before both API, legal-identifier, dependency
   and SBOM gates. A non-reproducible rebuild fails. Dry-run skips the publication
   task and still requires the signed paired evidence and build checks.
6. The `publishPairedRelease` task rechecks the signed evidence and current build
   outputs, signs and stages both modules, then verifies the actual publication
   payloads, signatures, metadata and checksums. The pinned publishing plugin
   uploads one combined deployment only after the build succeeds. Direct partial
   publication, alternate repositories and configuration-cache publication are
   refused. A staging mismatch fails the build before upload.

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

Both modules register Maven Central publishing and signing. The root publication
gate requires the exact pair, the reviewed signed schema-3 Lab export and the
final staged-byte check. A historical core-only receipt cannot authorize either
member of this paired release. Normal CI needs no signing credentials and does
not execute a publication task. Build checks do not replace installed-artifact
Lab qualification.

Before executing publication, the reviewed Lab exporter must bind both exact
AARs, their source/distribution closure and original installed use, including
raster policy/routes and actual customer rendering/privacy. The protected
release workflow gates the entire two-artifact publication set. Current candidate versions must not be
advertised as available until actual package publication and clean-consumer
verification finish. Annotation registration on API 23 does not change the
current API 29+ replay eligibility or waive the API 26–28 gap.

## Paired export and publication gate

The evidence consumer also accepts a separate closed **schema 3** for the exact
core and Compose pair. This implements validation of a prospective signed
export, not the missing original Lab exporter or its completion gate. No real
paired release export or qualification is asserted here. The Maven release
workflow requires the schema-3 `--distribution` check for both modules; schema 2
remains available only for historical core-only evidence validation.

The new envelope retains `evidenceKind`, `source`, `producer`, `run`,
`completion`, and `environment` with their existing closed fields. It retains
all 15 mandatory original proof categories; it adds no pass flags or invented
qualification category. It replaces `artifact` with these exact fields:

- `artifacts`: exactly `core` and `compose`, each containing `coordinate`,
  `sha256`, and `bytes`. Both coordinates use the original signed version.
- `distribution`: exactly `descriptor: {sha256, bytes}` and `files`. `files`
  contains the 12 exact versioned member paths emitted by the existing local
  distribution checker: each module's AAR, POM, Gradle module metadata, sources
  JAR, Javadoc JAR and runtime SBOM. Both AAR references must equal their member
  references. Each payload is at most 32 MiB; all 12 total at most 128 MiB.
- `scope`: exactly `profile: "android-views-and-declared-compose-v1"` and
  `profiles`, with exactly `views` and `declaredCompose` entries. Each entry
  contains `framework` (`Views` or `Compose`, respectively), original `cohort`
  (`lab`, `replay`, or `replay-performance`), `sourceCommit`, `coreAarSha256`,
  `composeAarSha256`, `distributionSha256`, `completionReceiptSha256`, an
  original `run`, and `configuration`, `policy`, and `observations` digest/byte
  references. Source, pair, descriptor and completion hashes must equal the
  top-level originals. Each case's `run` uses the existing five-field run shape;
  its run ID, test-run ID, manifest and cohort are preserved independently.
  Different original cases must not be relabelled as the top completion run.

The original final completion owner must join those case runs and the private
configuration/policy/framework observations to its final receipt. A digest
alone does not establish that relationship; consumer acceptance is structural
and cryptographic binding to a reviewed signed export, not proof of the
referenced observations' truth. Baseline and analytics cohorts cannot receive
replay-profile credit. Resource comparisons still require separate matched
original baseline evidence under the existing `resource-overhead` proof.

Schema 3 requires exactly six request summaries, using the unchanged summary
fields, positive observed counts and private original ledger references:

| Scenario | Method | Sanitized default-cloud route |
| --- | --- | --- |
| `config-v2` | GET | `https://elu.dev/sdk/v2/{siteKey}/config` |
| `config-v3` | GET | `https://elu.dev/sdk/v3/{siteKey}/config` |
| `capture` | POST | `https://ingest.elu.dev/v1/events` |
| `flags` | POST | `https://ingest.elu.dev/v1/flags` |
| `replay-v2` | POST | `https://ingest.elu.dev/v2/replay` |
| `replay-v3` | POST | `https://ingest.elu.dev/v3/replay` |

These are route templates, never URLs copied from traffic. The original exporter
must verify the actual corresponding exchanges before sanitizing. A declared
scenario, client option or observed v2 exchange cannot substitute for v3 traffic.
Custom origins and private trust roots remain ineligible for this cloud profile.

The explicit paired check is:

```sh
python3 scripts/validate-runtime-network-evidence.py original-export.json \
  --tag 0.2.0 --distribution build/compose-distribution
```

`--aar` and `--distribution` are mutually exclusive. Schema 2 cannot pass paired
validation, and schema 3 cannot pass the old core-only check. Authenticated
draft acquisition can preserve either signed schema's bytes; acquisition alone
does not validate installed artifacts or authorize publication. The 64 KiB
export bound, original signed-tag digest and acquisition restrictions remain.

The paired check compares the descriptor, version file and all 12 payload files,
rejects extra/missing/linked members, and reuses the existing exact POM/GMM/SBOM
validators. It checks original file and directory identities across the read.
The local descriptor must retain `runtimeQualified:false`; it is never rewritten
or promoted into qualification. `verify-paired-publication.py` additionally joins
the current Gradle outputs and, after signing/staging, the actual payloads read
by the pinned publisher. That check invokes only public-key signature validation;
it never loads a private key, uploads, tags or deploys. Signing and publication
remain the protected workflow's final step after all required evidence exists.
