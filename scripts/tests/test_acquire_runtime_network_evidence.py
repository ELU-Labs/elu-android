from __future__ import annotations

import copy
import importlib.util
import json
import pathlib
import tempfile
import unittest
from unittest.mock import patch
from test_validate_runtime_network_evidence import protocol_fixture, paired_protocol_fixture, encoded, binding

spec = importlib.util.spec_from_file_location("acquire_tested", pathlib.Path(__file__).resolve().parents[1] / "acquire-runtime-network-evidence.py")
acquire = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acquire)


class AcquireRuntimeNetworkEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.data = encoded(protocol_fixture())
        self.binding = binding(self.data)
        self.meta = {"id": 4, "url": acquire.API + "/releases/4", "tag_name": "0.2.0", "draft": True,
                     "assets": [{"id": 5, "name": acquire.evidence.ASSET_NAME, "url": acquire.API + "/releases/assets/5",
                                 "size": len(self.data), "digest": "sha256:" + self.binding["evidenceSha256"],
                                 "state": "uploaded", "content_type": "application/json"}]}
        self.calls = []

    def fetch(self, url, *, binary=False):
        self.calls.append((url, binary))
        return self.data if binary else encoded(self.meta)

    def test_only_reviewed_same_repo_asset_is_written_exactly_and_create_only(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "result.json"
            acquire.acquire(self.binding, path, self.fetch)
            self.assertEqual(self.data, path.read_bytes())
            self.assertEqual([(acquire.API + "/releases/tags/0.2.0", False),
                              (acquire.API + "/releases/assets/5", True),
                              (acquire.API + "/releases/tags/0.2.0", False)], self.calls)
            with self.assertRaises(FileExistsError):
                acquire.acquire(self.binding, path, self.fetch)
            self.assertEqual(self.data, path.read_bytes())

    def test_absent_ambiguous_unpublished_or_wrong_repository_metadata_rejected(self):
        mutations = [lambda x: x.update(draft=False), lambda x: x.update(tag_name="0.1.0"),
                     lambda x: x.update(url="https://api.github.com/repos/other/repo/releases/4"),
                     lambda x: x.update(assets=[]), lambda x: x["assets"].append(x["assets"][0]),
                     lambda x: x["assets"][0].update(size=65537), lambda x: x["assets"][0].update(id=True),
                     lambda x: x["assets"][0].update(state="new"),
                     lambda x: x["assets"][0].update(digest="sha256:" + "0" * 64),
                     lambda x: x["assets"][0].update(url="https://untrusted.example/file")]
        for mutation in mutations:
            with self.subTest(mutation=mutation), tempfile.TemporaryDirectory() as directory:
                original = copy.deepcopy(self.meta)
                mutation(self.meta)
                path = pathlib.Path(directory) / "result.json"
                with self.assertRaises(ValueError):
                    acquire.acquire(self.binding, path, self.fetch)
                self.assertFalse(path.exists())
                self.meta = original

    def test_changed_asset_incomplete_digest_and_oversized_download_never_written(self):
        for change in ("metadata", "bytes", "incomplete", "oversized"):
            calls = 0
            def fetch(url, *, binary=False):
                nonlocal calls
                calls += 1
                if change == "metadata" and calls == 3:
                    changed = copy.deepcopy(self.meta)
                    changed["assets"][0]["id"] = 9
                    return encoded(changed)
                if binary:
                    if change == "bytes": return self.data[:-1] + b" "
                    if change == "oversized": return self.data * 100
                    if change == "incomplete": return encoded({"finalCertification": False})
                return self.fetch(url, binary=binary)
            with tempfile.TemporaryDirectory() as directory:
                path = pathlib.Path(directory) / "result.json"
                with self.assertRaises(ValueError):
                    acquire.acquire(self.binding, path, fetch)
                self.assertFalse(path.exists())

    def test_http_redirect_never_forwards_authorization(self):
        requests = []
        responses = [(302, {"Location": "https://release-assets.githubusercontent.com/path?signature=private"}, b""),
                     (200, {"Content-Length": "2"}, b"{}")]
        class Response:
            def __init__(self, status, headers, body): self.status, self.headers, self.body = status, headers, body
            def getheader(self, key, default=None): return self.headers.get(key, default)
            def read1(self, size): chunk, self.body = self.body[:size], self.body[size:]; return chunk
        class Connection:
            sock = None
            def __init__(self, host, **kwargs): self.host = host
            def request(self, method, path, headers): requests.append((self.host, headers))
            def getresponse(self): return Response(*responses.pop(0))
            def close(self): pass
        with patch.object(acquire.http.client, "HTTPSConnection", Connection):
            self.assertEqual(b"{}", acquire.GitHubReader("secret")(acquire.API + "/releases/assets/5", binary=True))
        self.assertEqual("Bearer secret", requests[0][1]["Authorization"])
        self.assertNotIn("Authorization", requests[1][1])

    def test_network_reader_rejects_redirect_escapes_sizes_encoding_and_deadline(self):
        for status, headers, body in ((302, {"Location": "http://release-assets.githubusercontent.com/file"}, b""),
                                     (302, {"Location": "https://evil.example/file"}, b""),
                                     (302, {"Location": "https://secret@objects.githubusercontent.com/file"}, b""),
                                     (200, {"Content-Length": "65537"}, b""),
                                     (200, {"Content-Encoding": "gzip"}, b"compressed"),
                                     (200, {"Content-Length": "8"}, b"short"),
                                     (200, {}, b"x" * 65537)):
            class Response:
                def __init__(self): self.status, self.body = status, body
                def getheader(self, key, default=None): return headers.get(key, default)
                def read1(self, size): chunk, self.body = self.body[:size], self.body[size:]; return chunk
            class Connection:
                sock = None
                def __init__(self, *args, **kwargs): pass
                def request(self, *args, **kwargs): pass
                def getresponse(self): return Response()
                def close(self): pass
            with patch.object(acquire.http.client, "HTTPSConnection", Connection), self.assertRaises(ValueError):
                acquire.GitHubReader("secret")(acquire.API + "/releases/assets/5", binary=True)
        reader = acquire.GitHubReader("secret")
        reader.deadline = 0
        with self.assertRaisesRegex(ValueError, "deadline"):
            reader(acquire.API + "/releases/assets/5", binary=True)


class AcquirePairedRuntimeNetworkEvidenceTest(AcquireRuntimeNetworkEvidenceTest):
    """Repeat original authenticated acquisition refusals for the new signed version."""
    def setUp(self):
        super().setUp()
        self.data = encoded(paired_protocol_fixture())
        self.binding = binding(self.data)
        self.meta["assets"][0].update(size=len(self.data), digest="sha256:" + self.binding["evidenceSha256"])

    def test_acquired_paired_envelope_cannot_pass_existing_core_publication_check(self):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / "result.json"
            acquire.acquire(self.binding, path, self.fetch)
            with self.assertRaisesRegex(ValueError, "core-only"):
                acquire.evidence.validate(path.read_bytes(), self.binding, pathlib.Path(directory) / "not-read.aar")
            self.assertEqual(self.data, path.read_bytes())


if __name__ == "__main__":
    unittest.main()
