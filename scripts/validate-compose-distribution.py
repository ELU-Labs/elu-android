#!/usr/bin/env python3
"""Validate and stage two exact local Maven distributions; never publish or qualify runtime."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import pathlib
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
MODULES = ("elu-analytics", "elu-analytics-compose")
MAX_FILE_BYTES = 32 * 1024 * 1024
MAX_TOTAL_BYTES = 128 * 1024 * 1024
POM_NS = "{http://maven.apache.org/POM/4.0.0}"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def read_file(path: pathlib.Path) -> bytes:
    require(not path.is_symlink() and path.is_file(), "missing or linked distribution file")
    require(0 < path.stat().st_size <= MAX_FILE_BYTES, "distribution file size")
    with path.open("rb") as source:
        data = source.read(MAX_FILE_BYTES + 1)
    require(0 < len(data) <= MAX_FILE_BYTES, "distribution file size")
    return data


def decode_json(data: bytes) -> dict:
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key")
            result[key] = value
        return result
    value = json.loads(data.decode("utf-8"), object_pairs_hook=unique,
                       parse_constant=lambda _: require(False, "nonfinite JSON"))
    require(type(value) is dict, "JSON object required")
    return value


def version(root: pathlib.Path) -> str:
    source = read_file(root / "elu-analytics/src/main/kotlin/dev/elu/analytics/EluVersion.kt").decode("utf-8")
    values = re.findall(r'const val NAME: String = "([^"]+)"', source)
    require(len(values) == 1 and re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[A-Za-z0-9.-]+)?", values[0]), "SDK version")
    return values[0]


def dependencies(module: str, candidate: str) -> dict:
    result = {("org.jetbrains.kotlin", "kotlin-stdlib"): ("2.1.20", "compile")}
    if module == "elu-analytics":
        result[("com.squareup.okhttp3", "okhttp")] = ("4.12.0", "runtime")
    else:
        result.update({("dev.elu", "elu-analytics"): (candidate, "compile"),
                       ("androidx.compose.ui", "ui"): ("1.7.8", "compile"),
                       ("androidx.compose.foundation", "foundation"): ("1.7.8", "runtime")})
    return result


def validate_pom(data: bytes, module: str, candidate: str) -> None:
    source = data.decode("utf-8")
    require("\x00" not in source and "<!DOCTYPE" not in source and "<!ENTITY" not in source, "POM entity declaration")
    root = ET.fromstring(source)
    require(root.tag == POM_NS + "project", "POM namespace")
    def scalar(parent, key):
        nodes = parent.findall(POM_NS + key)
        require(len(nodes) == 1 and len(nodes[0]) == 0, "missing or duplicate POM scalar")
        return nodes[0].text
    for key, expected in {"modelVersion": "4.0.0", "groupId": "dev.elu", "artifactId": module,
                          "version": candidate, "packaging": "aar"}.items():
        require(scalar(root, key) == expected, "POM coordinates")
    for key in ("parent", "repositories", "dependencyManagement", "profiles", "relocation"):
        require(not root.findall(".//" + POM_NS + key), "unexpected POM indirection")
    require(scalar(root, "url") == "https://github.com/ELU-Labs/elu-android", "POM project URL")
    require(scalar(root, "name") and scalar(root, "description"), "POM descriptive metadata")
    licenses = root.findall(POM_NS + "licenses/" + POM_NS + "license")
    require(len(licenses) == 1 and scalar(licenses[0], "name") == "MIT License" and
            scalar(licenses[0], "url") == "https://opensource.org/license/mit/", "POM license")
    require(root.find(POM_NS + "developers/" + POM_NS + "developer") is not None and
            root.find(POM_NS + "scm/" + POM_NS + "url") is not None, "POM developer/SCM")
    containers = root.findall(POM_NS + "dependencies")
    require(len(containers) == 1, "POM dependency container")
    actual = {}
    for dep in containers[0]:
        require(dep.tag == POM_NS + "dependency" and
                {child.tag for child in dep} == {POM_NS + k for k in ("groupId", "artifactId", "version", "scope")},
                "POM dependency shape")
        key = (scalar(dep, "groupId"), scalar(dep, "artifactId"))
        require(key not in actual, "duplicate POM dependency")
        actual[key] = (scalar(dep, "version"), scalar(dep, "scope"))
    require(actual == dependencies(module, candidate), "POM exact dependency closure")


def validate_module(data: bytes, module: str, candidate: str, artifacts: dict[str, bytes]) -> None:
    value = decode_json(data)
    require(value.get("formatVersion") == "1.1", "Gradle metadata version")
    component = value.get("component", {})
    require(component.get("group") == "dev.elu" and component.get("module") == module and
            component.get("version") == candidate and "url" not in component, "Gradle metadata coordinates")
    variants = value.get("variants")
    require(type(variants) is list and len(variants) == 4, "Gradle metadata variants")
    expected = dependencies(module, candidate)
    seen = set()
    for variant in variants:
        require(type(variant) is dict and not (set(variant) - {"name", "attributes", "dependencies", "files"}),
                "Gradle variant indirection or constraints")
        attrs = variant.get("attributes", {})
        category = attrs.get("org.gradle.category")
        role = attrs.get("org.gradle.usage") if category == "library" else attrs.get("org.gradle.docstype")
        require(role in {"java-api", "java-runtime", "sources", "javadoc"} and role not in seen, "Gradle variant role")
        seen.add(role)
        require(category == ("library" if role.startswith("java-") else "documentation"), "Gradle variant category")
        actual = {}
        for dep in variant.get("dependencies", []):
            require(type(dep) is dict and set(dep) == {"group", "module", "version"}, "Gradle dependency shape")
            key = (dep["group"], dep["module"])
            require(key not in actual and key in expected, "duplicate or unexpected Gradle dependency")
            desired = expected[key][0]
            constraint = dep["version"]
            require(constraint == {"requires": desired} or
                    (key == ("com.squareup.okhttp3", "okhttp") and
                     constraint == {"requires": desired, "strictly": desired}), "Gradle dependency version")
            actual[key] = desired
        wanted = {k: v for k, (v, scope) in expected.items() if role == "java-runtime" or
                  (role == "java-api" and scope == "compile")}
        require(actual == wanted, "Gradle API/runtime dependency closure")
        suffix = ".aar" if role.startswith("java-") else f"-{role}.jar"
        name = f"{module}-{candidate}{suffix}"
        files = variant.get("files")
        require(type(files) is list and len(files) == 1 and type(files[0]) is dict, "Gradle variant files")
        entry = files[0]
        require(set(entry) == {"name", "url", "size", "sha512", "sha256", "sha1", "md5"} and
                entry["name"] == name and entry["url"] == name, "Gradle artifact path")
        content = artifacts[name]
        require(type(entry["size"]) is int and entry["size"] == len(content), "Gradle artifact size")
        for algorithm in ("sha512", "sha256", "sha1", "md5"):
            require(entry[algorithm] == hashlib.new(algorithm, content).hexdigest(), "Gradle artifact digest")


def validate_sbom(data: bytes, module: str, candidate: str) -> None:
    value = decode_json(data)
    component = value.get("metadata", {}).get("component", {})
    require(value.get("bomFormat") == "CycloneDX" and
            (component.get("group"), component.get("name"), component.get("version")) ==
            ("dev.elu", module, candidate), "SBOM component")
    components = value.get("components")
    require(type(components) is list and 0 < len(components) <= 1024, "SBOM runtime closure")
    ids = {(c.get("group"), c.get("name"), c.get("version")) for c in components}
    require(not any(g and (g.startswith("androidx.test") or "ui-test" in (n or "")) for g, n, _ in ids),
            "SBOM test dependency leakage")
    if module == "elu-analytics-compose":
        require(("dev.elu", "elu-analytics", candidate) in ids and
                any(g == "androidx.compose.ui" and n in {"ui", "ui-android"} and v == "1.7.8" for g, n, v in ids) and
                any(g == "androidx.compose.foundation" and n in {"foundation", "foundation-android"} and v == "1.7.8" for g, n, v in ids),
                "SBOM optional runtime dependencies")
    else:
        require(not any(g and g.startswith("androidx.compose") for g, _, _ in ids), "core SBOM Compose leakage")


def collect(root: pathlib.Path) -> tuple[str, dict[str, bytes]]:
    candidate = version(root)
    output = {}
    total = 0
    for module in MODULES:
        build = root / module / "build"
        publications = build / "publications"
        require(not publications.is_symlink() and {p.name for p in publications.iterdir()} == {"maven"},
                "unexpected publication set")
        prefix = f"repository/dev/elu/{module}/{candidate}/{module}-{candidate}"
        inputs = {".aar": build / f"outputs/aar/{module}-release.aar",
                  "-sources.jar": build / "intermediates/source_jar/release/release-sources.jar",
                  "-javadoc.jar": build / "intermediates/java_doc_jar/release/release-javadoc.jar",
                  ".pom": publications / "maven/pom-default.xml",
                  ".module": publications / "maven/module.json"}
        artifacts = {}
        for suffix, path in inputs.items():
            data = read_file(path)
            total += len(data)
            require(total <= MAX_TOTAL_BYTES, "aggregate distribution bytes")
            output[prefix + suffix] = data
            artifacts[f"{module}-{candidate}{suffix}"] = data
            if suffix.endswith((".aar", ".jar")):
                with zipfile.ZipFile(io.BytesIO(data)) as archive:
                    require(0 < len(archive.infolist()) <= 20_000, "distribution archive membership")
                    if suffix == ".aar":
                        require({"AndroidManifest.xml", "classes.jar"} <= set(archive.namelist()), "AAR membership")
        validate_pom(artifacts[f"{module}-{candidate}.pom"], module, candidate)
        validate_module(artifacts[f"{module}-{candidate}.module"], module, candidate, artifacts)
        sbom = read_file(build / f"reports/sbom/{module}-release-sbom.json")
        total += len(sbom)
        require(total <= MAX_TOTAL_BYTES, "aggregate distribution bytes")
        validate_sbom(sbom, module, candidate)
        output[f"evidence/{module}-sbom.json"] = sbom
    return candidate, output


def stage(root: pathlib.Path, verify_only: bool = False) -> dict:
    candidate, files = collect(root)
    receipt = {"kind": "local-distribution-only", "version": candidate, "runtimeQualified": False,
               "files": {name: {"bytes": len(data), "sha256": hashlib.sha256(data).hexdigest()}
                         for name, data in sorted(files.items())}}
    files["distribution.json"] = (json.dumps(receipt, indent=2) + "\n").encode()
    files["version.txt"] = (candidate + "\n").encode()
    destination = root / "build/compose-distribution"
    if verify_only:
        require(not destination.is_symlink() and destination.is_dir(), "missing staged distribution")
        require({str(p.relative_to(destination)) for p in destination.rglob("*") if p.is_file() or p.is_symlink()} == set(files),
                "unexpected staged membership")
        for name, data in files.items():
            require(read_file(destination / name) == data, "staged bytes changed")
    else:
        require(not destination.parent.is_symlink(), "linked build output directory")
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.mkdir()  # A previous or partial stage is never silently reused or overwritten.
        for name, data in files.items():
            target = destination / name
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as output:
                output.write(data)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=pathlib.Path, default=ROOT)
    parser.add_argument("--verify-staged", action="store_true")
    args = parser.parse_args()
    result = stage(args.root.resolve(), args.verify_staged)
    print(f"local distribution verified: two Maven artifacts at {result['version']}; no runtime or publication qualification")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, ET.ParseError, zipfile.BadZipFile, KeyError, TypeError, RecursionError) as error:
        raise SystemExit(f"Compose distribution rejected ({type(error).__name__}): {error}") from None
