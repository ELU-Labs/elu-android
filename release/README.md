# Releasing the Android SDK

Only `dev.elu:elu-analytics` is published to Maven Central. The optional
`elu-analytics-compose` module is built and checked in CI, but it has no Central
publication. Each release has a decision record in [decisions/](decisions/)
that says what was checked for that version and what was deferred:

- [0.2.0](decisions/0.2.0.md)

Maven Central versions are permanent. A published version can never be deleted
or replaced, so every step below runs before anything is uploaded, and the
upload itself is not released until a maintainer clicks Publish.

## 1. Merge and wait for CI

Merge the reviewed release commit to `main`. The CI workflow
(`.github/workflows/ci.yml`) runs on that push. Every job must pass. The
**Android SDK checks** job lists the core AAR's SHA-256 in its run summary and
keeps the AAR in its `android-sdk-reports` artifact.

Use that exact AAR for the end-to-end check the decision record requires. To
recompute its hash, download the artifact outside the checkout (step 2 refuses a
worktree with untracked files):

```sh
gh run download <run-id> -R ELU-Labs/elu-android -n android-sdk-reports -D "$TMPDIR/elu-ci-artifacts"
shasum -a 256 "$TMPDIR/elu-ci-artifacts/elu-analytics/build/outputs/aar/elu-analytics-release.aar"
```

## 2. Sign the tag

The tag name must equal `EluVersion.NAME`. Tag the commit that the CI push run on
`main` built (`git rev-parse origin/main` right after the merge), not the pull
request's head commit: the release workflow requires the CI run's commit to be
the tagged one. Create an annotated OpenPGP-signed tag with a trusted release
key. The signed message must carry each of these trailers exactly once:

```text
ELU Analytics for Android 0.2.0

<what is in the release and what was checked>

Source-Commit: <full SHA of the release commit>
CI-Run: https://github.com/ELU-Labs/elu-android/actions/runs/<run-id>
Core-AAR-SHA256: <SHA-256 of elu-analytics-release.aar from that run>
Release-Decision: release/decisions/0.2.0.md
Reviewed-by: <reviewer>
```

Write the message to a file outside the checkout, for example
`~/elu-0.2.0-tag-message.txt`, and sign in a terminal, because the passphrase
prompt needs one. Name the key with `-u` so git cannot pick another one:

```sh
git tag -s -u 8170EB95A7E746B421D34BC12363FE13E599B25D 0.2.0 <release-commit> -F ~/elu-0.2.0-tag-message.txt
git checkout --detach 0.2.0
ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS=<fingerprint> python3 scripts/verify-release-tag.py 0.2.0
git push origin 0.2.0
```

`scripts/verify-release-tag.py` refuses a lightweight or unsigned tag, a signer
outside the trusted fingerprints, a tag that does not match `EluVersion.NAME` or
the checked-out commit, a missing or duplicated trailer, text after the
signature, a decision record missing from the tagged commit, and a dirty
worktree.

## 3. Dry run, then upload

Run the **Publish to Maven Central** workflow with the tag. `dryRun` is on by
default. A dry run passes every gate and uploads nothing. The workflow:

1. imports the trusted public keys and, before it runs any code from the
   checkout, checks with git and GnuPG alone that the tag is annotated, points at
   the checked-out commit and has a valid signature from a trusted fingerprint;
2. requires the Maven artifact signing key among the imported keys, then verifies
   the signed tag in full as above;
3. requires the CI run named in the tag to be a successful push run on `main`
   for exactly the tagged commit, with every job successful, and requires the
   AAR in its `android-sdk-reports` artifact to match `Core-AAR-SHA256`;
4. rebuilds and reruns unit tests, lint, the feature-flag boundary, conformance
   fixtures and brand checks;
5. stages both modules locally and compiles the coordinate-only consumers;
6. requires the rebuilt core AAR to equal `Core-AAR-SHA256` byte for byte;
7. checks the public API snapshot and runs the strict scan over the AARs, POMs,
   sources, Javadoc, resolved dependencies and SBOMs;
8. checks the Gradle publication graph: only the core module, through
   `stageCentralRelease`, with no automatic release;
