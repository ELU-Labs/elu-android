"""Pure boundary tests; signed-tag integration tests remain in test_verify_release_tag."""
from __future__ import annotations
import importlib.util
import pathlib
import unittest
from unittest.mock import patch
from test_validate_runtime_network_evidence import paired_protocol_fixture, encoded, binding, evidence

spec = importlib.util.spec_from_file_location("signed_release_tested", pathlib.Path(__file__).resolve().parents[1] / "verify-release-tag.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


class ReleaseEvidenceTagBindingTest(unittest.TestCase):
    def verify_message(self, message):
        def observed(*args):
            if args[:2] == ("cat-file", "-t"): return "tag"
            if args[:2] == ("cat-file", "tag"):
                return "object " + "b" * 40 + "\ntype commit\ntag 0.2.0\n\n" + message + "\n-----BEGIN PGP SIGNATURE-----\nunit-mocked-signature\n-----END PGP SIGNATURE-----\n"
            if args[0] == "rev-parse": return "b" * 40
            if args[0] == "status": return ""
            raise AssertionError(args)
        with patch.object(release, "git", observed), patch.object(release, "trusted_fingerprints", return_value={"A" * 40}), \
                patch.object(release, "verified_signature_fingerprints", return_value={"A" * 40}), \
                patch.object(release.VERSION_SOURCE.__class__, "read_text", return_value='const val NAME: String = "0.2.0"'):
            return release.verify_release("0.2.0")

    def test_exact_one_digest_is_returned_only_after_existing_tag_checks(self):
        result = self.verify_message("Release\n\nReviewed-by: SDK Owner\nAndroid-Lab-Evidence-SHA256: " + "a" * 64)
        self.assertEqual({"tag": "0.2.0", "sourceCommit": "b" * 40, "evidenceSha256": "a" * 64, "signer": "A" * 40}, result)

    def test_missing_duplicate_malformed_and_wrong_case_trailers_fail_closed(self):
        prefix = "Release\n\nReviewed-by: SDK Owner\n"
        good = "Android-Lab-Evidence-SHA256: " + "a" * 64
        for body in ("", good + "\n" + good, good + " ", good[:-1], good.lower(),
                     "Android-Lab-Evidence-SHA256: " + "A" * 64, "Android-Lab-Evidence-SHA256: url"):
            with self.subTest(body=body), self.assertRaisesRegex(SystemExit, "exactly one"):
                self.verify_message(prefix + body)

    def test_missing_review_still_rejects_even_with_digest(self):
        with self.assertRaisesRegex(SystemExit, "Reviewed-by"):
            self.verify_message("Android-Lab-Evidence-SHA256: " + "a" * 64)

    def test_original_signed_digest_binds_full_pair_and_each_original_profile(self):
        value = paired_protocol_fixture(); data = encoded(value)
        trusted = self.verify_message("Release\n\nReviewed-by: SDK Owner\nAndroid-Lab-Evidence-SHA256: " + binding(data)["evidenceSha256"])
        self.assertEqual(value, evidence.validate(data, trusted))
        value["scope"]["profiles"]["views"]["observations"]["sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "trusted signed tag"):
            evidence.validate(encoded(value), trusted)

    def test_cli_keeps_core_and_paired_modes_mutually_exclusive_before_tag_access(self):
        spec = importlib.util.spec_from_file_location("release_cli_tested",
            pathlib.Path(__file__).resolve().parents[1] / "validate-runtime-network-evidence.py")
        cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
        import contextlib, io
        for args in (["check", "evidence", "--tag", "0.2.0"],
                     ["check", "evidence", "--tag", "0.2.0", "--aar", "core", "--distribution", "pair"]):
            with patch("sys.argv", args), patch.object(cli.evidence, "signed_binding", side_effect=AssertionError("no tag read")), \
                    contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit) as result:
                cli.main()
            self.assertEqual(2, result.exception.code)

    def test_cli_preserves_selected_original_path_and_signed_binding(self):
        spec = importlib.util.spec_from_file_location("release_cli_selected",
            pathlib.Path(__file__).resolve().parents[1] / "validate-runtime-network-evidence.py")
        cli = importlib.util.module_from_spec(spec); spec.loader.exec_module(cli)
        import contextlib, io
        for flag in ("--aar", "--distribution"):
            trusted = {"original": "already-mocked-signature"}; raw = b"original export bytes"
            with patch("sys.argv", ["check", "evidence", "--tag", "0.2.0", flag, "original-input"]), \
                    patch.object(cli.evidence, "signed_binding", return_value=trusted) as tag, \
                    patch.object(cli.evidence, "read_regular", return_value=raw), \
                    patch.object(cli.evidence, "validate", return_value={"source": {"version": "0.2.0"}}) as validate, \
                    contextlib.redirect_stdout(io.StringIO()) as output:
                cli.main()
            tag.assert_called_once_with("0.2.0")
            validate.assert_called_once_with(raw, trusted, pathlib.Path("original-input") if flag == "--aar" else None,
                                            distribution=pathlib.Path("original-input") if flag == "--distribution" else None)
            self.assertIn("publication not authorized", output.getvalue())


if __name__ == "__main__":
    unittest.main()
