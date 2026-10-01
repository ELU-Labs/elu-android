from __future__ import annotations

import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[2]
WORKFLOW = ROOT / ".github" / "workflows" / "release.yml"


class ReleaseWorkflowTest(unittest.TestCase):
    def test_resolved_runtime_graph_is_generated_and_strictly_scanned(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        generate = text.index(
            "./gradlew :elu-analytics:dependencies --configuration releaseRuntimeClasspath"
        )
        strict_input = text.index(
            "--input dependencies=build/reports/release-runtime-classpath.txt"
        )
        publish = text.index("./gradlew publishPairedRelease")
        self.assertLess(generate, strict_input)
        self.assertLess(strict_input, publish)

    def test_generated_sbom_outputs_join_the_strict_scan_inputs(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("-iname '*sbom*'", text)
        self.assertIn('--input "sbom-${sbom_index}=${sbom}"', text)

    def test_sbom_is_generated_before_the_strict_scan(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        generate = text.index(":elu-analytics:cyclonedxDirectBom")
        scan = text.index("-iname '*sbom*'")
        self.assertLess(generate, scan)

    def test_runtime_network_evidence_requires_native_replay_before_publish(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("runtimeNetworkEvidence", text)
        self.assertNotIn("replayNetworkEvidence", text)
        require = text.index("name: Require generated Android runtime-network evidence")
        verify = text.index("name: Verify public API and legal-only artifact gate")
        self.assertNotIn("if:", text[require:verify])
        self.assertNotIn("REPLAY_EVIDENCE", text)
        self.assertNotIn("if ", text[require:verify])
        self.assertIn('--tag "$RELEASE_TAG"', text[require:verify])
        self.assertIn('--distribution build/compose-distribution', text[require:verify])
        self.assertIn('verify-paired-publication.py --tag "$RELEASE_TAG"', text[require:verify])
        self.assertNotIn('--aar ', text[require:verify])
        acquire = text.index("name: Acquire reviewed draft Lab evidence")
        signed = text.index("name: Verify matching reviewed signed tag")
        build = text.index("name: Run release gates")
        self.assertLess(signed, acquire)
        self.assertLess(acquire, build)
        self.assertLess(build, require)
        self.assertIn('GH_TOKEN: ${{ github.token }}', text[acquire:build])
        self.assertIn('environment: maven-central-reviewed', text)
        self.assertNotIn('if:', text[acquire:build])
        self.assertNotIn('--expect ', text)
        self.assertIn("scanner_inputs+=(--network runtime=build/reports/android-runtime-network-evidence.json)", text)
        self.assertNotIn('if [[ "$RUNTIME_EVIDENCE" == "true" ]]', text)

    def test_dry_run_skips_only_the_publish_step(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        publish = text.index("name: Publish and release")
        self.assertIn("if: ${{ !inputs.dryRun }}", text[publish:])
        self.assertEqual(1, text.count("inputs.dryRun"))

    def test_feature_flag_isolation_runs_before_publish(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertLess(
            text.index("checkFeatureFlagBoundary"),
            text.index("./gradlew publishPairedRelease"),
        )

    def test_compose_build_consumers_api_and_scan_precede_paired_release(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        publish = text.index("./gradlew publishPairedRelease")
        for command in (":elu-analytics-compose:assembleRelease", ":elu-analytics-compose:cyclonedxDirectBom",
                        "fixtures/compose-consumer assembleDebug -PeluMetadataMode=module",
                        "fixtures/compose-consumer assembleDebug -PeluMetadataMode=pom",
                        "--compose-aar elu-analytics-compose/build/outputs/aar/elu-analytics-compose-release.aar",
                        "--input compose-dependencies=build/reports/compose-release-runtime-classpath.txt"):
            self.assertLess(text.index(command), publish)
        self.assertIn('--no-configuration-cache --no-parallel', text[publish:])
        self.assertIn('RELEASE_TAG: ${{ inputs.tag }}', text[publish:])
        self.assertIn('ELU_TRUSTED_RELEASE_SIGNING_FINGERPRINTS:', text[publish:])


if __name__ == "__main__":
    unittest.main()
