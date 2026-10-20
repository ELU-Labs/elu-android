from __future__ import annotations

import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"
UPLOAD = "./gradlew stageCentralRelease"
DRY_RUN_SIGN = "Sign the publication locally without uploading"


def step(text: str, name: str) -> str:
    start = text.index(f"- name: {name}")
    following = text.find("\n      - ", start + 1)
    return text[start:] if following < 0 else text[start:following]


class ReleaseWorkflowTest(unittest.TestCase):
    def setUp(self) -> None:
        self.text = WORKFLOW.read_text(encoding="utf-8")

    def test_resolved_runtime_graph_is_generated_and_strictly_scanned(self) -> None:
        generate = self.text.index(
            "./gradlew :elu-analytics:dependencies --configuration releaseRuntimeClasspath"
        )
        strict_input = self.text.index(
            "--input dependencies=build/reports/release-runtime-classpath.txt"
        )
        self.assertLess(generate, strict_input)
        self.assertLess(strict_input, self.text.index(UPLOAD))

    def test_generated_sbom_outputs_join_the_strict_scan_inputs(self) -> None:
        self.assertIn("-iname '*sbom*'", self.text)
        self.assertIn('--input "sbom-${sbom_index}=${sbom}"', self.text)
        self.assertLess(self.text.index(":elu-analytics:cyclonedxDirectBom"), self.text.index("-iname '*sbom*'"))

    def test_signed_tag_green_ci_and_tag_named_aar_gate_the_upload(self) -> None:
        keys = self.text.index("name: Import trusted release signing keys")
        signature = self.text.index("name: Verify the tag signature before running repository code")
        keyring = self.text.index("name: Require the artifact signing key in the keyring")
        signed = self.text.index("name: Verify matching reviewed signed tag")
        ci = self.text.index("name: Require green CI on the tagged commit")
        build = self.text.index("name: Run release gates")
        aar = self.text.index("name: Require the core AAR named in the signed tag")
        upload = self.text.index(UPLOAD)
        self.assertLess(keys, signature)
        self.assertLess(signature, keyring)
        self.assertLess(keyring, signed)
        self.assertLess(signed, ci)
        self.assertLess(ci, build)
        self.assertLess(build, aar)
        self.assertLess(aar, upload)
        self.assertIn("verify-release-publication.py --check-keyring",
                      step(self.text, "Require the artifact signing key in the keyring"))
        self.assertIn('verify-release-tag.py "$RELEASE_TAG"', step(self.text, "Verify matching reviewed signed tag"))
        ci_step = step(self.text, "Require green CI on the tagged commit")
        self.assertIn('verify-ci-run.py "$RELEASE_TAG"', ci_step)
        self.assertIn("GH_TOKEN: ${{ github.token }}", ci_step)
        self.assertIn('verify-release-publication.py --tag "$RELEASE_TAG"',
                      step(self.text, "Require the core AAR named in the signed tag"))
        self.assertIn("environment: maven-central-reviewed", self.text)
        self.assertRegex(self.text, re.compile(r"^permissions:\n  contents: read\n  actions: read\n", re.MULTILINE))

    def test_tag_signature_is_checked_before_any_repository_code_runs(self) -> None:
        first_repository_code = min(self.text.index("python3 scripts/"), self.text.index("./gradlew"))
        self.assertLess(self.text.index("name: Verify the tag signature before running repository code"),
                        first_repository_code)
        for gate in ("name: Import trusted release signing keys",
                     "name: Verify the tag signature before running repository code"):
            body = step(self.text, gate.removeprefix("name: "))
            self.assertNotIn("scripts/", body)
            self.assertNotIn("gradlew", body)
        signature = step(self.text, "Verify the tag signature before running repository code")
        for token in ('git cat-file -t "refs/tags/${RELEASE_TAG}"', "git -c gpg.format=openpgp verify-tag --raw",
                      "VALIDSIG", "ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS: ${{ vars.ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS }}",
                      'git rev-parse "refs/tags/${RELEASE_TAG}^{commit}"'):
            self.assertIn(token, signature)

    def test_summary_does_not_claim_central_validation(self) -> None:
        summary = step(self.text, "Release summary")
        self.assertNotIn("validated, not released", summary)
        self.assertIn("shows **Validated**", summary)

    def test_no_lab_evidence_or_runtime_network_export_is_required(self) -> None:
        for retired in ("acquire-runtime-network-evidence", "validate-runtime-network-evidence",
                        "android-runtime-network-evidence.json", "--network runtime=", "publishPairedRelease"):
            self.assertNotIn(retired, self.text)

    def test_dry_run_is_the_default_signs_locally_and_skips_only_the_upload(self) -> None:
        self.assertRegex(self.text, re.compile(r"dryRun:\n(?:        .*\n)*?        default: true\n"))
        conditions = re.findall(r"^        if: (.*)$", self.text, re.MULTILINE)
        self.assertEqual(conditions, ["${{ inputs.dryRun }}", "${{ !inputs.dryRun }}"])
        self.assertIn("if: ${{ inputs.dryRun }}", step(self.text, DRY_RUN_SIGN))
        self.assertIn("if: ${{ !inputs.dryRun }}", step(self.text, "Upload to Maven Central for a manual Publish"))

    def test_dry_run_signs_with_the_pinned_artifact_key_and_uploads_nothing(self) -> None:
        sign = step(self.text, DRY_RUN_SIGN)
        self.assertIn("./gradlew :elu-analytics:signMavenPublication --no-configuration-cache --no-parallel", sign)
        self.assertIn("RELEASE_TAG: ${{ inputs.tag }}", sign)
        self.assertIn("^ARTIFACT_SIGNING_FINGERPRINT = ", sign)
        self.assertIn("scripts/verify-release-publication.py", sign)
        self.assertIn("VALIDSIG", sign)
        for forbidden in ("stageCentralRelease", "publishToMavenCentral", "MAVEN_CENTRAL"):
            self.assertNotIn(forbidden, sign)
        self.assertLess(self.text.index("bash scripts/check-publication-graph.sh"), self.text.index(DRY_RUN_SIGN))

    def test_upload_is_core_only_and_never_releases_automatically(self) -> None:
        upload = step(self.text, "Upload to Maven Central for a manual Publish")
        self.assertIn("--no-configuration-cache --no-parallel", upload)
        self.assertIn("RELEASE_TAG: ${{ inputs.tag }}", upload)
        self.assertIn("ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS:", upload)
        for forbidden in ("publishAndReleaseToMavenCentral", "mavenCentralAutomaticPublishing",
                          ":elu-analytics-compose:publish"):
            self.assertNotIn(forbidden, self.text)
        self.assertLess(self.text.index("bash scripts/check-publication-graph.sh"), self.text.index(UPLOAD))

    def test_feature_flag_isolation_runs_before_upload(self) -> None:
        self.assertLess(self.text.index("checkFeatureFlagBoundary"), self.text.index(UPLOAD))

    def test_compose_still_builds_and_is_checked_before_the_core_upload(self) -> None:
        upload = self.text.index(UPLOAD)
        for command in (":elu-analytics-compose:assembleRelease", ":elu-analytics-compose:cyclonedxDirectBom",
                        "fixtures/compose-consumer assembleDebug -PeluMetadataMode=module",
                        "fixtures/compose-consumer assembleDebug -PeluMetadataMode=pom",
                        "--compose-aar elu-analytics-compose/build/outputs/aar/elu-analytics-compose-release.aar",
                        "--input compose-dependencies=build/reports/compose-release-runtime-classpath.txt"):
            self.assertLess(self.text.index(command), upload)

    def test_publication_secrets_reach_only_the_upload_step(self) -> None:
        upload = step(self.text, "Upload to Maven Central for a manual Publish")
        for secret in ("MAVEN_CENTRAL_USERNAME }}", "MAVEN_CENTRAL_PASSWORD }}"):
            self.assertEqual(self.text.count("secrets." + secret), 1, secret)
            self.assertEqual(upload.count("secrets." + secret), 1, secret)

    def test_signing_secrets_reach_only_steps_that_never_run_together(self) -> None:
        # The dry-run signing step and the upload are mutually exclusive by their conditions.
        upload = step(self.text, "Upload to Maven Central for a manual Publish")
        sign = step(self.text, DRY_RUN_SIGN)
        for secret in ("SIGNING_KEY }}", "SIGNING_KEY_PASSWORD }}"):
            self.assertEqual(self.text.count("secrets." + secret), 2, secret)
            self.assertEqual(upload.count("secrets." + secret), 1, secret)
            self.assertEqual(sign.count("secrets." + secret), 1, secret)


if __name__ == "__main__":
    unittest.main()
