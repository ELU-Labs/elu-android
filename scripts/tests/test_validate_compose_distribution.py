from __future__ import annotations

import hashlib
import importlib.util
import io
import json
import pathlib
import tempfile
import unittest
import zipfile

REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("compose_distribution", REPOSITORY / "scripts/validate-compose-distribution.py")
assert SPEC and SPEC.loader
DIST = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(DIST)


def archive(entries: dict) -> bytes:
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as value:
        for name, data in entries.items():
            value.writestr(name, data)
    return output.getvalue()


class ComposeDistributionTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="elu-compose-distribution-")
        self.root = pathlib.Path(self.temporary.name)
        self.write("elu-analytics/src/main/kotlin/dev/elu/analytics/EluVersion.kt", b'const val NAME: String = "0.2.0"')
        for module in ("elu-analytics", "elu-analytics-compose"):
            self.fixture(module)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def write(self, relative: str, data: bytes) -> pathlib.Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def fixture(self, module: str) -> None:
        prefix = module + "/build/"
        values = {".aar": archive({"AndroidManifest.xml": b"manifest", "classes.jar": b"classes"}),
                  "-sources.jar": archive({"Example.kt": b"class Example"}),
                  "-javadoc.jar": archive({"index.html": b"documentation"})}
        paths = {".aar": f"outputs/aar/{module}-release.aar", "-sources.jar": "intermediates/source_jar/release/release-sources.jar",
                 "-javadoc.jar": "intermediates/java_doc_jar/release/release-javadoc.jar"}
        for suffix, data in values.items():
            self.write(prefix + paths[suffix], data)
        deps = [("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.20", "compile")]
        if module == "elu-analytics":
            deps += [("com.squareup.okhttp3", "okhttp", "4.12.0", "runtime")]
        else:
            deps += [("dev.elu", "elu-analytics", "0.2.0", "compile"),
                     ("androidx.compose.ui", "ui", "1.7.8", "compile"),
                     ("androidx.compose.foundation", "foundation", "1.7.8", "runtime")]
        dep_xml = "".join(f"<dependency><groupId>{g}</groupId><artifactId>{a}</artifactId><version>{v}</version><scope>{scope}</scope></dependency>"
                          for g, a, v, scope in deps)
        pom = f'''<project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
<groupId>dev.elu</groupId><artifactId>{module}</artifactId><version>0.2.0</version><packaging>aar</packaging>
<name>ELU</name><description>Compile fixture</description><url>https://github.com/ELU-Labs/elu-android</url>
<licenses><license><name>MIT License</name><url>https://opensource.org/license/mit/</url></license></licenses>
<developers><developer><id>ELU-Labs</id></developer></developers><scm><url>https://github.com/ELU-Labs/elu-android</url></scm>
<dependencies>{dep_xml}</dependencies></project>'''
        self.write(prefix + "publications/maven/pom-default.xml", pom.encode())
        variants = []
        for role in ("java-api", "java-runtime", "sources", "javadoc"):
            suffix = ".aar" if role.startswith("java-") else f"-{role}.jar"
            data = values[suffix]
            name = f"{module}-0.2.0{suffix}"
            attrs = {"org.gradle.category": "library" if role.startswith("java-") else "documentation"}
            attrs["org.gradle.usage" if role.startswith("java-") else "org.gradle.docstype"] = role
            entries = [{"group": g, "module": a, "version": {"requires": v}} for g, a, v, scope in deps
                       if role == "java-runtime" or (role == "java-api" and scope == "compile")]
            variants.append({"name": role, "attributes": attrs, "dependencies": entries,
                             "files": [{"name": name, "url": name, "size": len(data),
                                        **{algorithm: hashlib.new(algorithm, data).hexdigest()
                                           for algorithm in ("sha512", "sha256", "sha1", "md5")}}]})
        metadata = {"formatVersion": "1.1", "component": {"group": "dev.elu", "module": module, "version": "0.2.0"},
                    "variants": variants}
        self.write(prefix + "publications/maven/module.json", json.dumps(metadata).encode())
        sbom = {"bomFormat": "CycloneDX", "metadata": {"component": {"group": "dev.elu", "name": module, "version": "0.2.0"}},
                "components": [{"group": g, "name": a, "version": v} for g, a, v, _ in deps]}
        self.write(prefix + f"reports/sbom/{module}-release-sbom.json", json.dumps(sbom).encode())

    def optional_path(self, name: str) -> pathlib.Path:
        return self.root / "elu-analytics-compose/build/publications/maven" / name

    def edit_metadata(self, mutate) -> None:
        path = self.optional_path("module.json")
        value = json.loads(path.read_bytes()); mutate(value); path.write_text(json.dumps(value))

    def test_exact_original_bytes_stage_once_and_verify_without_runtime_claim(self) -> None:
        expected_pom = self.optional_path("pom-default.xml").read_bytes()
        receipt = DIST.stage(self.root)
        self.assertEqual(receipt["version"], "0.2.0")
        self.assertFalse(receipt["runtimeQualified"])
        self.assertEqual(len(receipt["files"]), 12)
        self.assertEqual(self.root.joinpath("build/compose-distribution/repository/dev/elu/elu-analytics-compose/0.2.0/elu-analytics-compose-0.2.0.pom").read_bytes(), expected_pom)
        self.assertEqual(DIST.stage(self.root, True), receipt)
        with self.assertRaises(FileExistsError):
            DIST.stage(self.root)

    def test_matching_core_dependency_required_in_pom(self) -> None:
        path = self.optional_path("pom-default.xml")
        original = path.read_text()
        for replacement in ("0.1.0", "unspecified", "[0.1,)"):
            with self.subTest(version=replacement):
                path.write_text(original.replace("<artifactId>elu-analytics</artifactId><version>0.2.0", f"<artifactId>elu-analytics</artifactId><version>{replacement}"))
                with self.assertRaisesRegex(ValueError, "POM exact dependency"):
                    DIST.collect(self.root)
        path.write_text(original)

    def test_missing_foundation_and_duplicate_pom_dependency_rejected(self) -> None:
        path = self.optional_path("pom-default.xml"); original = path.read_text()
        start = original.index("<dependency><groupId>androidx.compose.foundation")
        end = original.index("</dependency>", start) + len("</dependency>")
        entry = original[start:end]
        for candidate in (original.replace(entry, ""), original.replace(entry, entry + entry)):
            path.write_text(candidate)
            with self.assertRaises(ValueError):
                DIST.collect(self.root)

    def test_wrong_gmm_core_version_and_missing_api_dependency_refused(self) -> None:
        path = self.optional_path("module.json"); original = path.read_bytes()
        for change in (lambda v: v["variants"][0]["dependencies"][1]["version"].update(requires="0.1.0"),
                       lambda v: v["variants"][0]["dependencies"].pop(1)):
            path.write_bytes(original); self.edit_metadata(change)
            with self.assertRaises(ValueError):
                DIST.collect(self.root)

    def test_gmm_indirection_or_unexpected_test_dependency_refused(self) -> None:
        path = self.optional_path("module.json"); original = path.read_bytes()
        for change in (lambda v: v["variants"][0].update({"available-at": {"url": "https://elsewhere"}}),
                       lambda v: v["variants"][1]["dependencies"].append({"group": "androidx.test", "module": "runner", "version": {"requires": "1.6.2"}})):
            path.write_bytes(original); self.edit_metadata(change)
            with self.assertRaises(ValueError):
                DIST.collect(self.root)

    def test_stale_aar_metadata_digest_refused_before_stage_creation(self) -> None:
        self.write("elu-analytics-compose/build/outputs/aar/elu-analytics-compose-release.aar",
                   archive({"AndroidManifest.xml": b"manifest", "classes.jar": b"different classes"}))
        with self.assertRaisesRegex(ValueError, "Gradle artifact"):
            DIST.stage(self.root)
        self.assertFalse(self.root.joinpath("build/compose-distribution").exists())

    def test_gmm_external_or_traversing_file_refused(self) -> None:
        path = self.optional_path("module.json"); original = path.read_bytes()
        for url in ("../../outside.aar", "https://example.invalid/file.aar", "another.aar"):
            path.write_bytes(original); self.edit_metadata(lambda v: v["variants"][0]["files"][0].update(url=url))
            with self.assertRaisesRegex(ValueError, "Gradle artifact path"):
                DIST.collect(self.root)

    def test_missing_source_javadoc_sbom_and_extra_publication_refused(self) -> None:
        paths = ["intermediates/source_jar/release/release-sources.jar",
                 "intermediates/java_doc_jar/release/release-javadoc.jar", "reports/sbom/elu-analytics-compose-release-sbom.json"]
        for name in paths:
            path = self.root / "elu-analytics-compose/build" / name; data = path.read_bytes(); path.unlink()
            with self.assertRaises(ValueError):
                DIST.collect(self.root)
            path.write_bytes(data)
        self.optional_path("../unexpected").mkdir()
        with self.assertRaisesRegex(ValueError, "publication set"):
            DIST.collect(self.root)

    def test_sbom_must_include_core_and_reject_tests(self) -> None:
        path = self.root / "elu-analytics-compose/build/reports/sbom/elu-analytics-compose-release-sbom.json"
        original = path.read_bytes()
        for mutate in (lambda v: v["components"].pop(1),
                       lambda v: v["components"].append({"group": "androidx.test", "name": "runner", "version": "1.6.2"})):
            value = json.loads(original); mutate(value); path.write_text(json.dumps(value))
            with self.assertRaisesRegex(ValueError, "SBOM"):
                DIST.collect(self.root)

    def test_duplicate_json_key_and_pom_repository_refused(self) -> None:
        path = self.optional_path("module.json"); original = path.read_bytes()
        path.write_bytes(original.replace(b'"formatVersion": "1.1"', b'"formatVersion": "1.1", "formatVersion": "1.1"'))
        with self.assertRaisesRegex(ValueError, "duplicate JSON"):
            DIST.collect(self.root)
        path.write_bytes(original)
        pom = self.optional_path("pom-default.xml")
        pom.write_text(pom.read_text().replace("</project>", "<repositories/></project>"))
        with self.assertRaisesRegex(ValueError, "POM indirection"):
            DIST.collect(self.root)

    def test_linked_input_and_changed_staged_bytes_refused(self) -> None:
        path = self.optional_path("pom-default.xml"); data = path.read_bytes(); path.unlink()
        target = self.write("original-pom.xml", data); path.symlink_to(target)
        with self.assertRaisesRegex(ValueError, "linked"):
            DIST.collect(self.root)
        path.unlink(); path.write_bytes(data)
        DIST.stage(self.root)
        staged = self.root / "build/compose-distribution/version.txt"; staged.write_text("0.1.0\n")
        with self.assertRaisesRegex(ValueError, "staged bytes changed"):
            DIST.stage(self.root, True)

    def test_extra_staged_artifact_refused(self) -> None:
        DIST.stage(self.root)
        self.write("build/compose-distribution/repository/dev/elu/unexpected.aar", b"unqualified")
        with self.assertRaisesRegex(ValueError, "staged membership"):
            DIST.stage(self.root, True)

    def test_workflow_keeps_two_modes_and_two_artifact_evidence(self) -> None:
        source = (REPOSITORY / ".github/workflows/ci.yml").read_text()
        for token in (":elu-analytics-compose:generatePomFileForMavenPublication",
                      ":elu-analytics-compose:generateMetadataFileForMavenPublication", ":elu-analytics-compose:cyclonedxDirectBom",
                      "-PeluMetadataMode=module", "-PeluMetadataMode=pom", "validate-compose-distribution.py --verify-staged",
                      "--input compose-aar=", "--input compose-publication=", "--input compose-sources=",
                      "--input compose-javadoc=", "--input compose-dependencies=", "--input compose-sbom="):
            self.assertIn(token, source)
        self.assertNotIn("publishAndRelease", source)
        self.assertIn("api-level: [29, 35]", source)


if __name__ == "__main__":
    unittest.main()
