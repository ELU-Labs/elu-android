"""Pure boundary tests; signed-tag integration tests remain in test_verify_release_tag."""
from __future__ import annotations
import importlib.util
import pathlib
import unittest
from unittest.mock import patch

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


if __name__ == "__main__":
    unittest.main()
