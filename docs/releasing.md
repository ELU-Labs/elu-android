# Releasing the Android SDK

Version `0.1.0` is already published and must never be modified or republished.

Future publication is manual and requires all of the following:

1. `EluVersion.NAME` is the single SDK version source consumed by the Maven
   publication and tag verifier.
2. The workflow checks out an exact semantic-version tag rather than the
   default branch or an arbitrary commit.
3. The tag is cryptographically signed, points at `HEAD`, matches
   `EluVersion.NAME`, contains a `Reviewed-by:` trailer, and verifies to a full
   signer fingerprint in the protected environment's trusted set. An unsigned
   annotated tag is not a signed tag and cannot publish.
4. The `maven-central-reviewed` GitHub environment is protected with required
   reviewers and contains the publication secrets.
5. JVM, instrumentation compilation, sample/current consumer, fixture,
   API/ABI, source-ratchet, generated POM/module, source/docs archive, and AAR
   gates pass before credentials reach the publish step.
6. A generated Android runtime-network capture covers the config, capture,
   replay, and flags scenarios with their expected request counts. The checked
   in parser smoke is explicitly not runtime evidence, and cannot satisfy this
   release gate.
7. The workflow materializes `releaseRuntimeClasspath` and includes the report
   in the strict publication scan, together with the AAR, publication metadata,
   source/docs archives, and any generated SBOM/BOM outputs. Publication
   proceeds only when the complete input set passes the strict scan; the CI
   ratchet is not a release substitute.

The protected `maven-central-reviewed` environment must define both signing
trust inputs before publication:

- secret `ELU_RELEASE_SIGNING_PUBLIC_KEYS`: one or more armored public keys;
- variable `ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS`: comma- or
  whitespace-separated full 40- or 64-hex primary/signing fingerprints. Short
  key IDs are rejected.

The workflow imports only those public keys, asks Git/GPG to verify the tag,
then compares the reported full signing or primary-key fingerprint to the
trusted set. A missing key, missing fingerprint set, bad signature, or valid
signature from an untrusted key fails closed. Rotate trust by reviewing and
updating the protected environment values together; never weaken the verifier
or add a short key ID.

Create a future candidate tag only after review:

```bash
git tag -s 1.0.0-rc.1 -m $'Android release candidate\n\nReviewed-by: SDK Owner <owner@elu.dev>'
git verify-tag --raw 1.0.0-rc.1
git push origin 1.0.0-rc.1
```

Then manually dispatch the release workflow with that exact tag. Never reuse,
move, or overwrite a released Maven version or tag. A failed candidate gets a
new semantic version.

The workflow must not be enabled for production until repository settings
actually enforce the protected environment and the instrumentation/device
harness writes `build/reports/android-runtime-network-evidence.json` in the
format enforced by `scripts/validate-runtime-network-evidence.py`. YAML naming
alone is not an approval control.
