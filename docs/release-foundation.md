# Android release foundation

The `0.1.0` publication is immutable historical evidence. No workflow in this
repository modifies or republishes it.

Future publication is manual and requires all of the following:

1. `EluVersion.NAME` is the single SDK version source consumed by the Maven
   publication and tag verifier.
2. The workflow checks out an exact semantic-version tag rather than the
   default branch or an arbitrary commit.
3. The tag is annotated, points at `HEAD`, matches `EluVersion.NAME`, and
   contains a `Reviewed-by:` trailer.
4. The `maven-central-reviewed` GitHub environment is protected with required
   reviewers and contains the publication secrets.
5. JVM, instrumentation compilation, sample/current consumer, fixture,
   API/ABI, source-ratchet, generated POM/module, source/docs archive, and AAR
   gates pass before credentials reach the publish step.
6. The legal-only scanner runs in strict mode for publication. The current
   transitional wrapper is expected to fail that gate; publication remains
   intentionally impossible until runtime ownership removes the historical
   dependency and all generated occurrences.

Create a future candidate tag only after review:

```bash
git tag -a 1.0.0-rc.1 -m $'Android ownership candidate\n\nReviewed-by: SDK Owner <owner@elu.dev>'
git push origin 1.0.0-rc.1
```

Then manually dispatch the release workflow with that exact tag. Never reuse,
move, or overwrite an adopted Maven version or tag. A failed candidate gets a
new semantic version.

The workflow must not be enabled for production until repository settings
actually enforce the protected environment; YAML naming alone is not an
approval control.
