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


if __name__ == "__main__":
    unittest.main()