9. in a dry run only, signs the core publication locally with `SIGNING_KEY` and
   `SIGNING_KEY_PASSWORD` and requires every signature to verify against the
   Maven artifact signing key. A wrong key or passphrase fails here, not on the
   upload.

Then run it again with `dryRun` off. `stageCentralRelease` signs the core
artifact into a local staging repository, checks that every staged payload
equals the checked build and that every signature verifies against the Maven
artifact signing key, and uploads one deployment. The job ends when Central
accepts the upload. Central then validates the deployment: wait until it shows
**Validated** before you click Publish, and Drop it if it shows **Failed**.
Nothing is released yet.

## 4. Publish in the Central Portal

Open <https://central.sonatype.com/publishing/deployments>. The deployment must
contain only `dev/elu/elu-analytics/<version>/`: the AAR, POM, Gradle module
metadata, sources and Javadoc JARs, their signatures and checksums. Click
**Publish**. If anything is wrong, click **Drop** and fix it before a new upload.

## 5. After publication

- A clean Gradle app resolves `dev.elu:elu-analytics:<version>` from Maven
  Central, and the downloaded AAR's SHA-256 equals `Core-AAR-SHA256`.
- The `.asc` signatures verify with the artifact signing key, and the POM's
  dependencies are exactly the ones `scripts/validate-compose-distribution.py`
  expects.
- Create the GitHub release for the tag. Its notes include what the decision
  record says they must, and how to verify the tag.

## Repository setup

The workflow runs in the protected `maven-central-reviewed` environment. GitHub
creates an environment that a workflow names on its first run, but without any
protection rules, so set it up before the first run, in this order:

1. In the repository settings, create the environment `maven-central-reviewed`.
2. Under **Required reviewers**, add the maintainer who approves releases.
3. Under **Deployment branches and tags**, choose **Selected branches and tags**
   and add `main`.
4. Add these **environment** secrets:

   | Secret | Value |
   | --- | --- |
   | `ELU_RELEASE_SIGNING_PUBLIC_KEYS` | One armored block with the public keys of the tag-signing key `8170EB95A7E746B421D34BC12363FE13E599B25D` and of the Maven artifact signing key `C80C9F80FFBDE134020957F8BECDBFA573963BFD`. The workflow stops at its first step if the artifact key is missing. |
   | `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD` | Central Portal user token |
   | `SIGNING_KEY`, `SIGNING_KEY_PASSWORD` | Armored secret key of the Maven artifact signing key and its passphrase |

5. Add the environment variable
   `ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS=8170EB95A7E746B421D34BC12363FE13E599B25D`
   (full fingerprints of the trusted tag-signing keys, separated by commas).
6. Delete any repository-level `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`,
   `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` secrets. Repository secrets are
   readable by every workflow in the repository; environment secrets reach only
   jobs the reviewer approved.

Also add a tag ruleset that lets only administrators create, update or delete
tags matching `*.*.*`.

To rotate the artifact signing key, change `ARTIFACT_SIGNING_FINGERPRINT` in
`scripts/verify-release-publication.py` and this table in a reviewed commit, and
publish the new public key to a public keyserver.

## Local distribution checks

`scripts/validate-compose-distribution.py` validates the core and Compose AAR,
POM, Gradle module metadata, sources, Javadoc and runtime SBOM, then copies the
exact bytes to `build/compose-distribution/repository`. It never runs a Gradle
publish task and never overwrites an earlier stage. The
`fixtures/compose-consumer` build resolves both modules only from that local
repository, with Gradle metadata and with POM-only resolution, and checks both
AAR hashes against the stage. These checks keep the Compose module buildable and
its metadata correct; they do not publish it.

`fixtures/core-consumers` and `fixtures/kotlin20-consumer` resolve only the
staged core artifact and check its AAR hash. They build a release app with R8
full mode (and the same app without the SDK, for the size figures in the
README), the same app compiled against android-34, an app on OkHttp 5, a
Java-only app and a Kotlin 2.0 app. CI also
installs the minified apps on every emulator level and runs
`scripts/run-consumer-smoke.sh` against them.
