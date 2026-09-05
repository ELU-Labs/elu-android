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
        publish = text.index("./gradlew publishAndReleaseToMavenCentral")
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

    def test_runtime_network_evidence_is_opt_in(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("runtimeNetworkEvidence:", text)
        self.assertIn("replayNetworkEvidence:", text)
        require = text.index("name: Require generated Android runtime-network evidence")
        self.assertIn("if: ${{ inputs.runtimeNetworkEvidence }}", text[require:])
        self.assertIn('if [[ "$REPLAY_EVIDENCE" == "true" ]]; then', text)
        self.assertIn('scanner_inputs+=(--network runtime=', text)
        self.assertNotIn("--expect replay=1 \\", text)

    def test_dry_run_skips_only_the_publish_step(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        publish = text.index("name: Publish and release")
        self.assertIn("if: ${{ !inputs.dryRun }}", text[publish:])
        self.assertEqual(1, text.count("inputs.dryRun"))

    def test_feature_flag_isolation_runs_before_publish(self) -> None:
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertLess(
            text.index("checkFeatureFlagBoundary"),
            text.index("./gradlew publishAndReleaseToMavenCentral"),
        )


if __name__ == "__main__":
    unittest.main()
