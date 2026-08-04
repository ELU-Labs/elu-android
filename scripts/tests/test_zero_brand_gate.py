from __future__ import annotations

import json
import pathlib
import subprocess
import sys
import tempfile
import unittest
import zipfile

SCRIPT = pathlib.Path(__file__).resolve().parents[1] / "zero-brand-gate.py"
TOKEN = "VendorWord"


class ZeroBrandGateTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory(prefix="elu-scanner-test-")
        self.root = pathlib.Path(self.temp.name)
        subprocess.run(["git", "init", "-q"], cwd=self.root, check=True)
        legal = self.root / "legal" / "THIRD_PARTY_NOTICES.md"
        legal.parent.mkdir()
        legal.write_text(
            "<!-- zero-brand-token-start -->\n"
            f"{TOKEN}\n"
            "<!-- zero-brand-token-end -->\n",
            encoding="utf-8",
        )
        allowlist = self.root / "network-allowlist.json"
        allowlist.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "domains": [{"host": "elu.dev", "includeSubdomains": True}],
                }
            ),
            encoding="utf-8",
        )

    def tearDown(self) -> None:
        self.temp.cleanup()

    def run_gate(self, *args: str, check: bool = False) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root), "--token-file", str(self.root / "legal" / "THIRD_PARTY_NOTICES.md"), *args],
            cwd=self.root,
            check=check,
            capture_output=True,
            text=True,
        )

    def test_strict_rejects_source_but_allows_legal_file(self) -> None:
        subprocess.run(["git", "add", "legal/THIRD_PARTY_NOTICES.md"], cwd=self.root, check=True)
        self.assertEqual(0, self.run_gate().returncode)

        (self.root / "source.txt").write_text(TOKEN, encoding="utf-8")
        result = self.run_gate()
        self.assertEqual(1, result.returncode)
        self.assertIn("source.txt", result.stdout)

    def test_nested_archive_content_is_scanned(self) -> None:
        archive = self.root / "artifact.aar"
        with zipfile.ZipFile(archive, "w") as outer:
            outer.writestr("classes.txt", f"symbol:{TOKEN}")

        result = self.run_gate("--skip-tree", "--input", f"aar={archive}")
        self.assertEqual(1, result.returncode)
        self.assertIn("aar!/classes.txt", result.stdout)

    def test_legal_named_archive_does_not_hide_nested_content(self) -> None:
        archive = self.root / "LICENSE-evidence.jar"
        with zipfile.ZipFile(archive, "w") as outer:
            outer.writestr("classes.txt", f"symbol:{TOKEN}")

        result = self.run_gate("--skip-tree", "--input", f"LICENSE-evidence.jar={archive}")
        self.assertEqual(1, result.returncode)
        self.assertIn("LICENSE-evidence.jar!/classes.txt", result.stdout)

    def test_legal_filename_does_not_allowlist_forbidden_parent_path(self) -> None:
        disguised_legal = self.root / TOKEN / "LICENSE.txt"
        disguised_legal.parent.mkdir()
        disguised_legal.write_text("otherwise valid legal text", encoding="utf-8")

        result = self.run_gate()
        self.assertEqual(1, result.returncode)
        self.assertIn(f"path: {TOKEN}/LICENSE.txt", result.stdout)

    def test_nested_legal_filename_does_not_allowlist_forbidden_parent_path(self) -> None:
        archive = self.root / "artifact.aar"
        with zipfile.ZipFile(archive, "w") as outer:
            outer.writestr(f"{TOKEN}/LICENSE.txt", "otherwise valid legal text")

        result = self.run_gate("--skip-tree", "--input", f"aar={archive}")
        self.assertEqual(1, result.returncode)
        self.assertIn(f"path: aar!/{TOKEN}/LICENSE.txt", result.stdout)

    def test_network_trace_requires_https_and_label_boundary_domain(self) -> None:
        trace = self.root / "network.json"
        allowlist = self.root / "network-allowlist.json"
        trace.write_text(
            json.dumps({"urls": ["https://ingest.elu.dev/v1/events", "https://elu.dev/v1/config"]}),
            encoding="utf-8",
        )
        allowed = self.run_gate(
            "--skip-tree",
            "--network-allowlist",
            str(allowlist),
            "--network",
            f"trace={trace}",
        )
        self.assertEqual(0, allowed.returncode, allowed.stdout + allowed.stderr)

        for rejected_url in (
            "http://ingest.elu.dev/v1/events",
            "https://elu.dev.evil.example/v1/events",
            "https://notelu.dev/v1/events",
        ):
            trace.write_text(json.dumps({"url": rejected_url}), encoding="utf-8")
            rejected = self.run_gate(
                "--skip-tree",
                "--network-allowlist",
                str(allowlist),
                "--network",
                f"trace={trace}",
            )
            self.assertEqual(1, rejected.returncode, rejected_url)

    def test_network_trace_rejects_empty_or_no_url_evidence(self) -> None:
        trace = self.root / "network.json"
        allowlist = self.root / "network-allowlist.json"
        for contents in ("", json.dumps({"requests": []}), json.dumps({"status": "blocked"})):
            trace.write_text(contents, encoding="utf-8")
            result = self.run_gate(
                "--skip-tree",
                "--network-allowlist",
                str(allowlist),
                "--network",
                f"trace={trace}",
            )
            self.assertEqual(1, result.returncode)
            self.assertIn("trace contains no URLs", result.stdout)

        empty_directory = self.root / "empty-network"
        empty_directory.mkdir()
        result = self.run_gate(
            "--skip-tree",
            "--network-allowlist",
            str(allowlist),
            "--network",
            f"trace={empty_directory}",
        )
        self.assertEqual(1, result.returncode)
        self.assertIn("trace contains no files", result.stdout)

    def test_ratchet_accepts_known_debt_and_rejects_new_debt(self) -> None:
        (self.root / "legacy.txt").write_text(TOKEN, encoding="utf-8")
        emitted = self.run_gate("--emit-baseline", check=True)
        baseline = self.root / "baseline.json"
        baseline.write_text(emitted.stdout, encoding="utf-8")

        self.assertEqual(0, self.run_gate("--mode", "ratchet", "--baseline", str(baseline)).returncode)
        (self.root / "new.txt").write_text(TOKEN, encoding="utf-8")
        self.assertEqual(1, self.run_gate("--mode", "ratchet", "--baseline", str(baseline)).returncode)
        self.assertEqual(1, json.loads(emitted.stdout)["schemaVersion"])


if __name__ == "__main__":
    unittest.main()
