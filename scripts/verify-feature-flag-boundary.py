#!/usr/bin/env python3
"""Enforce the exact owned public runtime and owned native capability and authority boundaries."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


PINNED_FILES = {
    # Closed optional AppCompat class identities; no reflective or private API calls.
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/replay/NativeAppCompatViewTypes.kt":
        "0afbc44d70284de8343d7dd28821d8c4ff8393a0f7662c2923403fc89da3aad4",
    "elu-analytics/src/main/AndroidManifest.xml":
        "531cc169655bb89c4544a7a52e03328fb3e24a486c1d5c7e07812b6b9f93aae6",
    # Approved public/config surfaces and the runtime dependency manifest.
    "elu-analytics/src/main/kotlin/dev/elu/analytics/Elu.kt":
        "d34d64ebaae9d26cdcd5e4469743fe1d748264babfbe92359645a223fc2c42c5",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluFeatureFlagOptions.kt":
        "8d78e1650df46a5bacf42f2e6382000b56df8b172c84ec5a67b098b5239e0467",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluConfigClient.kt":
        "ed9e65335829cf348ee992059efc03a523e61c79ef000575814f3e81f4eae642",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluOptions.kt":
        "175731874c892cd845a58b3fd53cdc6e8bf5c22eded558191319ce3f86e1ec1d",
    "elu-analytics/build.gradle.kts":
        "467950f0497da666d721126a2518c3d94a33abf454bb04bfeabf6e45078d87d1",
    "elu-analytics/consumer-rules.pro":
        "4fabc808ed8f99ec3660a83c224cdcf8e3fd041a51c404093195e4cd8bdbb6cb",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/concurrent/SdkFuture.kt":
        "13b2077148db05774a72c47efb0c864b67b571dac72af77e1343f8a5e97de9a9",
    # These files carry the explicit no-wire release status.
    "elu-analytics/src/test/resources/contracts/v1/manifest.json":
        "98152d8725c286f29402ba3e420bda8dd364200fb6fdf1cfe49b2da9b8f63e54",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/transport-policy.json":
        "992900180683af04f69d5e459b7c0c9e68edf92c6ebf320136ed36dbae8b60ce",
}

ANNOTATED_EXTRA_FILES = (
    "build.gradle.kts",
    "settings.gradle.kts",
    "fixtures/compose-consumer/settings.gradle.kts",
    "fixtures/compose-consumer/build.gradle.kts",
    "fixtures/compose-consumer/src/main/AndroidManifest.xml",
    "fixtures/compose-consumer/src/main/kotlin/dev/elu/analytics/composeconsumer/MainActivity.kt",
    "elu-analytics/src/main/res/values/elu_annotated_replay_ids.xml",
    "elu-analytics-compose/build.gradle.kts",
    "elu-analytics-compose/src/main/kotlin/dev/elu/analytics/compose/EluComposeReplay.kt",
    "elu-analytics-compose/src/main/kotlin/dev/elu/analytics/compose/internal/ComposeReplayNodes.kt",
)

CONTRACT_FILES = {
    "elu-analytics/src/test/resources/contracts/v1/schemas/flags-request.schema.json":
        "aef0ae186355db81806561abb4b1c89885ee5024eb3d99a587531a9c7430a770",
    "elu-analytics/src/test/resources/contracts/v1/schemas/flags-response.schema.json":
        "723161b3c0f3a448d679faa7a0723cb819cdb162e62ba605fc7935e15df69db2",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/flags-request.json":
        "19b4f681c8f2c059d39403a5621c0c60a4b6b4328e2bbe8ae28341724604238a",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/flags-response.json":
        "ae943a59d4362cd297e2ea6d7838f5075ad1f949d1401d07d5585db0102326be",
    "elu-analytics/src/test/resources/contracts/v1/test-vectors/feature-flag-activity.json":
        "dbceaa7bee48caf8bf54b73e494fb3f28460eeaf366bbe26f606b659c62a47c4",
}

MAIN_KOTLIN = pathlib.Path("elu-analytics/src/main/kotlin")
CLIENT = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/flags/AndroidFeatureFlagClient.kt"
)
TRANSPORT = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/flags/HttpURLConnectionFlagTransport.kt"
)
TRANSPORT_NAME = "HttpURLConnectionFlagTransport"
ROUTER = pathlib.Path("elu-analytics/src/main/kotlin/dev/elu/analytics/internal/flags/V2ConfigBoundFlagTransport.kt")
STACK = pathlib.Path("elu-analytics/src/main/kotlin/dev/elu/analytics/internal/facade/AndroidStandaloneStack.kt")
ROUTER_NAME = "V2ConfigBoundFlagTransport"
OWNER = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/RuntimeQueueOwner.kt"
)
DATABASE_INTERFACE = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/RuntimeQueueDatabase.kt"
)
SQLITE_DATABASE = pathlib.Path(
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/runtime/AndroidSQLiteRuntimeDatabase.kt"
)

FORBIDDEN_EGRESS = re.compile(
    r"(?i)(java\.net\.(?:http|urlconnection|httpurlconnection)|"
    r"android\.net\.http|org\.apache\.http|okhttp|ktor\.client|cronet)"
)


def sha256(path: pathlib.Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def load_text(root: pathlib.Path, relative: pathlib.Path | str) -> str:
    path = root / relative
    if not path.is_file():
        raise ValueError(f"required file is missing: {relative}")
    return path.read_text(encoding="utf-8")


def verify_pins(root: pathlib.Path, errors: list[str]) -> None:
    for relative, expected in {**PINNED_FILES, **CONTRACT_FILES}.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"required pinned file is missing: {relative}")
            continue
        actual = sha256(path)
        if actual != expected:
            errors.append(f"pinned feature-flag boundary changed: {relative} ({actual} != {expected})")


def verify_contract_status(root: pathlib.Path, errors: list[str]) -> None:
    try:
        manifest = json.loads(
            load_text(root, "elu-analytics/src/test/resources/contracts/v1/manifest.json")
        )
        policy = json.loads(
            load_text(
                root,
                "elu-analytics/src/test/resources/contracts/v1/fixtures/transport-policy.json",
            )
        )
    except (ValueError, json.JSONDecodeError) as error:
        errors.append(str(error))
        return
    if manifest.get("transport", {}).get("status") != "specified-not-wired":
        errors.append("contract manifest transport status must remain specified-not-wired")
    if manifest.get("transport", {}).get("runtimeBehavior") != "unchanged":
        errors.append("contract manifest runtime behavior must remain unchanged")
    if policy.get("transportStatus") != "specified-not-wired":
        errors.append("transport policy must remain specified-not-wired")


REMOVED_RUNTIME_FILES = (
    "dev/elu/analytics/EluCore.kt",
    "dev/elu/analytics/EluReplayBudget.kt",
    "dev/elu/analytics/EluRuntimeMode.kt",
    "dev/elu/analytics/internal/facade/EluFacadeLane.kt",
    "dev/elu/analytics/internal/facade/EmbeddedRuntimeSink.kt",
)


def verify_owned_runtime(root: pathlib.Path, sources: dict[pathlib.Path, str], errors: list[str]) -> None:
    for relative in REMOVED_RUNTIME_FILES:
        if (root / MAIN_KOTLIN / relative).exists():
            errors.append("removed provider/runtime wrapper must remain absent: " + relative)
    legacy = re.compile(r"\b(?:EluCore|EluReplayBudget|EluRuntimeMode|EluRuntimeSelector|EluFacadeLane|EmbeddedRuntimeSink)\b")
    platform_future = re.compile(r"\bjava\s*\.\s*util\s*\.\s*concurrent\s*\.\s*(?:CompletableFuture|CompletionStage|CompletionException)\b|\bimport\s+java\.util\.concurrent\.\*")
    for relative, text in sources.items():
        if legacy.search(text):
            errors.append(f"retired runtime selector reintroduced into owned source: {relative}")
        if platform_future.search(text):
            errors.append(f"API23 runtime must use the private completion primitive: {relative}")
    public = sources.get(MAIN_KOTLIN / "dev/elu/analytics/Elu.kt", "")
    if ("private val consent = EluConsentHandoff()" not in public or
        "private val sink get() = consent.sink" not in public or
        public.count("AndroidStandaloneStack.facade(appContext, key, configHost, options.performance, options.diagnostics, options.apiHost, options.personProfiles, options.persistence, options.rateLimiting, options.declaredRegionReplayEnabled)") != 1):
        errors.append("public setup must construct exactly the owned standalone sink with the validated host")
    if not (0 <= public.find("val facade = AndroidStandaloneStack.facade(") < public.find("consent.install(facade, facade::start)")):
        errors.append("public setup must publish the exact owned sink through the consent handoff")
    handoff = sources.get(MAIN_KOTLIN / "dev/elu/analytics/internal/facade/EluConsentHandoff.kt", "")
    if ("internal class EluConsentHandoff" not in handoff or
        not (0 <= handoff.find("pending?.apply(target)") < handoff.find("sink = target") < handoff.find("start()"))):
        errors.append("pending consent must precede sink publication and startup")
    facade = sources.get(MAIN_KOTLIN / "dev/elu/analytics/internal/facade/StandaloneFacade.kt", "")
    if not (0 <= facade.find("pendingConsent?.let") < facade.find("applyConsentOnLane(intent)") < facade.find("onOpened()")):
        errors.append("startup must durably apply pending consent before lifecycle collection")
    build = load_text(root, "elu-analytics/build.gradle.kts")
    runtime_dependencies = re.findall(r'(?m)^\s*(?:implementation|api|runtimeOnly)\s*\(\s*"([^"]+)"', build)
    if runtime_dependencies != ["com.squareup.okhttp3:okhttp"]:
        errors.append("runtime dependencies must remain the exact approved owned closure")
    if (not re.search(r"\bminSdk\s*=\s*23\b", build) or
        "isCoreLibraryDesugaringEnabled = true" not in build or
        'coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")' not in build):
        errors.append("owned runtime must retain API23 and the approved time/arithmetic desugaring")
    future = sources.get(MAIN_KOTLIN / "dev/elu/analytics/internal/concurrent/SdkFuture.kt", "")
    if ("internal open class SdkFuture<T> : Future<T>, SdkCompletionStage<T>" not in future or
        "internal interface SdkCompletionStage<T>" not in future):
        errors.append("private completion primitive must not become public or change its ownership boundary")


def verify_no_wiring(root: pathlib.Path, errors: list[str]) -> None:
    source_root = root / MAIN_KOTLIN
    if not source_root.is_dir():
        errors.append(f"production source root is missing: {MAIN_KOTLIN}")
        return
    sources: dict[pathlib.Path, str] = {}
    for path in sorted(source_root.rglob("*.kt")):
        relative = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        sources[relative] = text
        if relative not in {ROUTER, STACK} and re.search(r"\b" + ROUTER_NAME + r"\b", text):
            errors.append(f"configuration-bound flag transport escaped standalone composition: {relative}")
        if re.search(r"\bEluRuntimeSelector\s*\.\s*select\s*\(", text):
            errors.append(f"production runtime selection change is forbidden: {relative}")
        if relative not in {CLIENT, STACK} and re.search(r"\bAndroidFeatureFlagClient\b", text):
            errors.append(f"production feature-flag client reference is forbidden outside its internal file: {relative}")
        if relative not in {TRANSPORT, ROUTER} and re.search(r"\b" + TRANSPORT_NAME + r"\b", text):
            errors.append(f"approved flag transport must remain unconstructed and unreferenced: {relative}")
        if relative not in {CLIENT, TRANSPORT, ROUTER} and re.search(r"\bFlagTransport\b", text):
            errors.append(f"production FlagTransport reference/conformer is forbidden: {relative}")
        if relative.parts[-3:-1] == ("internal", "flags") and relative != TRANSPORT and FORBIDDEN_EGRESS.search(text):
            errors.append(f"concrete network/HTTP code is forbidden in the unwired flags package: {relative}")

    verify_owned_runtime(root, sources, errors)

    client = sources.get(CLIENT)
    if client is None:
        errors.append(f"unwired client source is missing: {CLIENT}")
    else:
        if client.count("AndroidFeatureFlagClient") != 1:
            errors.append("AndroidFeatureFlagClient must have one internal declaration and no production construction")
        if len(re.findall(r"\bFlagTransport\b", client)) != 2:
            errors.append("FlagTransport must remain an injected interface with no production conformer")
        if "internal fun interface FlagTransport" not in client:
            errors.append("FlagTransport must remain module-internal")
        if "internal class AndroidFeatureFlagClient" not in client:
            errors.append("AndroidFeatureFlagClient must remain module-internal")
        if re.search(r"(?:class|object)\s+\w+[^\n{]*:\s*FlagTransport\b", client):
            errors.append("a concrete production FlagTransport conformer was added")
        if re.search(r"(?<!interface )\bFlagTransport\s*\{", client):
            errors.append("a production FlagTransport lambda/conformer was added")

    transport = sources.get(TRANSPORT)
    if transport is None:
        errors.append("approved internal flag transport source is missing")
    else:
        if not re.search(r"internal\s+class\s+" + TRANSPORT_NAME + r"\s*\(", transport):
            errors.append("approved flag transport must remain an internal class")
        if transport.count(TRANSPORT_NAME) != 1:
            errors.append("approved flag transport must have one declaration and no construction")
        if len(re.findall(r"\bFlagTransport\b", transport)) != 1:
            errors.append("approved transport file must contain exactly one FlagTransport conformance")
        if not re.search(r"\)\s*:\s*FlagTransport\s*,\s*AutoCloseable\s*\{", transport):
            errors.append("approved flag transport must conform only at its internal declaration")

    router = sources.get(ROUTER, "")
    stack = sources.get(STACK, "")
    if not re.search(r"internal\s+class\s+" + ROUTER_NAME + r"\s*\(", router):
        errors.append("required internal configuration-bound transport is missing or public")
    if len(re.findall(r"\bFlagTransport\b", router)) != 1 or router.count(ROUTER_NAME) != 1:
        errors.append("configuration-bound transport must contain one internal conformer only")
    if router.count(TRANSPORT_NAME) != 5 or len(re.findall(r"\b" + TRANSPORT_NAME + r"\s*\(", router)) != 2:
        errors.append("native flag transport construction must remain inside the exact router factory")
    if not re.search(r"internal\s+object\s+AndroidStandaloneStack\b", stack):
        errors.append("required standalone composition must remain internal")
    if stack.count("AndroidFeatureFlagClient") != 2 or stack.count(ROUTER_NAME) != 2:
        errors.append("standalone composition must contain exactly the approved client and router construction")

    allowed_activation = {CLIENT, OWNER}
    allowed_schema = {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE}
    for relative, text in sources.items():
        if "ensureFeatureFlagRuntime" in text and relative not in allowed_activation:
            errors.append(f"lazy flag activation escaped its internal boundary: {relative}")
        if "ensureFlagSchema" in text and relative not in allowed_schema:
            errors.append(f"flag schema migration escaped its internal boundary: {relative}")

    activation_occurrences = sum(text.count("ensureFeatureFlagRuntime") for text in sources.values())
    schema_occurrences = sum(text.count("ensureFlagSchema") for text in sources.values())
    if activation_occurrences != 2:
        errors.append(
            f"expected only the internal activation declaration and unwired-client call; found {activation_occurrences}"
        )
    if schema_occurrences != 3:
        errors.append(
            f"expected only the schema interface, implementation, and activation call; found {schema_occurrences}"
        )
    owner = sources.get(OWNER, "")
    activation_match = re.search(
        r"internal\s+fun\s+ensureFeatureFlagRuntime\s*\(\s*\)\s*:[^=]+="
        r"(?P<body>.*?)(?=\n\s*internal\s+fun\s+)",
        owner,
        re.DOTALL,
    )
    if activation_match is None or "database().ensureFlagSchema(" not in activation_match.group("body"):
        errors.append("SQLite v2 migration must remain inside explicit ensureFeatureFlagRuntime only")



def verify_lifecycle_bootstrap(root: pathlib.Path, errors: list[str]) -> None:
    namespace = "{http://schemas.android.com/apk/res/android}"
    try:
        manifest = ET.fromstring(load_text(root, "elu-analytics/src/main/AndroidManifest.xml"))
        providers = manifest.findall("application/provider")
        if len(providers) != 1 or providers[0].attrib != {
            namespace + "name": "dev.elu.analytics.internal.runtime.EluLifecycleInitializer",
            namespace + "authorities": "${applicationId}.elu-analytics-lifecycle",
            namespace + "exported": "false",
            namespace + "initOrder": "100",
        }:
            errors.append("lifecycle initializer must remain the exact nonexported application-namespaced provider")
        permissions = [item.get(namespace + "name") for item in manifest.findall("uses-permission")]
        if permissions != ["android.permission.INTERNET"]:
            errors.append("lifecycle bootstrap must not add permissions")
        for name in ["EluLifecycleInitializer.kt", "ProcessActivityLifecycle.kt"]:
            text = load_text(root, MAIN_KOTLIN / "dev/elu/analytics/internal/runtime" / name)
            if re.search(r"\b(?:AndroidStandaloneStack|StandaloneRuntime|RuntimeQueueOwner|AndroidRuntimeQueue|"
                         r"EluRuntimeSelector|EluCore)\b|java\.net|okhttp|\bcapture\s*\(", text):
                errors.append(f"lifecycle bootstrap must not start collection, storage or network: {name}")
    except (ValueError, ET.ParseError) as error:
        errors.append(str(error))

def verify_prepared_replay_boundary(root: pathlib.Path, errors: list[str]) -> None:
    replay_package = MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
    config_package = MAIN_KOTLIN / "dev/elu/analytics/internal/config"
    composition = replay_package / "NativeReplayComposition.kt"
    for path in sorted((root / MAIN_KOTLIN).rglob("*.kt")):
        relative = path.relative_to(root)
        text = path.read_text(encoding="utf-8")
        if "ensureNativeReplaySchema" in text and relative not in {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE}:
            errors.append(f"native replay schema escaped its internal owner: {relative}")
        if ("ensureNativeReplayAccounting" in text and relative not in {OWNER, composition, replay_package / "NativeReplayAuthority.kt"}) or (
            any(name in text for name in ("observeNativeReplaySession", "beginNativeReplayAccounting")) and relative != OWNER) or (
            "stopNativeReplayAccounting" in text and relative not in {OWNER, replay_package / "NativeReplayAuthority.kt"}
        ):
            errors.append(f"native replay accounting must remain unconstructed: {relative}")
        if "ensureReplaySchema" in text and relative not in {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE}:
            errors.append(f"replay schema migration escaped its internal owner: {relative}")
        if ("ensurePreparedReplayStorage" in text and relative not in {OWNER, composition, replay_package / "NativeReplayAuthority.kt"}) or (
            any(name in text for name in ("appendPreparedReplay", "reconcilePreparedReplay",
                                         "expirePreparedReplay", "storedPreparedReplayForTesting")) and relative != OWNER):
            errors.append(f"prepared replay storage must remain unconstructed: {relative}")
        if "openReplayDeliveryQueue" in text and relative not in {OWNER, composition}:
            errors.append(f"prepared replay delivery binding must remain unconstructed: {relative}")
        if "ReplayDeliveryCoordinator" in text and relative not in {replay_package / "ReplayDeliveryCoordinator.kt", composition}:
            errors.append(f"prepared replay coordinator must remain unconstructed: {relative}")
        if "replayMaskingAdmission" in text and relative != OWNER and relative.parent != config_package:
            errors.append(f"production composition must not supply masking admission: {relative}")
        if ("readbackProvenReplayTransports" in text or "supportedReplayProtocolGenerations" in text) and relative not in {OWNER, STACK, MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/AndroidRuntimeQueue.kt"} and relative.parent != config_package:
            errors.append(f"production composition must not supply replay proof: {relative}")
        old_sealer_text = text
        if relative == replay_package / "NativeRasterSealer.kt":
            old_sealer_text = old_sealer_text.replace("NativeReplaySealer.timestamp(timestamp)", "").replace(
                "NativeReplaySealer.gzip(encodedPayload, (policy.maximumRequestBytes - overhead) / 4 * 3)", "")
        if relative == replay_package / "NativeRasterStorage.kt":
            old_sealer_text = old_sealer_text.replace("NativeReplaySealer.timestamp(timestamp)", "")
        if "NativeReplaySealer" in old_sealer_text and relative not in {replay_package / "NativeReplaySealer.kt", replay_package / "NativeReplayCaptureOwner.kt"}:
            errors.append(f"native replay sealer must remain unconstructed: {relative}")
        if "OkHttpReplayTransport" in text and relative not in {replay_package / "OkHttpReplayTransport.kt", composition}:
            errors.append(f"prepared replay HTTP transport must remain unconstructed: {relative}")
        # This exact composition may use only the owned adapter name, never a raw client/API.
        network_text = re.sub(r"\bOkHttpReplayTransport\b", "", text) if relative == composition else text
        if relative.parent == replay_package and relative != replay_package / "OkHttpReplayTransport.kt" and FORBIDDEN_EGRESS.search(network_text):
            errors.append(f"prepared replay storage must not contain network or HTTP code: {relative}")
    stack = load_text(root, STACK)
    for required, count in {
        'internal val installedNativeReplayProtocols: Set<NativeReplayProtocol> =\n        Collections.unmodifiableSet(setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2))': 1,
        'val nativeReplayTransports = installedNativeReplayProtocols.map { it.transport }.toSet()': 1,
        'val nativeReplayGenerations = installedNativeReplayProtocols.map { it.generation }.toSet()': 1,
        'readbackProvenReplayTransports = nativeReplayTransports': 1,
        'supportedReplayProtocolGenerations = nativeReplayGenerations': 1,
        'transports = nativeReplayTransports': 1, 'generations = nativeReplayGenerations': 1,
    }.items():
        if stack.count(required) != count:
            errors.append("standalone must not supply replay proof outside exact owned native capability selection: " + required)
    if stack.count("readbackProvenReplayTransports") != 1 or stack.count("supportedReplayProtocolGenerations") != 1:
        errors.append("standalone must not supply replay proof outside exact owned native capability selection")
    queue = load_text(root, MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/AndroidRuntimeQueue.kt")
    for required in ["readbackProvenReplayTransports: Set<V1ReplayTransport> = emptySet()",
                     "supportedReplayProtocolGenerations: Set<String> = emptySet()",
                     "readbackProvenReplayTransports = readbackProvenReplayTransports",
                     "supportedReplayProtocolGenerations = supportedReplayProtocolGenerations",
                     "assertStartupCurrent = assertStartupCurrent"]:
        if required not in queue:
            errors.append("owned startup must retain exact capability forwarding: " + required)
    if any((root / MAIN_KOTLIN / "dev/elu/analytics/internal/compat").glob("*.kt")):
        errors.append("retired preview import readers must remain absent")
    # Memory consent may locate the exact retired owned file, but never open/import it.
    # Strip only this one pure path lookup; every other legacy-store use still fails.
    path_only_queue = queue.replace("import dev.elu.analytics.internal.core.AndroidCoreStateStore", "", 1).replace(
        "AndroidCoreStateStore.fileFor(applicationContext, constructorSiteKey)", "", 1)
    if any(token in path_only_queue for token in ("AndroidCoreStateStore", "getSharedPreferences", ".filesDir", ".cacheDir", "bootstrapFromLegacy", "startupMigration", ".readBytes(", ".readText(", ".inputStream(")):
        errors.append("clean setup must not read or import preview storage")
    if "freshState(identifiers, SystemCoreEpochClock, freshIdentityStartedAt)" not in queue:
        errors.append("clean setup must create isolated owned state")
    for relative in (OWNER, MAIN_KOTLIN / "dev/elu/analytics/internal/core/CoreState.kt"):
        text = load_text(root, relative)
        if re.search(r"\b(?:StartupHistoryLedger|StartupMigrationCheckpoint|startupHistoryLoader|startupMigrationCompleter)\b", text):
            errors.append("retired preview import authority must remain absent: " + str(relative))
    sealer = load_text(root, replay_package / "NativeReplaySealer.kt")
    if not re.search(r"internal\s+class\s+NativeReplaySealer\s*\(", sealer) or sealer.count("NativeReplaySealer") != 1:
        errors.append("native replay sealer must have one internal declaration and no construction")
    http = load_text(root, replay_package / "OkHttpReplayTransport.kt")
    if not re.search(r"internal\s+class\s+OkHttpReplayTransport\s*\(", http):
        errors.append("replay HTTP transport must remain internal")
    for boundary in (".cookieJar(CookieJar.NO_COOKIES)", ".authenticator(Authenticator.NONE)",
                     ".proxyAuthenticator(Authenticator.NONE)", ".followRedirects(false)",
                     ".followSslRedirects(false)", ".retryOnConnectionFailure(false)", ".cache(null)"):
        if boundary not in http:
            errors.append(f"replay HTTP isolation requirement missing: {boundary}")
    owner = load_text(root, OWNER)
    if not re.search(r"readbackProvenReplayTransports: Set<V1ReplayTransport> = emptySet\(\)", owner):
        errors.append("owner replay proof registry must default empty")
    if "supportedReplayProtocolGenerations: Set<String> = emptySet()" not in owner:
        errors.append("supported replay generations must default empty")
    if "replayMaskingAdmission: ReplayMaskingAdmission = ReplayMaskingAdmission { _, _ -> false }" not in owner:
        errors.append("owner masking admission must default deny")
    if not re.search(r"internal fun ensurePreparedReplayStorage\(\)", owner):
        errors.append("prepared replay activation must remain internal")


def verify_native_authority_boundary(root: pathlib.Path, errors: list[str]) -> None:
    replay = MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
    runtime = MAIN_KOTLIN / "dev/elu/analytics/internal/runtime"
    authority = replay / "NativeReplayAuthority.kt"
    loop = replay / "NativeReplayCaptureOwner.kt"
    composition = replay / "NativeReplayComposition.kt"
    lifecycle = replay / "NativeReplayLifecycle.kt"
    accounting = replay / "NativeReplayAccounting.kt"
    touch_observer = replay / "AndroidReplayTouchObserver.kt"
    initializer = runtime / "EluLifecycleInitializer.kt"
    projector = runtime / "PrivacyStateProjector.kt"
    sources = {path.relative_to(root): path.read_text(encoding="utf-8")
               for path in sorted((root / MAIN_KOTLIN).rglob("*.kt"))}
    allowed = {
        "NativeReplayComposition": {composition, STACK, runtime / "StandaloneRuntime.kt"},
        "NativeReplayAuthority": {authority, loop, composition},
        "NativeReplayFrameBuffer": {replay / "NativeReplayFrameBuffer.kt", loop, replay / "NativeReplayV2Buffer.kt"},
        "NativeReplayLifecycle": {lifecycle, initializer, composition},
        "NativeReplaySelection": {lifecycle, authority, composition},
        "NativeReplayPermit": {authority, OWNER, loop},
        "NativeReplayCapturePhysicalUse": {accounting, authority, OWNER, loop},
        "NativeReplayCaptureEnrollment": {accounting, OWNER, loop},
        "NativeReplayCaptureResources": {accounting, OWNER},
        "NativeReplayCaptureFinish": {accounting, OWNER, loop},
        "NativeReplayCaptureAdmission": {authority, OWNER, loop},
        "NativeReplayAppendOutcome": {authority, OWNER, loop},
        "NativeReplayScope": {accounting, OWNER},
        "NativeReplayGuard": {accounting, authority, OWNER},
        "NativeReplayProjectionInput": {accounting, authority, OWNER, projector},
        "NativeReplayCaptureOwner": {loop, composition},
        "NativeReplayCapturePlatform": {loop, composition},
        "NativeReplayCaptureCollector": {loop},
        "NativeReplayCaptureTouch": {touch_observer, loop, lifecycle, accounting},
        "NativeReplayCaptureWake": {loop},
        "AndroidNativeReplayCapturePlatform": {loop, composition},
    }
    projection_methods = ("observeNativeReplayProjection", "prepareNativeReplayProjection",
                          "beginNativeReplayAuthority", "flushNativeReplayClockDenial")
    capture_methods = {
        "consumeOriginalRoot": {lifecycle},
        "consumeOriginalWindow": {lifecycle, loop, composition},
        "closeOriginalTouchObserver": {lifecycle, loop},
        "retainOriginalTouch": {accounting, loop},
        "originalTouchSettled": {accounting, loop},
        "nativeReplayCaptureClock": {OWNER, loop, composition},
        "nativeReplayCaptureMatches": {OWNER, authority},
        "enrollNativeReplayCapture": {OWNER, loop},
        "finishNativeReplayCapture": {OWNER, loop},
        "appendNativeReplay": {OWNER, loop},
        "stopNativeReplayCaptureAccounting": {OWNER, authority},
        "makeNativeReplayCaptureAdmission": {OWNER, authority},
    }
    capture_issuers = {
        "NativeReplayCaptureEnrollment": (accounting, OWNER),
        "NativeReplayCapturePhysicalUse": (accounting, accounting),
        "NativeReplayCaptureAdmission": (authority, OWNER),
    }
    for relative, text in sources.items():
        if relative == touch_observer:
            if re.search(r"\bAndroidViewReplayCollector\s*\(|\bcollector\s*\.\s*collect\s*\(", text):
                errors.append("native touch observer may only inspect the original collector projection")
            if re.search(r"\b(?:RuntimeQueueOwner|RuntimeQueueDatabase|NativeReplayAuthority|NativeReplayLifecycle|NativeReplayCaptureOwner)\b", text):
                errors.append("native touch observer cannot enter authority queue or lifecycle")
        elif relative != loop and re.search(r"\bAndroidReplayTouchObserver\b", text):
            errors.append("native touch observer installation escaped original capture owner")
        if relative == replay / "NativeReplayV2Buffer.kt":
            # This pure buffer shares only existing size/time ceilings, never the physical owner.
            constants_only = re.sub(r"\bNativeReplayFrameBuffer\s*\.\s*(?:MAXIMUM_ESTIMATED_BYTES|MAXIMUM_FRAME_NODES|MAXIMUM_FRAMES|MAXIMUM_NODES|FLUSH_NANOSECONDS)\b", "", text)
            if re.search(r"\bNativeReplayFrameBuffer\b", constants_only):
                errors.append("native v2 buffer may borrow only exact existing capacity constants")
            if re.search(r"\b(?:RuntimeQueueOwner|RuntimeQueueDatabase|NativeReplayAuthority|NativeReplayLifecycle|NativeReplayCaptureOwner)\b|\b(?:android|androidx)\.", text):
                errors.append("native v2 buffer must not enter authority queue lifecycle or platform APIs")
        for symbol, permitted in allowed.items():
            if re.search(r"\b" + symbol + r"\b", text) and relative not in permitted:
                errors.append(f"native authority reference escaped its exact seam: {symbol}: {relative}")
        for name in projection_methods:
            permitted = {OWNER, authority}
            if name in {"observeNativeReplayProjection", "prepareNativeReplayProjection"}:
                permitted.add(composition)
            if name in text and relative not in permitted:
                errors.append(f"native projection calls escaped authority/queue: {relative}")
        for name, permitted in capture_methods.items():
            if re.search(r"\b" + name + r"\b", text) and relative not in permitted:
                errors.append(f"native capture call escaped its exact seam: {name}: {relative}")
        if re.search(r"\bAndroidViewReplayCollector\b", text) and relative not in {replay / "AndroidViewReplayCollector.kt", loop, touch_observer}:
            errors.append(f"native collector must remain unconstructed: {relative}")
        if re.search(r"\bregisterActivityLifecycleCallbacks\s*\(", text) and relative not in {initializer, MAIN_KOTLIN / "dev/elu/analytics/EluCore.kt", runtime / "ActivityLifecycleEmitter.kt"}:
            errors.append(f"native lifecycle must reuse the sole Application callback source: {relative}")
        for symbol, issuer in [("NativeReplaySelection", lifecycle), ("NativeReplayPermit", authority)]:
            if re.search(r"\b" + symbol + r"\s*\.\s*issue\s*\(", text) and relative != issuer:
                errors.append(f"native witness issuer escaped its exact source: {symbol}: {relative}")
        for symbol, (declaration, issuer) in capture_issuers.items():
            if re.search(r"\b" + symbol + r"\s*(?:\.\s*Companion\s*)?(?:\.|::)\s*issue\b", text) and relative != issuer:
                errors.append(f"native capture issuer escaped its exact source: {symbol}: {relative}")
            if re.search(r"\b" + symbol + r"\s*\(", text) and relative != declaration:
                errors.append(f"native capture constructor escaped its private factory: {symbol}: {relative}")

    # Only this private exact-tuple owner can bind the already-reviewed observer to capture.
    capture = sources.get(loop, "")
    for token in [
        "if (protocol == NativeReplayProtocol.V1) return createCollector(masking, profile)",
        "check(originalUse === use && taken && !physicalFinished && !released && !quarantined && !touchBound)",
        'check(originalTouch == null) { "Original UI cleanup is unresolved" }',
        "checkNotNull(enrollment).retainOriginalTouch(checkNotNull(physicalUse), originalTouch)",
        "row.projection === acceptedProjection",
        "if (interactions != null && initialCommitted && !touchArmed)",
        "originalTouch.withdrawIntake()",
        "selection.closeOriginalTouchObserver(originalTouch).awaitExact()",
        "checkNotNull(enrollment).originalTouchSettled(checkNotNull(physicalUse), originalTouch)",
    ]:
        if token not in capture and token not in sources.get(accounting, ""):
            errors.append("native touch capture lost original binding/commit/cleanup: " + token)
    install = capture.find("originalTouch.install()")
    retained = capture.find(".retainOriginalTouch(checkNotNull(physicalUse), originalTouch)")
    cleanup = capture.find("selection.closeOriginalTouchObserver(originalTouch).awaitExact()")
    physical = capture.find("settleNativeCapture(queue, authority, enrollment, physicalUse, startSubmitted)")
    settlement = capture.split("private fun settleNativeCapture(", 1)[-1].split("/** Raster branch", 1)[0]
    if not (0 <= settlement.find("use.settle()") < settlement.find("authority.stop().awaitExact()") <
            settlement.find("queue.finishNativeReplayCapture(enrollment).awaitExact()")):
        errors.append("native shared physical settlement order changed")
    if not (0 <= retained < install and 0 <= cleanup < physical):
        errors.append("native touch capture must retain before install and join before physical settlement")
    observer_text = sources.get(touch_observer, "")
    for token in ["validatePendingSamples()", "isCurrent = { false }", "freshIntakeAllowed = { false }; wake = {}",
                  "it.kind.text.value != NativeWireframeV2Encoder.MASK"]:
        if token not in observer_text:
            errors.append("native touch capture lost privacy or withdrawn reference release: " + token)

    for relative, text in sources.items():
        if "retainNativeReplayCleanupFailure" in text and relative not in {OWNER, composition}:
            errors.append("native cleanup quarantine escaped exact composition/queue")
    composed = sources.get(composition, "")
    if not re.search(r"internal\s+class\s+NativeReplayComposition\s*\(", composed):
        errors.append("native composition must remain internal and unconstructed")
    for required in ["if (capabilities.hasLocalEvidence())", "private val intakeAllowed: () -> Boolean",
                     "intakeAllowed() && synchronized(monitor) { !closed && intent === original }",
                     "queue.ensurePreparedReplayStorage().awaitExact()", "queue.ensureNativeReplayAccounting().awaitExact()",
                     "PrivacyStateProjector.nativeSealedDeliveryPolicy(capabilities, deviceInEuTimezone)",
                     "selected.closeAndWait().awaitExact()", "queue.retainNativeReplayCleanupFailure().awaitExact()",
                     "old.settleStop().awaitExact()", "original.settlement.whenComplete",
                     "!closed.get() && !canceled.get() && authorizeIo()",
                     "original = intent; useForce = forceRequested; forceRequested = false; requested = false",
                     "runEvaluation(result, original, useForce, acceptance)", "acceptance() && current(original) && acceptance()", "if (includeDelivery) deliveryEpoch.set(null)",
                     "original != null && deliveryEpoch.get() === original && authorizeIo()",
                     "else if (acceptance() && it.current(original) && acceptance())", "it.reevaluate(originalAcceptance = acceptance)"]:
        if required not in composed:
            errors.append("native composition lost an original proof/intent/settlement fence: " + required)
    close_section = composed.split("fun closeAndWait(): SdkFuture<Unit>", 1)[-1]
    if not (0 <= close_section.find("attempt { retireFresh() }") < close_section.find("authority?.closeAndWait()")):
        errors.append("native composition must join physical capture before closing authority")
    queue_text = sources.get(OWNER, "")
    quarantine = queue_text.split("internal fun retainNativeReplayCleanupFailure", 1)[-1].split("fun closeAsync", 1)[0]
    if "nativeScope.close()" not in quarantine or "nativeSettlementUncertain = true" not in quarantine:
        errors.append("native cleanup quarantine must invalidate and retain original resources")

    runtime_file = runtime / "StandaloneRuntime.kt"
    facade_file = MAIN_KOTLIN / "dev/elu/analytics/internal/facade/StandaloneFacade.kt"
    for relative, text in sources.items():
        for method, permitted in {
            "nativeReplayIntakeAllowed": {STACK, facade_file},
            "nativeReplayRecordingAllowed": {STACK, facade_file},
            "startNativeRecording": {facade_file, runtime_file},
            "stopNativeRecording": {facade_file, runtime_file},
            "nativeRecordingStarted": {facade_file, runtime_file},
            "nativeReplayLifecycleChanged": {STACK, facade_file},
            "withdrawNativeReplay": {STACK, facade_file, runtime_file},
            "reevaluateNativeReplay": {facade_file, runtime_file},
        }.items():
            if re.search(r"\b" + method + r"\b", text) and relative not in permitted:
                errors.append("native public-private lifecycle bridge escaped exact assembly: " + method)
    stack_source = sources.get(STACK, "")
    if len(re.findall(r"\bNativeReplayComposition\s*\(", stack_source)) != 1 or stack_source.count("NativeReplayCapabilities(") != 1:
        errors.append("standalone must construct exactly one native composition with the exact private local capability selection")
    ready = stack_source.find("native.ready().get()")
    construct = stack_source.find("val runtime = StandaloneRuntime(")
    if not (0 <= ready < construct) or "AndroidProcessLifecycle.nativeObserved" not in stack_source or "facade::nativeReplayIntakeAllowed" not in stack_source:
        errors.append("native assembly must finish preparation and retain original lifecycle/intent before runtime")
    runtime_source = sources.get(runtime_file, "")
    close_native = runtime_source.find("originalNativeClose?.awaitExact()")
    close_queue = runtime_source.find("owner.closeAsync().awaitExact()")
    if not (0 <= close_native < close_queue) or "controlExecutor.execute" not in runtime_source:
        errors.append("native runtime close must join original physical settlement before queue on control worker")
    if "nativeReplay.also { nativeStartTrace.mark(NativeStartPhase.NATIVE_PRESENT, it != null) }?.reevaluate(force, originalAcceptance)" not in runtime_source:
        errors.append("native runtime lost original caller acceptance")
    facade_source = sources.get(facade_file, "")
    for required in ["if (restrictive) nativeIntentEpoch = Any()", "pendingNativeOperations += 1",
                     "token.settled.compareAndSet(false, true)", "operation.settled.compareAndSet(false, true)",
                     "nativeIntentEpoch === epoch", "pendingNativeOperations == 0 && nativeLifecycleEligible",
                     "nativeEpochIsCurrent(epoch) && nativeReplayIntakeAllowed()",
                     "affectsFlags = hasContextMutation(eventProperties)",
                     "held.forEach { discard(it, EluFacadeDropReason.CLOSED) }"]:
        if required not in facade_source:
            errors.append("native facade lost first-acceptance or exact settlement fence: " + required)

    emitter = sources.get(runtime / "ActivityLifecycleEmitter.kt", "")
    if len(re.findall(r"\bregisterActivityLifecycleCallbacks\s*\(", emitter)) != 1:
        errors.append("existing event lifecycle emitter must retain exactly its original callback registration")
    auth = sources.get(authority, "")
    life = sources.get(lifecycle, "")
    account = sources.get(accounting, "")
    initial = sources.get(initializer, "")
    if not re.search(r"internal\s+class\s+NativeReplayAuthority\s*\(", auth) or len(re.findall(r"\bNativeReplayAuthority\b", auth)) != 1:
        errors.append("native authority must have one internal declaration and no construction")
    if not re.search(r"internal\s+class\s+NativeReplayLifecycle\s*\(", life) or len(re.findall(r"\bNativeReplayLifecycle\b", life)) != 1:
        errors.append("native lifecycle must have one internal declaration")
    if initial.count("NativeReplayLifecycle") != 1 or not re.search(r"val\s+nativeObserved\s*=\s*(?:[\w.]+\.)?NativeReplayLifecycle\(\)", initial):
        errors.append("native lifecycle construction must remain the sole initializer observer")
    if initial.count("registerActivityLifecycleCallbacks") != 1:
        errors.append("native lifecycle must reuse the sole Application callback source")
    for symbol, (declaration, issuer) in capture_issuers.items():
        declared = sources.get(declaration, "")
        if not re.search(r"internal\s+class\s+" + symbol + r"\s+private\s+constructor\s*\(", declared):
            errors.append(f"native capture capability must retain its private constructor: {symbol}")
        if len(re.findall(r"\b" + symbol + r"\s*\(", declared)) != 1:
            errors.append(f"native capture capability must have exactly one private factory: {symbol}")
        expression = r"\b" + symbol + r"\s*(?:\.\s*Companion\s*)?(?:\.|::)\s*issue\b"
        if len(re.findall(expression, sources.get(issuer, ""))) != 1:
            errors.append(f"native capture capability must have exactly one original issuer: {symbol}")
    exact_capture_counts = {
        "nativeReplayCaptureMatches": {OWNER: 1, authority: 2},
        "consumeOriginalWindow": {lifecycle: 2, loop: 4, composition: 1},
        "nativeReplayCaptureClock": {OWNER: 1, loop: 2, composition: 1},
        "enrollNativeReplayCapture": {OWNER: 1, loop: 2},
        "retainOriginalTouch": {accounting: 3, loop: 1},
    }
    for name, permitted in capture_methods.items():
        if any(len(re.findall(r"(?<!@)\b" + name + r"\b", sources.get(path, ""))) !=
               exact_capture_counts.get(name, {}).get(path, 1) for path in permitted):
            errors.append(f"native capture call must retain exactly its original declaration/call: {name}")
    if (len(re.findall(r"originalUse\s*===\s*use", account)) != 5 or
            "if (taken || physicalFinished || intakeClosed || released || quarantined)" not in account or
            "taken = true; NativeReplayCapturePhysicalUse.issue(this)" not in account):
        errors.append("native physical use must retain one-shot original-use checks")
    owner = sources.get(OWNER, "")
    if not re.search(r'if\s*\(NativeReplayProtocol\.isNativeCodec\(request\.transport\.codec\)\)\s*return@submit\s+ReplayAppendResult\.Rejected\(ReplayAppendRejection\.AUTHORITY\)', owner):
        errors.append("generic replay append must reject native codec without original capture admission")
    protocol = sources.get(replay / "NativeReplayProtocol.kt", "")
    tuples = re.findall(r'V([0-9]+)\("([^"]+)", "([^"]+)", "([^"]+)"\)', protocol)
    if tuples != [("1", "elu-native-wireframe-v1", "protocol-generation-v1", "elu-native-replay-chunk-v1"),
                  ("2", "elu-native-wireframe-v2", "protocol-generation-v2", "elu-native-replay-chunk-v2")]:
        errors.append("native protocol must retain only the two closed codec generation domains")
    checks = [
        (protocol, "values().firstOrNull { it.transport == transport && it.generation == generation }"),
        (protocol, "Collections.unmodifiableMap(values().associate { it.transport to it.generation })"),
        (protocol, "values().any { it.codec == codec }"),
        (owner, "replayTransportGenerations = NativeReplayProtocol.generationBindings()"),
        (owner, "NativeReplayProtocol.match(projection.transport, projection.protocolGeneration) == null"),
        (owner, "NativeReplayProtocol.match(request.transport, request.captureProtocolGeneration) != null"),
        (owner, "request.transport == admission.permit.prepared.projection.privacy.transport"),
        (auth, "it in transports && NativeReplayProtocol.match(it, generation) != null"),
    ]
    manager = sources.get(MAIN_KOTLIN / "dev/elu/analytics/internal/config/V1ConfigManager.kt", "")
    checks += [(manager, token) for token in (
        "replayTransportGenerations: Map<V1ReplayTransport, String> = emptyMap()",
        "Collections.unmodifiableMap(LinkedHashMap(replayTransportGenerations))",
        "pair !in replayTransportGenerations || replayTransportGenerations[pair] == generation",
        "pair !in readbackProvenReplayTransports || !generationMatches(pair, generation)",
        "!generationMatches(selectedPair, replayCapabilities.replayProtocolGeneration)",
    )]
    if any(token not in text for text, token in checks):
        errors.append("native tuple negotiation lost exact original generation or independent proof restriction")
    for parameter in [r"transports:\s*Set<V1ReplayTransport>\s*=\s*emptySet\(\)",
                      r"generations:\s*Set<String>\s*=\s*emptySet\(\)"]:
        if not re.search(parameter, auth):
            errors.append("native transport and generation proof must default empty")
    capture = sources.get(loop, "")
    if not re.search(r"internal\s+class\s+NativeReplayCaptureOwner\s+private\s+constructor\s*\(", capture) or len(re.findall(r"\bNativeReplayCaptureOwner\s*\(", capture)) != 1:
        errors.append("native physical owner must retain one private factory and no public construction")
    if "platform.apiLevel < 29" not in capture:
        errors.append("native physical owner must deny API levels below 29 before enrollment")
    if "readbackProven" in capture or "supportedReplayProtocolGenerations" in capture:
        errors.append("native physical owner cannot create its own proof registry")
    collector_path = replay / "AndroidViewReplayCollector.kt"
    collector = sources.get(collector_path, "")
    for required in ["val touchPaintClip = if (pointQuery != null) clip.intersect(exactRoot).wire(density) else null",
                     "contains(checkNotNull(touchPaintClip), x, y)", "contains(touchPaintClip, floor(x), floor(y))",
                     "val lawfulLeaf = !localBlocked && !localMasked && maskingProfile.readsText",
                     "kind.text.value != NativeWireframeV2Encoder.MASK",
                     "encoded.geometry == NativeGeometryKind.VISIBLE_CLIP && encoded.kind === kind",
                     "if (!lawfulLeaf && (contains(checkNotNull(touchPaintClip), x, y)"]:
        if required not in collector:
            errors.append("native touch projection lost actual or encoded private paint clip veto")
    text_reader = collector.split("fun textKind(", 1)[-1].split("// Inspect ancestry", 1)[0]
    if ("{ view.text }" in text_reader or "{ layout.text }" not in text_reader or
            "NativeAppCompatViewTypes.isText(type.name, type.superclass)" not in text_reader or
            "text.javaClass !== String::class.java" not in text_reader or
            "view.transformationMethod } != null" not in text_reader):
        errors.append("native readable text must retain exact types, displayed layout, and privacy checks")
    for required in ["observed.view.layout } !== observed.layout", "observed.layout.text } !== observed.text",
                     "textKind(observed.view, false, false) != observed.kind"]:
        if required not in collector:
            errors.append("native readable text must revalidate the original displayed layout before publication")
    for relative, source in sources.items():
        if "observeNativeReplayOutline" in source and relative not in {collector_path, lifecycle}:
            errors.append("native outline observation must remain within collector/selection")
    if (life.count("observeNativeReplayOutline(decor, decor)") != 1 or
            'observeNativeReplayOutline(decor, decor) { check(current())' not in life or
            collector.count("observeNativeReplayOutlineProfiled(view, originalWindowDecor, ::check, profile)") != 1):
        errors.append("native outline observation must consume original selection/collection withdrawal checks")
    guarded_read = collector.split("private inline fun <T> guardedNativeViewRead", 1)[-1].split("private fun observeNativeReplayOutlineProfiled", 1)[0]
    if not re.search(r"check\(\).*?val value = getter\(\).*?check\(\).*?return value", guarded_read, re.DOTALL):
        errors.append("native outline getters must retain pre/post original withdrawal checks")
    for required in ["view === originalWindowDecor", 'view.javaClass.name == "com.android.internal.policy.DecorView"',
                     "view.javaClass.classLoader === View::class.java.classLoader", "background.javaClass === ColorDrawable::class.java",
                     "val originalWindowDecor = guardedNativeViewRead(profile, readGuard) { root.rootView }", "guardedNativeViewRead(profile, readGuard) { root.rootView } !== originalWindowDecor"]:
        if required not in collector:
            errors.append("native layout uncertainty must bind exact original boot window and final root")
    buffer = sources.get(replay / "NativeReplayFrameBuffer.kt", "")
    if not re.search(r"internal\s+class\s+NativeReplayFrameBuffer\s*\(", buffer) or len(re.findall(r"\bNativeReplayFrameBuffer\b", buffer)) != 1:
        errors.append("native frame buffer must have one internal declaration and no construction")
    for relative, source in sources.items():
        if (re.search(r"\bcurrentRoot\b", source) and relative != lifecycle) or (
            any(re.search(r"\b" + name + r"\b", source) for name in ("selectCurrent", "observeChanges")) and relative not in {lifecycle, composition}):
            errors.append("native root discovery/subscription must remain within lifecycle")
    if not re.search(r"fun\s+selectCurrent\([^)]*\):\s*SdkFuture<NativeReplaySelection\?>", life):
        errors.append("native current-root discovery must return only an opaque selection")
    lookup = life.split("override fun currentRoot", 1)[-1].split("override fun observe", 1)[0]
    for required in ["Build.VERSION.SDK_INT < 29", "val decor = read { window.peekDecorView() }", "decor.findViewById<View>(android.R.id.content)",
                     "read { selected.window } !== window", "read { root.rootView } !== decor", "return value.takeIf { current() }"]:
        if required not in lookup:
            errors.append("native current-root discovery must retain main original window and withdrawal checks")
    if ("!allowed() || !discoveredMatches()" not in life or
            "val actual = access.currentRoot(selectedActivity, ::allowed)" not in life or
            "return actual === selectedRoot && allowed()" not in life or
            "facts.apiLevel == api" not in life or "return same && discoveredMatches() && allowed()" not in life or
            "matchesDiscovery(activity, root)" not in life or
            "matchesDiscovery(checkNotNull(activity), checkNotNull(root))" not in life or
            "NativeReplaySelection.issue(access, selectedActivity, selectedRoot, facts, discovered)" not in life):
        errors.append("native discovered-root ownership must survive every main collection boundary")
    disposal = life.split("private fun discardUnpublished", 1)[-1].split("internal class NativeReplaySelection", 1)[0]
    registered = disposal.find("unsettledSelections.add(selection)")
    closing = disposal.find("selection.closeAndWait()")
    if not (0 <= registered < closing) or "if (cleanupError == null) synchronized(monitor) { unsettledSelections.remove(selection) }" not in disposal:
        errors.append("native unpublished disposal must reserve before close and release only proven cleanup")
    if not re.search(r"fun\s+consumeOriginalRoot\s*\(\s*current:\s*\(\)\s*->\s*Boolean,\s*locallyStopped:\s*\(\)\s*->\s*Boolean\s*=\s*\{\s*false\s*\},\s*consume:\s*\(Any,\s*\(\)\s*->\s*Boolean\)\s*->\s*NativeReplayCollectionAttempt\?,\s*\):\s*SdkFuture<NativeReplayCollectionAttempt\?>", life):
        errors.append("native root consumption must return only a detached masked snapshot")
    for relative, source in sources.items():
        if "NativeReplayCollectionAttempt" in source and relative not in {lifecycle, loop, composition}:
            errors.append("native detached collection outcome escaped exact physical owner and selection")
    for required in ["internal sealed class NativeReplayCollectionAttempt",
                     "class Captured(val frame: NativeMaskedSnapshot, val continuous: Long,",
                     "val projection: NativeTouchProjection? = null, val preceding: List<NativeTouchObservation> = emptyList(),",
                     "val activeTouch: Boolean = false) : NativeReplayCollectionAttempt()",
                     "class TouchBoundary(val observations: List<NativeTouchObservation>, val active: Boolean = false)",
                     "object CollectorDeadline : NativeReplayCollectionAttempt()"]:
        if required not in capture:
            errors.append("native root consumption must return only a detached masked snapshot or closed deadline outcome")
    if "facts.apiLevel < 29" not in life or "Build.VERSION.SDK_INT < 29" not in life:
        errors.append("native selection must refuse API levels below 29")
    for callback in ["onActivityPrePaused", "onActivityPreStopped", "onActivityPreDestroyed", "onActivityPaused"]:
        if not re.search(r"override\s+fun\s+" + callback + r"\(activity:\s*Activity\)\s*=\s*withdrawing\(activity\)", initial):
            errors.append(f"native lifecycle withdrawal must be synchronous at {callback}")
    if not re.search(r"private\s+fun\s+withdrawing\(activity:\s*Activity\)\s*\{\s*performanceObserved\.withdrawing\(activity\)\s*nativeObserved\.withdrawing\(activity\)\s*\}", initial):
        errors.append("native lifecycle withdrawal must synchronously retire replay and performance facts")
    if auth.count("stopNativeReplayAccounting") != 1:
        errors.append("native authority must retain its single original accounting settlement call")


def verify_frame_observer_boundary(root: pathlib.Path, errors: list[str]) -> None:
    """Keep optional scalar frame observation within original public Window ownership."""
    base = MAIN_KOTLIN / "dev/elu/analytics"
    options = load_text(root, base / "EluFrameMetricsOptions.kt")
    access = load_text(root, base / "internal/performance/AndroidFrameMetricsAccess.kt")
    monitor = load_text(root, base / "internal/performance/AndroidPerformanceMonitor.kt")
    owner = load_text(root, base / "internal/performance/NativeFrameMetricsOwner.kt")
    aggregate = load_text(root, base / "internal/performance/NativeFrameMetrics.kt")
    facade = load_text(root, base / "internal/facade/StandaloneFacade.kt")
    if "val enabled: Boolean = false" not in options or "val processAgeAtFirstObservedFrame: Boolean = false" not in options:
        errors.append("frame observation must require explicit local options")
    if ("options.frameMetrics && Build.VERSION.SDK_INT >= 26" not in monitor or
            "if (Build.VERSION.SDK_INT >= 26 && frames != null)" not in monitor or
            "metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)" not in access or
            "if (Build.VERSION.SDK_INT >= 31) metrics.getMetric(FrameMetrics.DEADLINE) else null" not in access):
        errors.append("frame observation requires supported timestamps and versioned metric availability")
    fields = set(re.findall(r"metrics\.getMetric\(FrameMetrics\.([A-Z_]+)\)", access))
    if (fields != {"TOTAL_DURATION", "INTENDED_VSYNC_TIMESTAMP", "FIRST_DRAW_FRAME", "DEADLINE"} or
            re.search(r"\b(?:PixelCopy|Bitmap|ViewTreeObserver|Window\.Callback|setOnTouchListener)\b|\.(?:text|contentDescription|rootView|decorView)\b", access) or
            access.count("originalWindow.addOnFrameMetricsAvailableListener(listener, main)") != 1 or
            access.count("originalWindow.removeOnFrameMetricsAvailableListener(listener)") != 1):
        errors.append("frame observation must retain numeric fields and one original public listener")
    for required in ["lifecycle.isCurrent(original.selection) && context() == original.context",
                     "if (!current(acquired)) aggregate.clear()", "original.watcher?.close()",
                     "else if (lease == null) closeResult.complete(Unit)"]:
        if required not in owner:
            errors.append("frame observation must retain original context and physical close settlement")
    if ("frameStartedAtNanos < registeredAtNanos" not in aggregate or
            "durationNanos > observedAtNanos - frameStartedAtNanos" not in aggregate or
            "count >= MAXIMUM_FRAMES" not in aggregate or
            "if (includeProcessAge && !processAgeObserved)" not in aggregate):
        errors.append("frame observation must reject stale timestamps and bound aggregate admission")
    if "onCloseSettled()" not in facade or "frameClose.whenComplete" not in monitor or "worker.awaitTermination" not in monitor:
        errors.append("frame observation close must join original listener and worker settlement")


def verify_network_observer_boundary(root: pathlib.Path, errors: list[str]) -> None:
    """Keep explicit customer instrumentation separate from the SDK transport and replay grants."""
    base = MAIN_KOTLIN / "dev/elu/analytics"
    public = load_text(root, base / "EluOkHttpInterceptor.kt")
    observer = load_text(root, base / "internal/network/NativeNetworkInterceptor.kt")
    facade = load_text(root, base / "internal/facade/StandaloneFacade.kt")
    owner = load_text(root, OWNER)
    if ("NativeNetworkInterceptor(Elu::beginNetworkObservation)" not in public or
            "delegate.intercept(chain)" not in public or "OkHttpClient" in public):
        errors.append("network instrumentation must remain an explicit customer interceptor")
    fields = set(re.findall(r'"\\\$(network_[a-z_]+)"\s+to', observer))
    if fields != {"network_method", "network_status_code", "network_response_time_ms",
                  "network_initiator", "network_failed"}:
        errors.append("network observation must retain only the five reviewed request metrics")
    if (observer.count("chain.proceed(request)") != 1 or
            re.search(r"\.(?:body|headers?|encodedPath|encodedQuery|query|fragment)\b", observer) or
            "url.toString" in observer or "throw failure" not in observer or
            "completed.compareAndSet(false, true)" not in observer or
            'host == "elu.dev" || host.endsWith(".elu.dev")' not in observer):
        errors.append("network observation must preserve one proceed, caller results, privacy and SDK exclusion")
    admission = facade.split("override fun beginNetworkObservation", 1)[-1].split("// ---- properties", 1)[0]
    if not re.search(r"networkObservations\s*>=\s*200\b", admission):
        errors.append("network observation must retain the process admission cap")
    for required in ["networkObservations++", "value != networkConfigHost",
                     "if (networkContext() != original) return@submit", "networkContext() == original",
                     "!hasCurrentCapture()", "isOptedOut()", "!nativeLifecycleEligible",
                     "pendingNativeOperations != 0", "pendingIdentityOperations != 0", "pendingFlagOperations != 0",
                     "session?.id, session?.startedAt, flagIntentRevision, consentIntentRevision, nativeIntentEpoch"]:
        if required not in admission:
            errors.append("network observation must retain its bounded original capture and consent context")
    if "captureNetwork" in admission.replace(".runtime.captureNetwork", ""):
        errors.append("network observation must not consume replay request-detail permission")
    durable = owner.split("fun originalContextMatches()", 1)[-1].split("val mergedProperties", 1)[0]
    for required in ["command.networkExpectation", "before.state.identity.revision == expected.identityRevision",
                     "before.state.identity.contextRevision == expected.contextRevision",
                     "before.state.identity.session?.id == expected.sessionId",
                     "before.state.identity.session?.startedAt == expected.sessionStartedAt && expected.isCurrent()"]:
        if required not in durable:
            errors.append("network durable enqueue must recheck original identity, session and authority")
    if "commitPreparedAppend(transaction, created)\n                        if (!originalContextMatches()) throw PassiveCaptureWithdrawn()" not in owner:
        errors.append("network durable enqueue must roll back final context withdrawal")
    for required in ["val passive = source.expectation != null || source.networkExpectation?.sessionId != null",
                     "if (passive) RuntimeEventSessionUpdate.Preserve",
                     "passiveCaptureAt = command.occurredAt.takeIf { passive }"]:
        if required not in owner:
            errors.append("network durable enqueue must preserve existing session activity")


def verify_startup_observer_boundary(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    options = load_text(root, base / "EluDiagnosticsOptions.kt")
    access = load_text(root, base / "internal/diagnostics/AndroidStartupAccess.kt")
    observation = load_text(root, base / "internal/diagnostics/NativeStartupObservation.kt")
    monitor = load_text(root, base / "internal/diagnostics/NativeStartupMonitor.kt")
    stack = load_text(root, STACK)
    owner = load_text(root, OWNER)
    if ("val enabled: Boolean = false" not in options or "val launchTimings: Boolean = false" not in options or
            "diagnosticsOptions.enabled && android.os.Build.VERSION.SDK_INT >= 35" not in stack or
            "startupAccess != null && diagnosticsOptions.launchTimings" not in stack):
        errors.append("startup observation requires both explicit options and API 35")
    if ("getHistoricalProcessStartReasons(NativeStartupObservation.MAXIMUM_RECORDS)" not in access or
            re.search(r"\.(?:intent|traceInputStream|description|getIntent)\b|addApplicationStartInfoCompletionListener", access) or
            "START_TIMESTAMP_FIRST_FRAME" not in access):
        errors.append("startup observation must use bounded public history without app callback replacement or private content")
    fields = set(re.findall(r'"\\\$(diagnostic_[a-z_]+|launch_[a-z_]+)"\s+to', observation))
    if fields != {"diagnostic_platform", "diagnostic_source", "launch_start_uptime_ns", "launch_first_frame_uptime_ns",
                  "launch_duration_ms", "launch_reason", "launch_type"}:
        errors.append("startup observation must retain only reviewed numeric OS fields and fixed labels")
    for required in ["matching.size != 1", "record.state != STATE_STARTED || record.firstFrameUptimeNanos != null",
                     "now.bootCount != began.bootCount", "currentEpoch != epoch", "polls > MAXIMUM_POLLS",
                     "record.state != STATE_FIRST_FRAME", "frame < began.uptimeNanos"]:
        if required not in observation:
            errors.append("startup observation must prove an original bounded incomplete-to-first-frame transition")
    for required in ["context() != admitted", "if (deliveryReady())", "worker.awaitTermination(5, TimeUnit.SECONDS)"]:
        if required not in monitor:
            errors.append("startup observation must retain current context and original query settlement")
    for required in ["!diagnosticsClosurePending && diagnosticsConfiguration.enabled && diagnosticsConfiguration.launchTimings",
                     "epoch == measurement.epoch", "diagnosticsLaunchAuthority", "nativeDiagnostic = source.startupMeasurement != null",
                     "created = created.copy(after = created.after.copy(diagnostics = before.diagnostics.copy(",
                     "lastLaunchUptimeNanos = measurement.launchUptimeNanos)))",
                     "!nativeSettlementUncertain && !diagnosticsClosurePending", "quarantineDiagnosticsResources()"]:
        if required not in owner:
            errors.append("startup observation must retain durable authority, atomic dedupe and failed-close ownership")


def verify_local_endpoint_binding(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "EluConfigHostPolicy.kt": ["val declaredApiOrigin = apiHost?.let { selfHostedOrigin(it) ?: return null }"],
        "internal/config/LocalEndpointPolicy.kt": ["internal class LocalEndpointPolicy private constructor", "EluConfigHostPolicy.selfHostedOrigin(apiHost)", "endpoint.rawUserInfo == null", '!= "site_key"'],
        "internal/config/V2ConfigSource.kt": ["endpointPolicy.apiOrigin", "boundEndpoint = endpoint", "V1ConfigManager(endpointPolicy = endpointPolicy)"],
        "internal/config/V2ConfigTransport.kt": ["endpoint == boundEndpoint", "endpointPolicy.apiOrigin == null || boundEndpoint != null", "connection.instanceFollowRedirects = false"],
        "internal/config/V1ConfigManager.kt": ["endpointPolicy.matchesRole(uri, role, schemaVersion)"],
        "internal/facade/AndroidStandaloneStack.kt": ["LocalEndpointPolicy.fromApiHost(apiHost)", "V2ConfigBoundFlagTransport(siteKey, gate, endpointPolicy)", "networkApiHost = endpointPolicy.apiHost", "endpointPolicy = endpointPolicy"],
        "internal/runtime/AndroidRuntimeQueue.kt": ["databaseFileFor(applicationContext, constructorSiteKey, endpointPolicy)", "RuntimeSiteNamespace.directory(constructorSiteKey, endpointPolicy)", "endpointPolicy = endpointPolicy"],
        "internal/runtime/RuntimeQueueOwner.kt": ["val endpointPolicy: LocalEndpointPolicy", "endpointPolicy = endpointPolicy"],
        "internal/runtime/StandaloneRuntime.kt": ["endpointPolicy = owner.endpointPolicy"],
        "internal/runtime/delivery/BatchDeliveryModels.kt": ["endpointPolicy.requireApproved(this.eventsEndpoint, V1EndpointRole.EVENTS)"],
        "internal/flags/V2ConfigBoundFlagTransport.kt": ["HttpURLConnectionFlagTransport(key, endpoint, endpointPolicy = endpointPolicy)"],
        "internal/flags/HttpURLConnectionFlagTransport.kt": ["requireApprovedEndpoint(endpoint, endpointPolicy)", "endpointPolicy.requireApproved(uri, V1EndpointRole.FLAGS)"],
        "internal/replay/NativeReplayComposition.kt": ["NativeReplayHttpRouter(queue.endpointPolicy)", "claim.authorization.endpoint, format = claim.format, endpointPolicy = endpointPolicy"],
        "internal/replay/OkHttpReplayTransport.kt": ["endpointPolicy.requireApproved(endpoint, V1EndpointRole.REPLAY)"],
        "internal/facade/StandaloneFacade.kt": ["value != networkConfigHost && value != networkApiHost"],
        "internal/runtime/CaptureAuthority.kt": ['endpointPolicy.apiOrigin ?: return "site-$keyDigest"', '"elu-runtime-selfhost-v1\\u0000$origin\\u0000$keyDigest"'],
    }
    for relative, tokens in required.items():
        try:
            source = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error))
            continue
        if any(token not in source for token in tokens):
            errors.append("local endpoint binding must preserve immutable origin, role, transport and storage ownership: " + relative)
    for path in (root / base).rglob("*.kt"):
        if "LocalEndpointPolicy.fromApiHost(" in path.read_text() and path.name != "AndroidStandaloneStack.kt":
            errors.append("local endpoint policy can only be minted at original application composition")


def verify_person_selection(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "EluOptions.kt": ["personProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY"],
        "Elu.kt": ["options.apiHost, options.personProfiles, options.persistence, options.rateLimiting, options.declaredRegionReplayEnabled)", "fun reset(resetDeviceId: Boolean)"],
        "internal/facade/AndroidStandaloneStack.kt": ["personProfiles: dev.elu.analytics.EluPersonProfilesMode = dev.elu.analytics.EluPersonProfilesMode.IDENTIFIED_ONLY"],
        "internal/runtime/AndroidRuntimeQueue.kt": ["personProfiles: EluPersonProfilesMode = EluPersonProfilesMode.IDENTIFIED_ONLY"],
        "internal/runtime/RuntimeQueueOwner.kt": [
            'if (personProfiles == null) corrupt("Person metadata requires a selected profile mode")',
            'if (it.streamId != state.stream.streamId) corrupt("Person metadata does not match owned stream")',
            "person = transitionedPerson", "left.person == right.person",
            "request.drafts.any { isPersonMutation(it.change) }", "putAll(checkNotNull(person).stamps(identity, personProfiles))"],
        "internal/runtime/AndroidSQLiteRuntimeDatabase.kt": [
            "else -> runtimeNormalizedDatabaseVersion(version)", "validateTableSql(sqlite, PERSON_TABLE, CREATE_PERSON)",
            'readPerson(sqlite) ?: corrupt("Missing person state")'],
    }
    for relative, tokens in required.items():
        try:
            text = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in text for token in tokens):
            errors.append("person selection must bind production mode, durable metadata and final event identity: " + relative)
    queue = load_text(root, base / "internal/runtime/AndroidRuntimeQueue.kt")
    stack = load_text(root, base / "internal/facade/AndroidStandaloneStack.kt")
    if queue.count("personProfiles = personProfiles,") != 2 or stack.count("personProfiles = personProfiles,") != 2:
        errors.append("person selection must reach both production owners and the test-only opener explicitly")
    for path in (root / base).rglob("*.kt"):
        if "RuntimeQueueOwner.open(" in path.read_text() and path.name != "AndroidRuntimeQueue.kt":
            errors.append("person selection forbids another production raw owner opener")


def verify_durable_flag_exposures(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "internal/runtime/RuntimeFlagExposureState.kt": ["MAX_RUNTIME_FLAG_EXPOSURES = 4096", "RUNTIME_EXPOSURE_SCHEMA_OFFSET = 36", "it.encode().contentEquals(bytes)"],
        "internal/runtime/RuntimeQueueOwner.kt": [
            "initializeExposureState()", "FlagDurableStore.read(transaction, flagAuthority, before.state, command.versions",
            "if (ledger.contains(exposure.digest))", "created.after.copy(exposures = nextExposures)",
            "if (!originalContextMatches()) throw PassiveCaptureWithdrawn()", "left.exposures == right.exposures",
            "RuntimeFlagExposureState.initial(committedState) else before.exposures"],
        "internal/runtime/AndroidSQLiteRuntimeDatabase.kt": [
            "validateTableSql(sqlite, EXPOSURES_TABLE, CREATE_EXPOSURES)", 'readExposures(sqlite) ?: corrupt("Missing exposure state")'],
        "internal/runtime/RuntimeFlagExposureCapture.kt": [
            '"\\$feature_flag_request_id" to metadata.requestId', '"\\$feature_flag_evaluated_at" to metadata.evaluatedAt.toEpochMillisFloor()',
            '"\\$used_bootstrap_value" to usedBootstrap'],
        "internal/facade/StandaloneFacade.kt": ["RuntimeFlagExposureCapture.from(key, read, !flagsFromRemote)",
            "metadata.logicalDigest != flagEvaluationDigest", "floorMillis = 5_000L", "flagRetryAttempt >= 6"],
        "internal/config/V2ConfigLifecycleDriver.kt": ["minOf(5 * MINUTE", "result is V2ConfigSourceResult.Document && retained", "publishedUpdate?.let(onRetainedRefresh)"],
        "internal/facade/AndroidStandaloneStack.kt": ["facade.configurationRefreshed(token)"],
    }
    for relative, tokens in required.items():
        try:
            source = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in source for token in tokens):
            errors.append("durable flag exposure must retain atomic visitor ledger, evaluation origin and bounded refresh: " + relative)



def verify_replay_controls(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    requirements = {
        "Elu.kt": ["fun startSessionRecording() { sink?.startSessionRecording() }", "fun stopSessionRecording() { sink?.stopSessionRecording() }", "fun sessionRecordingStarted(): Boolean = sink?.sessionRecordingStarted() ?: false"],
        "internal/facade/AndroidStandaloneStack.kt": ["recordingAllowed = facade::nativeReplayRecordingAllowed"],
        "internal/facade/StandaloneFacade.kt": ["@Volatile private var recordingRequested = true", "recordingRequested = false", "stack?.runtime?.stopNativeRecording()", "stack?.runtime?.nativeRecordingStarted() == true"],
        "internal/replay/NativeReplayComposition.kt": ["old.settleStop().awaitExact()", "original?.stopRecording()", "recordingGeneration === originalRecording", "restrictionGeneration === originalRestriction", "val intake = { acceptance() && intakeAllowed()", "original?.recordingStarted() == true", "if (includeDelivery) deliveryEpoch.set(null)"],
        "internal/replay/NativeReplayCaptureOwner.kt": ["collectorCurrent.get()?.invoke() == true", "frames.beginDraining()?.let { prefix -> sealPrefix(prefix) }", "if (captured === NativeReplayCollectionAttempt.LocalStop)", "discardTail = true", "fence.gracefulStopRequested() && !discardTail", "privacyCurrent() && fence.isCurrent()", "!restricted && intakeCurrent() && !restricted", "admission.isCurrent()", "selection.isCurrent()", "if (!fence.acceptFrame { frames.append(captured.frame, captured.continuous) }) break"],
        "internal/replay/NativeReplayFrameBuffer.kt": ["frames.isEmpty() || (!firstChunkCommitted && !ready)"],
        "internal/replay/NativeReplayLifecycle.kt": ["fun stopped(): Boolean = locallyStopped() && authorized() && locallyStopped()", "fun allowed(): Boolean = !locallyStopped() && authorized() && !locallyStopped()"],
    }
    for relative, tokens in requirements.items():
        source = load_text(root, base / relative)
        for token in tokens:
            if token not in source:
                errors.append("replay controls lost original local-switch/status/tail boundary: " + relative + ": " + token)


def verify_replay_continuity(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
    composition = load_text(root, base / "NativeReplayComposition.kt")
    lifecycle = load_text(root, base / "NativeReplayLifecycle.kt")
    capture = load_text(root, base / "NativeReplayCaptureOwner.kt")
    required = {
        "composition": (composition, ["if (capture === opened) retireFresh()", "capture != null || selection != null || quarantined",
            "current(original.intent) && recordingEnabled() && original.privacy() && original.preparedCurrent()",
            "lifecycle.observeRootReadiness { rootObservationCurrent(original) }.awaitExact()",
            "if (!rootObservationCurrent(original)) { cancelRootObservation(); return }",
            "rootObservation = null; rootTimer?.cancel(false); rootTimer = null"]),
        "selection": (lifecycle, ["val actual = access.currentRoot(selectedActivity, ::allowed)",
            "return actual === selectedRoot && allowed()", "return same && discoveredMatches() && allowed()",
            "activity != null && allowed() && current(activity, original.second) && allowed()",
            "if (!current()) NativeReplayRootReadiness.INACTIVE", "override fun cancel(mayInterruptIfRunning: Boolean) = false"]),
        "capture": (capture, ["error is NativeReplayRootBoundary && passFailure == null && pendingRequest == null",
            "privacyCurrent() && fence.mayCollect() && authority.belongsTo(queue, prepared)",
            "originalPermit?.started?.guard?.isCurrent() == true && privacyCurrent() && fence.mayCollect()",
            "if (viewport != null && viewport != captured.frame.viewport) throw NativeReplayRootBoundary()",
            "queue.finishNativeReplayCapture(enrollment).awaitExact() == NativeReplayCaptureFinish.SETTLED",
            "complete(if (recoverRoot) NativeReplayCaptureOutcome.SETTLED_ROOT_CHANGED"]),
    }
    for name, (source, tokens) in required.items():
        if any(token not in source for token in tokens):
            errors.append("native continuity lost original settlement/root/privacy/source boundary: " + name)
    preparation = composition.split("private fun observeMissingRoot", 1)[-1].split("private fun scheduleRootObservation", 1)[0]
    tick = composition.split("private fun observeRoot(original:", 1)[-1].split("/** A delayed scheduling opportunity", 1)[0]
    for method in ("observeNativeReplayProjection", "prepareNativeReplayProjection"):
        if composition.count(method) != 1 or preparation.count(method) != 1 or method in tick:
            errors.append("native root observer must not renew authority or poll SQLite: " + method)
    if any(token in tick for token in ("queue.", "authority.", "observeMissingRoot(")):
        errors.append("native root observer must not renew authority or poll SQLite")


def verify_capture_rate_limiter(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "EluOptions.kt": ["private var rateLimitingOptions = EluRateLimitingOptions()", "rateLimitingOptions = rateLimiting"],
        "EluRateLimitingOptions.kt": ["eventsPerSecond.takeIf { it.isFinite() && it > 0.0 } ?: 10.0", "maxOf(this.eventsPerSecond", "Double.MAX_VALUE"],
        "internal/facade/AndroidStandaloneStack.kt": ["rateLimiting = rateLimiting,"],
        "internal/runtime/AndroidRuntimeQueue.kt": ["rateLimiting: dev.elu.analytics.EluRateLimitingOptions = dev.elu.analytics.EluRateLimitingOptions()", "rateLimiting = rateLimiting,"],
        "internal/runtime/RuntimeCaptureRateLimiter.kt": ["stored ?: held", "((now - previous.last) / 1000.0)", "limited && !previouslyLimited && !checkOnly", "owner === originalOwner && command == originalCommand", "it.encodeBucket().contentEquals(bytes)"],
        "internal/runtime/RuntimeQueueOwner.kt": ["owner.initializeCaptureRateLimiting()", "consumeCaptureRate(checkOnly = true)", "warningOf == null && captureRateLimiter != null && attempt.claim(this, command)", "checkExposureLedger = true", "captureRateTransaction", 'corrupt("Capture limiter stream differs")', "catch (error: Throwable) { poisonAndThrow(error) }"],
        "internal/runtime/AndroidSQLiteRuntimeDatabase.kt": ["validateTableSql(sqlite, CAPTURE_RATE_TABLE, CREATE_CAPTURE_RATE)", "readCaptureRate(readOnly).streamId != state.stream.streamId", "cause is android.database.sqlite.SQLiteDiskIOException", 'corrupt("Missing capture limiter state")'],
        "internal/facade/StandaloneFacade.kt": ["val attempt = RuntimeCaptureRateAttempt()", "val first = send(attempt).await()", "val second = send(attempt).await()", "RuntimeCaptureRejection.RATE_LIMITED -> EluFacadeDropReason.RATE_LIMITED"],
    }
    for relative, tokens in required.items():
        try:
            source = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in source for token in tokens):
            errors.append("capture limiter must retain selected-store debit, source fences and exact retry identity: " + relative)
    owner = load_text(root, base / "internal/runtime/RuntimeQueueOwner.kt")
    capture = owner[owner.find("private fun captureOnWorker("):owner.find("private fun captureAuthorityRejection(")]
    if not (0 <= capture.find("val sourceRejection") < capture.find("val decision = consumeCaptureRate()") < capture.find("if (!isValidCaptureCommand(command))")):
        errors.append("capture limiter must debit after source checks and before canonical event validation")
    source_checks = owner[owner.find("private fun captureSourceRejection("):owner.find("private fun captureOnWorker(")]
    if "MAX_RUNTIME_FLAG_EXPOSURES" in source_checks:
        errors.append("capture limiter must charge ledger capacity after source admission")
    if not (0 <= owner.find("owner.reconcileExplicitConsentOnWorker()") < owner.find("owner.initializeCaptureRateLimiting()")):
        errors.append("capture limiter must initialize after durable explicit consent reconciliation")

def verify_exception_intake(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics/internal"
    required = {
        "runtime/RuntimeDiagnosticsState.kt": ["in 49L..54L -> RUNTIME_EXCEPTION_SCHEMA_OFFSET", "else -> throw UnsupportedRuntimeStorageSchemaException(version)"],
        "runtime/AndroidSQLiteRuntimeDatabase.kt": ["else -> runtimeNormalizedDatabaseVersion(version)", "validateTableSql(sqlite, EXCEPTIONS_TABLE, CREATE_EXCEPTIONS)",
            'readExceptions(sqlite) ?: corrupt("Missing exception state")', "exceptions.reservation.matches(state)"],
        "runtime/RuntimeQueueOwner.kt": ["if (memoryOnly || exceptionSpoolFactory == null", "exceptionIntake?.let { barriers += it.close() }",
            "exceptionIntake?.joinClosedWriter()", "val sourceIsCurrent = { !sourceRequired || originalSource?.isCurrent() == true }",
            "left.exceptions == right.exceptions", "consumedDigest = imported.report.digest()", "exceptionImport = imported", "spool.clear()",
            "if (!it.reportSettlement.isDone) return null", "original.rearm(reservation, policy, mono, remaining, sourceIsCurrent)",
            'require(command.exceptionImport == null)', "consentStorageUncertain = true; poisonAndThrow(error)",
            "current.state.identity.contextRevision, policy.policyHash, wall, expiry"],
        "diagnostics/NativeExceptionIntake.kt": ["original.claimed.compareAndSet(false, true)", "pending.compareAndSet(null, work)",
            "!original.sourceIsCurrent()", "while (writer.isAlive)", "writer.join()", "if (interrupted) Thread.currentThread().interrupt()",
            "spool.publish(work.report) { current(work.arm) }", "elapsed >= 0 && elapsed < original.budget",
            "wall < original.reservation.expiresWall", "if (!current(original) && pending.compareAndSet(work, null))", "previous.completion.isDone"],
        "diagnostics/AndroidExceptionSpool.kt": ["OsConstants.O_NOFOLLOW", "OsConstants.O_EXCL", "value.st_nlink != 1L",
            "if (!mayPublish()) return false", "Os.rename(temporary.path, this.report.path)", "syncDirectory(directory)", "Os.close(fd)"],
    }
    for relative, tokens in required.items():
        try:
            source = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in source for token in tokens):
            errors.append("exception intake lost exact schema, original slot/authority or physical settlement: " + relative)
    owner = load_text(root, base / "runtime/RuntimeQueueOwner.kt")
    close = owner[owner.index("fun closeAsync()"):owner.index("private fun finishQuarantinedNativeCloseOnWorker")]
    if "exceptionIntake?.joinClosedWriter()" not in close or close.index("exceptionIntake?.joinClosedWriter()") > close.index("if (capture?.isQuarantined()"):
        errors.append("exception intake lost original writer join before every close branch")
    for path in (root / MAIN_KOTLIN).rglob("*.kt"):
        text = path.read_text()
        if path.name != "RuntimeQueueOwner.kt" and "prepareExceptionIntake(" in text:
            errors.append("exception intake raw policy entry escaped its original queue")
    activation = {
        "runtime/RuntimeQueueOwner.kt": ["configurationGate != null && source?.isCurrent() == true",
            "parsed.captureExceptions?.allowsUncaughtReports == true", "authority?.configSemanticHash == parsed.configSemanticHash",
            "originalIntent() && originalSource.isCurrent() && originalIntent()", "if (pending != null) return@submit RuntimeExceptionIntakeUpdate(pending = pending)",
            "exceptionProductionPolicy?.revoke()", "spool.clear()\n                retireExceptionReservationOnWorker()"],
        "runtime/StandaloneRuntime.kt": ["diagnosticsOptions.enabled && diagnosticsOptions.crashReports",
            "exceptionAdmission.getAndSet(null)?.invalidate()", "exceptionEpoch.get() === epoch", "!exceptionSuspended.get()",
            "exceptionAdmission.get()?.snapshotForPublication()", "exceptionHandler = handler\n            if (!handler.install())",
            "exceptionHandler?.close() != false", "owner.closeAsync().awaitExact()", "pending.whenComplete { _, _ -> requestAutomaticExceptions() }"],
        "facade/AndroidStandaloneStack.kt": ["diagnosticsOptions = diagnosticsOptions, automaticExceptionAllowed = facade::automaticExceptionIntakeAllowed"],
        "facade/StandaloneFacade.kt": ["internal fun automaticExceptionIntakeAllowed()", "pendingIdentityOperations == 0 && pendingFlagOperations == 0",
            "nativeLifecycleEligible && hasCurrentConfiguration()", "stack?.runtime?.withdrawAutomaticExceptions(retire = true)",
            "stack?.runtime?.requestAutomaticExceptions()"],
        "diagnostics/AndroidUncaughtExceptionOwner.kt": ["val originalAdmission = admission.snapshot() ?: return",
            "if (!originalAdmission.allowsObservation()) return", "originalAdmission.offer(observation)"],
        "diagnostics/NativeExceptionIntake.kt": ["original.completion.get(100, TimeUnit.MILLISECONDS)",
            "Thread.currentThread().interrupt(); return NativeExceptionPublication.UNCONFIRMED",
            "return if (original.published)", "observePublication(observation, original)"],
        "config/V1ConfigJson.kt": ['parseCaptureExceptions(root, schemaVersion)', 'if (raw == false) return V1CaptureExceptions(false)',
            'if (schema != V2_CONFIG_SCHEMA_VERSION || root.opt("status") != "enabled")',
            'expectFields(policy, setOf("suppressionRules"), emptySet(), path)', 'return V1CaptureExceptions(rules.length() == 0)'],
    }
    for relative, tokens in activation.items():
        text = load_text(root, base / relative)
        if any(token not in text for token in tokens):
            errors.append("automatic exception activation lost original policy, admission or close fencing: " + relative)
    for path in (root / MAIN_KOTLIN).rglob("*.kt"):
        text = path.read_text()
        if path.name not in ("RuntimeQueueOwner.kt", "StandaloneRuntime.kt") and "updateAutomaticExceptionIntake(" in text:
            errors.append("automatic exception selection escaped the original runtime control lane")
    options = load_text(root, MAIN_KOTLIN / "dev/elu/analytics/EluDiagnosticsOptions.kt")
    if "private var reportUncaught = false" not in options:
        errors.append("automatic exception option must remain disabled by default")
    # The worker may not use the queue, SQLite, HTTP or the manual detail serializer.
    intake = load_text(root, base / "diagnostics/NativeExceptionIntake.kt")
    waits = re.findall(r"\.get\([^()]*,\s*TimeUnit\.[A-Z]+\)", intake)
    if waits != [".get(100, TimeUnit.MILLISECONDS)"]:
        errors.append("exception publication observation must remain the one bounded original-arm wait")
    if any(name in intake for name in ("RuntimeQueueOwner", "SQLiteDatabase", "ExceptionSerializer", "HttpURLConnection", "Thread.sleep")):
        errors.append("exception callback/writer escaped its detached one-slot boundary")

def verify_annotated_root_boundary(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "EluAnnotatedReplayRootScope.kt": ["fun prepare(view: View)", "AnnotatedRootRegistry(view)", "fun attach(): Boolean = registry.attach()"],
        "internal/replay/AnnotatedRootRegistry.kt": ["private val host = WeakReference(view)", "private var required:",
            "(previous as? AnnotatedRootRegistry)?.conflict()", "previous !== this", "it.getTag(R.id.elu_annotated_replay_root) === this",
            "private val reader: EluReplayGeometryReader", "if (isClosed) null else reader.read()"],
        "internal/replay/AndroidAnnotatedReplayCollector.kt": ['if (Build.VERSION.SDK_INT < 29) error("unsupported-platform")', "occupied.compareAndSet(false, true)",
            "INTERVAL_NANOS = 1_000_000_000L", "PASS_NANOS = 50_000_000L", "lastAttempt = checks.started",
            "checkNotNull(c.read { binding.read() })", "sameGeometry(a.geometry, b.geometry)",
            "val acceptedPolicy = original.policyVersion", "{ current() && source.isCurrent() && registry.policyCurrent(acceptedPolicy) }",
            "bitmap?.let { AnnotatedRasterCandidate.clear(it) }"],
        "internal/replay/AnnotatedRasterCandidate.kt": ["internal class AnnotatedRasterCandidate private constructor(",
            "DeclaredRegionPngEncoder.normalize(checkNotNull(raw), width, height)", "raw?.fill(0); output?.close()",
            "failure?.let { encoded?.fill(0); throw it }",
            "bitmap.eraseColor(Color.TRANSPARENT)", "finally { bitmap.recycle() }", "try { close() } catch"],
        "internal/replay/DeclaredRegionPngEncoder.kt": ["MAX_ENCODED_BYTES = 2_097_152", "chunks.size < 1024", "w * h <= 1_048_576",
            "inflater.finished() && inflater.remaining == 0", "png-nonopaque", "png-idat-order", "png-crc"],
    }
    for relative, tokens in required.items():
        try:
            source = load_text(root, base / relative)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in source for token in tokens):
            errors.append("annotated capture lost declared ownership, bounds or validated-output gate: " + relative)
    public = "\n".join(load_text(root, base / path) for path in ("EluAnnotatedReplayRootScope.kt", "EluReplayRegionGeometry.kt"))
    if any(token in public for token in ("Bitmap", "ByteArray", "OutputStream", "NativeReplayAuthority", "PreparedReplayRequest", "RuntimeQueue")):
        errors.append("annotated public seam must carry declared geometry only")
    prepare = public[public.index("fun prepare(view: View)"):public.index("fun prepare(view: View)") + 260]
    if ".attach(" in prepare or ".setTag(" in prepare:
        errors.append("annotated preparation must not attach before composition commits")
    collector = load_text(root, base / "internal/replay/AndroidAnnotatedReplayCollector.kt")
    if collector.count('if (Build.VERSION.SDK_INT < 29) error("unsupported-platform")') != 2:
        errors.append("annotated capture and planning must both refuse below API29 before platform calls")
    if not (0 <= collector.find("canvas.clipOutRect(it)") < collector.find("original.host.draw(canvas)") <
            collector.find("validate(original, plan(window, checks))") < collector.find("AnnotatedRasterCandidate.validated")) or collector.count("validate(original, plan(window, checks))") != 2:
        errors.append("annotated output must revalidate original geometry before candidate construction")
    sizing = (
        "MAX_PIXELS = 1_048_576", "MAX_EDGE = 2_048", "MAX_VIEWPORT_EDGE = 16_384",
        "imageWidth.toLong() * imageHeight <= AndroidAnnotatedReplayCollector.MAX_PIXELS",
        "val dimensions = AnnotatedRasterDimensions.fit(viewport.width(), viewport.height())",
        "Bitmap.createBitmap(original.dimensions.imageWidth, original.dimensions.imageHeight, Bitmap.Config.ARGB_8888)",
        "val margin = if (scaled) 1 else 0", "floor(l * scaleX.toDouble())", "ceil(r * scaleX.toDouble())",
        "floor(t * scaleY.toDouble())", "ceil(b * scaleY.toDouble())",
        "viewportWidth = original.viewport.width(), viewportHeight = original.viewport.height()",
        "old.dimensions == fresh.dimensions",
    )
    if any(token not in collector for token in sizing) or collector.count("Bitmap.createBitmap(") != 1:
        errors.append("annotated downsampling must preserve original viewport and bound the only bitmap allocation")
    if not (0 <= collector.find("canvas.clipOutRect(it)") < collector.find("canvas.scale(original.dimensions.scaleX, original.dimensions.scaleY)") <
            collector.find("canvas.translate(-original.viewport.left.toFloat(), -original.viewport.top.toFloat())") < collector.find("original.host.draw(canvas)")):
        errors.append("annotated device-pixel exclusion must precede scaling, translation and original drawing")
    optional_base = pathlib.Path("elu-analytics-compose/src/main/kotlin/dev/elu/analytics/compose")
    optional = load_text(root, optional_base / "EluComposeReplay.kt")
    node = load_text(root, optional_base / "internal/ComposeReplayNodes.kt")
    if any(token not in optional for token in ("EluAnnotatedReplayRootScope.prepare(view)", "DisposableEffect(registry, view)",
            "registry.attach()", "registry.declareRequired(requiredPrivateRegions)", "onDispose { registry.close() }")) or any(token not in node for token in
            ("val weak = WeakReference(this)", "original.localToWindow(", "serial != generation", "original.parentLayoutCoordinates !== parent", "binding?.close()")):
        errors.append("Compose bindings must read current original geometry and retain exact scope lifetime")
    for path in (root / MAIN_KOTLIN).rglob("*.kt"):
        source = path.read_text()
        if "import androidx.compose." in source:
            errors.append("core production source must remain Compose-independent")
        if path.name != "AndroidAnnotatedReplayCollector.kt" and "AndroidAnnotatedReplayCollector(" in source:
            errors.append("annotated collector must remain uninstalled before closed policy/envelope support")
    for relative in required:
        source = load_text(root, base / relative)
        if any(token in source for token in ("RuntimeQueueOwner", "NativeReplayAuthority", "HttpURLConnection", "Executors.", "FileOutputStream")):
            errors.append("annotated capture cannot obtain authority, install a worker or publish bytes")
    build = load_text(root, pathlib.Path("elu-analytics/build.gradle.kts"))
    runtime_dependency = re.compile(r'^\s*(?:api|implementation|compileOnly|debugImplementation|releaseImplementation)\(.*(?:compose|elu-analytics-compose)', re.MULTILINE)
    if runtime_dependency.search(build) or 'androidTestImplementation(project(":elu-analytics-compose"))' not in build:
        errors.append("Compose fixture dependencies must remain androidTest-only")
    option = 'plugin:androidx.compose.compiler.plugins.kotlin:skipIrLoweringIfRuntimeNotFound=true'
    task_set = 'if (name in setOf("compileDebugKotlin", "compileReleaseKotlin", "compileDebugUnitTestKotlin", "compileReleaseUnitTestKotlin"))'
    if build.count(option) != 1 or task_set not in build:
        errors.append("Compose runtime-absent option must preserve strict AndroidTest compilation")

def verify_compose_distribution_boundary(root: pathlib.Path, errors: list[str]) -> None:
    optional = load_text(root, "elu-analytics-compose/build.gradle.kts")
    for path in ("elu-analytics/build.gradle.kts", "elu-analytics-compose/build.gradle.kts"):
        build = load_text(root, path)
        if 'group = "dev.elu"\nversion = sdkVersion' not in build:
            errors.append("distribution project identity must match the original SDK publication version")
    required = (
        'id("com.vanniktech.maven.publish.base")',
        'configure(AndroidSingleVariantLibrary(javadocJar = JavadocJar.Javadoc(), variant = "release"))',
        'coordinates("dev.elu", "elu-analytics-compose", sdkVersion)',
        'check(optionalProject.extensions.getByType<PublishingExtension>().repositories.isEmpty())',
        'check(allTasks.none { it.project == optionalProject && it is AbstractPublishToMaven })',
        'api(project(":elu-analytics"))', 'api("androidx.compose.ui:ui:1.7.8")',
        'implementation("androidx.compose.foundation:foundation:1.7.8")',
        'includeConfigs = listOf("releaseRuntimeClasspath")', 'testConfigs = emptyList()',
    )
    if any(token not in optional for token in required) or re.search(
            r'publishToMavenCentral\s*\(|signAllPublications\s*\(|id\("com\.vanniktech\.maven\.publish"\)', optional):
        errors.append("optional Compose distribution must remain local metadata only with exact dependencies and publication refusal")
    settings = load_text(root, "fixtures/compose-consumer/settings.gradle.kts")
    build = load_text(root, "fixtures/compose-consumer/build.gradle.kts")
    if any(token not in settings for token in ('exclusiveContent {', 'filter { includeGroup("dev.elu") }',
            'url = uri("../../build/compose-distribution/repository")',
            'if (metadataMode == "module") gradleMetadata()',
            'mavenPom(); ignoreGradleMetadataRedirection()')) or any(token not in build for token in (
            'implementation("dev.elu:elu-analytics-compose:$candidateVersion")',
            'listOf("debugCompileClasspath", "debugRuntimeClasspath")',
            'check(actual == digest(staged.readBytes()))',
            'tasks.named("preBuild") { dependsOn(verifyStagedArtifacts) }')) or re.search(
            r'includeBuild\s*\(|(?:implementation|api)\s*\(\s*project\s*\(', settings + build):
        errors.append("Compose consumer must use exclusive staged Maven coordinates and verify both original AARs")


def verify_raster_sealer_boundary(root: pathlib.Path, errors: list[str]) -> None:
    replay = MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
    path = replay / "NativeRasterSealer.kt"
    source = load_text(root, path)
    for token in ("fun seal(frame: AnnotatedRasterCandidate, timestamp: Long)",
                  "frame.sourceIdentity === sourceIdentity", "sourceIdentity.isCurrent() && sourceIsCurrent()",
                  "private val sourceIsCurrent: () -> Boolean", "fun fork(): NativeRasterSealer",
                  "timestamp - it >= 1_000", "viewport == null || viewport == Pair(frame.viewportWidth, frame.viewportHeight)",
                  '"elu-sdk-replay-request-v3"', '"schemaVersion" to number(3)',
                  '"automaticInputDiscovery" to Value.BooleanValue(false)',
                  '"unknownContentClassification" to Value.BooleanValue(false)',
                  '"requiredRegionsRedacted" to Value.BooleanValue(true)',
                  "frame.close()\n            checkSource()\n            nextSequence++", "if (!accepted) prepared?.clearRejected()",
                  "png?.fill(0); payload?.fill(0); compressed?.fill(0)"):
        if token not in source:
            errors.append("raster sealer lost original source, one-shot state or closed schema boundary")
    if any(token in source for token in ("Bitmap", "V1AuthorizedConfig", "NativeReplayAuthority", "RuntimeQueue", "SQLite",
                                         "FileOutputStream", "Executors.", "secureInputsMasked", "fun seal(png:", "sourceIsCurrent: () -> Boolean =")):
        errors.append("raster sealer cannot accept raw images, obtain authority or reuse automatic-input policy")
    if any(token not in source for token in (
            '"image" to obj("width" to number(frame.width.toLong())',
            '"viewport" to obj("width" to number(frame.viewportWidth.toLong()), "height" to number(frame.viewportHeight.toLong()))',
            "viewport = Pair(frame.viewportWidth, frame.viewportHeight)",
            "policy.effectivePolicyHash, frame.viewportWidth, frame.viewportHeight, sourceIdentity")):
        errors.append("raster sealer must bind the original display viewport independently of encoded image dimensions")
    registry = load_text(root, replay / "AnnotatedRootRegistry.kt")
    for token in ("originalSource?.withdraw()", "if (intent == null) sourceBindingChanged()",
                  "if (intent == null) owner.sourceBindingChanged()", "originalRoot?.get() === root",
                  "originalCoordinates?.get() === geometry.coordinateIdentity", "originalWindow?.get() === window",
                  "originalWindowToken?.get() === token", "private val live = AtomicBoolean(true)"):
        if token not in registry:
            errors.append("raster source token lost original root/window or withdrawal binding")
    collector = load_text(root, replay / "AndroidAnnotatedReplayCollector.kt")
    if "registry.sourceIdentity(root.binding, root.geometry, window, original.decor, original.token)" not in collector or \
            "AnnotatedRasterCandidate.validated(owned, source," not in collector:
        errors.append("raster candidate must carry its actual collector source identity")
    old = load_text(root, replay / "NativeReplaySealer.kt")
    helpers = old[old.index("internal companion object {"):]
    visible = re.findall(r"^        (?!private )(?:internal )?fun (\w+)", helpers, re.MULTILINE)
    if visible != ["timestamp", "gzip"]:
        errors.append("old sealer may expose only timestamp/gzip helpers for raster reuse")
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        relative = file.relative_to(root); text = file.read_text()
        if (relative not in {path, replay / "NativeReplayCaptureOwner.kt"} and
                any(token in text for token in ("NativeRasterSealer(", ".clearRejected()"))) or (
                relative != path and "NativeRasterPreparedRequest(" in text):
            errors.append("raster sealer/request must remain uninstalled outside original capture owner")
        if relative != replay / "AndroidAnnotatedReplayCollector.kt" and "AnnotatedRasterCandidate.validated(" in text:
            errors.append("raster candidate may only originate at the original collector")
        if relative != replay / "AnnotatedRootRegistry.kt" and "AnnotatedRasterSourceIdentity(" in text:
            errors.append("raster source identities may only originate at the original registry")

def verify_native_raster_response_boundary(root: pathlib.Path, errors: list[str]) -> None:
    replay = MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
    path = replay / "NativeRasterResponseClassifier.kt"
    source = load_text(root, path)
    for token in (
        "request: NativeRasterPreparedRequest", "response.status == 200", "response.status == 409",
        'ack.number("schemaVersion") == 3L', 'ack.string("requestId", 72, 72) == request.requestId',
        'ack.string("replayId", 1, 256) == request.replayId', 'ack.string("chunkId", 1, 256) == request.chunkId',
        'ack.number("sequence") == request.sequence', 'ack.string("result", 1, 16) == "accepted"',
        'conflict.number("schemaVersion") == 3L && conflict.number("status") == 409L',
        'conflict.string("requestId", 72, 72) == request.requestId',
        'conflict.string("code", 1, 64) == "replay-identity-conflict"',
        'conflict.string("disposition", 1, 64) == "permanent"',
        '"request" -> NativeRasterConflictScope.REQUEST', '"chunk" -> NativeRasterConflictScope.CHUNK',
        '"sequence" -> NativeRasterConflictScope.SEQUENCE', "finally { body.fill(0) }",
    ):
        if token not in source:
            errors.append("raster response lost closed schema3 or original request identity checks")
    if source.count("require(response.retryAfter == null)") != 3 or \
            "if (response.status == 401 || response.status == 403)" not in source or \
            "if (response.status == 429) require(header != null)" not in source or \
            "minOf(REPLAY_MAX_RETRY_MILLIS, maxOf(0L, retryDelayMillis, delay))" not in source:
        errors.append("raster response lost original refusal or bounded retry semantics")
    remaining = source
    for owner, method in (("ReplayJson", "parse"), ("V1BatchResponseCodec", "validateTransportError"),
                          ("RetryAfterParser", "parseDelayMillis")):
        remaining = re.sub(rf"^import [\w.]+\.{owner}\n", "", remaining, flags=re.MULTILINE)
        remaining = re.sub(rf"\b{owner}\.{method}\s*\(", "(", remaining)
        if re.search(rf"\b{owner}\b", remaining):
            errors.append("raster response may reuse only the exact static parse/error/retry helpers")
    forbidden = ("RuntimeQueueOwner", "AndroidRuntimeQueue", "RuntimeQueueDatabase", "V2ConfigAuthorityGate",
                 "NativeReplayAuthority", "NativeReplayComposition", "NativeReplayCaptureOwner", "NativeRasterSealer",
                 "AnnotatedRasterCandidate", "AnnotatedRootRegistry", "ReplayDeliveryClaim", "ReplayDeliveryOutcome",
                 "ReplayDeliveryCoordinator", "ReplayResponseClassifier", "PreparedReplayRequest", "Thread", "Executors",
                 "URL", "URLConnection", "HttpURLConnection", "Socket", "FileOutputStream")
    if any(re.search(rf"\b{token}\b", source) for token in forbidden) or FORBIDDEN_EGRESS.search(source) or \
            re.search(r"NativeRasterPreparedRequest\s*\(|\.clearRejected\s*\(", source):
        errors.append("raster response cannot construct requests, install authority, dispatch or mutate queue state")
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        if file.relative_to(root) not in {path, replay / "ReplayDeliveryModels.kt"} and re.search(r"\bNativeRaster(?:ResponseClassifier|ResponseOutcome|ConflictScope)\b", file.read_text()):
            errors.append("raster response classifier and outcomes must remain uninstalled")


def verify_native_v3_parser_boundary(root: pathlib.Path, errors: list[str]) -> None:
    config = MAIN_KOTLIN / "dev/elu/analytics/internal/config"
    path = config / "NativeV3ConfigParser.kt"
    source = load_text(root, path)
    for token in (
        "internal object NativeV3ConfigParser", "const val MAXIMUM_BYTES = 65_536",
        "val original = data.copyOf()", "onMalformedInput(CodingErrorAction.REPORT)",
        "onUnmappableCharacter(CodingErrorAction.REPORT)",
        'V1StrictCanonicalJson.parseRetainingRootProperty(source, "configV2")',
        "V1ConfigJson.parseConfig(baseSource)", "base.schemaVersion != V2_CONFIG_SCHEMA_VERSION",
        "endpointPolicy.requireApproved(uri, role, base.schemaVersion)",
        "trusted(it.flags, V1EndpointRole.FLAGS)", "trusted(replay, V1EndpointRole.REPLAY)",
        "trusted(assets, V1EndpointRole.ASSETS)", "replay.advertisedTransports.size != 1",
        "NativeReplayProtocol.match(replay.advertisedTransports.singleOrNull(), replay.replayProtocolGeneration)",
        'setOf("https://ingest.elu.dev", "https://35-224-68-29.sslip.io")',
        '"basePrivacy" to basePrivacy', '"replayAudience" to string(',
        "V1StrictCanonicalJson.canonicalize(candidate) != V1StrictCanonicalJson.canonicalize(expected)",
        "minOf(limits.replayChunkBytes, 5_242_880)",
    ):
        if token not in source:
            errors.append("native v3 semantic parser lost exact bytes, closed policy or original endpoint checks")
    if re.search(r"\b(?:RuntimeQueueOwner|AndroidRuntimeQueue|V2ConfigAuthorityGate|V2ConfigSource|"
                 r"NativeReplayAuthority|NativeRasterSealer|V1AuthorizedConfig|HttpURLConnection|"
                 r"Thread|Executors|FileOutputStream)\b|\.install\s*\(", source):
        errors.append("native v3 semantic parser cannot install authority, persistence, capture or network")
    strict = load_text(root, config / "V1StrictCanonicalJson.kt")
    if "val value = parser.parse()\n        return RootPropertyDocument(value, parser.rootPropertyRange?.let(source::substring))" not in strict or \
            "if (depth == 1 && name == retainedRootProperty) rootPropertyRange = valueStart until index" not in strict:
        errors.append("native v3 root span must come from the original complete strict parse")
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        allowed = {path, *(config / name for name in (
            "V2ConfigSource.kt", "V2ConfigTransport.kt", "V2ConfigLifecycleDriver.kt", "V2ConfigAuthorityGate.kt"))}
        text = file.read_text()
        if file.relative_to(root) in {MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayAuthority.kt",
                MAIN_KOTLIN / "dev/elu/analytics/internal/replay/ReplayQueueStore.kt"}:
            text = re.sub(r"NativeV3ConfigParser\.(?:RasterPolicy|Parsed)\b", "", text)
        if file.relative_to(root) not in allowed and "NativeV3ConfigParser" in text:
            errors.append("native v3 semantic parser must remain uninstalled outside original config source")


def verify_native_v3_source_boundary(root: pathlib.Path, errors: list[str]) -> None:
    verify_native_raster_bootstrap_boundary(root, errors)
    config = MAIN_KOTLIN / "dev/elu/analytics/internal/config"
    required = {
        "V2ConfigTransport.kt": [
            'internal enum class V2ConfigFormat(val pathVersion: String) { V2("v2"), NATIVE_V3("v3") }',
            "format: V2ConfigFormat = V2ConfigFormat.V2", "tail[1] == format.pathVersion",
            "requireApproved(endpoint, debuggable, endpointPolicy.apiOrigin, format)",
            "endpoint == boundEndpoint", "connection.instanceFollowRedirects = false",
        ],
        "V2ConfigSource.kt": [
            "private val format: V2ConfigFormat = V2ConfigFormat.V2",
            "V2ConfigEndpoint.build(configHost, siteKey, debuggable, endpointPolicy.apiOrigin, format)",
            "boundEndpoint = endpoint, format = format", "format == V2ConfigFormat.NATIVE_V3",
            "NativeV3ConfigParser.parse(strictConfigUtf8(receiptBody), endpointPolicy)",
            "nativeV3?.configV2Data?.toString(Charsets.UTF_8) ?: receiptBody",
            "body == other.body && receiptBody == other.receiptBody",
            "parsed.configV2Data.contentEquals(strictConfigUtf8(body))",
            "parsed.data.contentEquals(strictConfigUtf8(receiptBody))",
            "parsed.base.expiresAtInstant.compareTo(expiresAt) == 0",
            "onMalformedInput(CodingErrorAction.REPORT)", "onUnmappableCharacter(CodingErrorAction.REPORT)",
            "prior.conflicted || prior.semanticHash != nativeV3.semanticHash", "prior.conflicted = true",
            "EnvelopeBoundary(issued, nativeV3.semanticHash)", "manager.install(body, sample.wall)",
            "minOf(deadline, prior?.deadline ?: deadline)", "retained.snapshot.sameReceipt(expected)",
        ],
        "V2ConfigLifecycleDriver.kt": [
            "internal fun consumeLease(", "consumeCurrent { body -> consumer(body, originalLease) }",
            "current.sameReceipt(retained)", "current.sameReceipt(published)",
            "sameReceipt(published, snapshot)", "V2ConfigLifecycleUpdate(noticeSequence, kind, snapshot, conflict,",
        ],
        "V2ConfigAuthorityGate.kt": [
            "token.consumeLease { body, lease ->", "lease.body == body && lease.validReceiptBinding()",
            "lease?.receiptBody == witness.receiptBody && lease?.nativeV3 === witness.nativeV3",
            "current === token", "current !== witness.token",
        ],
    }
    for name, tokens in required.items():
        text = load_text(root, config / name)
        if any(token not in text for token in tokens):
            errors.append("native v3 original source lost exact selection, receipt, conflict or lease fence")
        if re.search(r"\b(?:RuntimeQueueOwner|AndroidRuntimeQueue|NativeRasterSealer|NativeReplayAuthority|"
                     r"NativeRasterResponseClassifier|FileOutputStream)\b", text):
            errors.append("native v3 source cannot install raster runtime, storage or authority")
    source = load_text(root, config / "V2ConfigSource.kt")
    if source.count("transport.fetch(endpoint)") != 1 or re.search(r"envelopeBoundary\s*=\s*null", source):
        errors.append("native v3 source cannot fallback or erase its original conflict boundary")
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        relative = file.relative_to(root)
        text = file.read_text()
        if "V2ConfigFormat.NATIVE_V3" in text and relative not in {config / "V2ConfigSource.kt", config / "V2ConfigTransport.kt", STACK}:
            errors.append("native v3 request selection must remain uninstalled in production Stack")
        if "NativeV3ConfigParser.parse(" in text and relative != config / "V2ConfigSource.kt":
            errors.append("native v3 parser acquisition belongs only to the original source")


def verify_native_raster_bootstrap_boundary(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics"
    required = {
        "EluOptions.kt": ["private var declaredRegionReplay = false", "declaredRegionReplayEnabled: Boolean",
            "public val declaredRegionReplayEnabled: Boolean get() = declaredRegionReplay"],
        "internal/facade/AndroidStandaloneStack.kt": ["declaredRegionReplayEnabled: Boolean = false",
            "format = if (declaredRegionReplayEnabled)", "V2ConfigFormat.NATIVE_V3",
            "else dev.elu.analytics.internal.config.V2ConfigFormat.V2", "rasterSupported = declaredRegionReplayEnabled"],
        "internal/runtime/PrivacyStateProjector.kt": ["support = if (capabilities.rasterSupported)",
            "ReplayDeliverySupport.INCLUDING_RASTER", "else dev.elu.analytics.internal.replay.ReplayDeliverySupport.WIREFRAME_ONLY"],
        "internal/replay/NativeReplayComposition.kt": ["declaredRoots.deniesWireframe(selected.originalRootIdentity())",
            "declaredRoots.remember(selected.originalRootIdentity())", "private val declaredRoots = NativeDeclaredRootHistory()",
            "if (roots.size >= 64) { uncertain = true; return }", "if (uncertain) return true",
            "roots.removeAll { it.isCollected() }", "originalAuthority.prepareRaster(selected, discovery.binding.sourceIdentity)",
            "NativeReplayCaptureOwner.startRaster(queue, originalAuthority, prepared, discovery.binding"],
        "internal/replay/NativeReplayLifecycle.kt": ["original?.discard()", "owned = value",
            "internal fun originalRootIdentity(): NativeReplayOriginalRootIdentity?", "private val root = WeakReference(root)",
            "if (value != null && stopped()) { discard();", "try { discard() } catch"],
        "internal/replay/NativeReplayCaptureOwner.kt": ["binding.sourceIdentity !== prepared.projection.sourceIdentity",
            "candidate.compareAndSet(null, frame)", "frame.sourceIdentity === binding.sourceIdentity", "val remainingCandidate = candidate.getAndSet(null)", "remainingCandidate?.close()", "actual.frame.hasCleanupFailure()", "remainingCandidate?.hasCleanupFailure() == true", "if (binding.hasCleanupFailure()) cleanupFailed = true", "minimumFork?.let { append();",
            "actual.continuous - first < minimum", "enrollment?.quarantineRaster(request)",
            "if (use == null) enrollment.cancelUnused() else use.settle()", "queue.finishNativeReplayCapture(enrollment).awaitExact()"],
        "internal/replay/AnnotatedRasterCandidate.kt": ["private var cleanupFailed = false", "@Synchronized internal fun hasCleanupFailure()",
            "catch (error: Throwable) { cleanupFailed = true; throw error }"],
        "internal/replay/AnnotatedRootRegistry.kt": ["originalCorners == corners(geometry)",
            "if (intent == null) owner.rootInvalidated(this)", "if (!same) sourceBindingChanged()"],
        "internal/replay/AndroidAnnotatedReplayCollector.kt": ["internal fun prepareBinding(", "check(registry == null)",
            "checkNotNull(AnnotatedRootRegistry.fromHost(view))", "return collector.capture(window, { isCurrent() && current() }, clock)", "cleanupFailed.set(true)", "!collector.hasCleanupFailure()"],
    }
    for path, tokens in required.items():
        text = load_text(root, base / path)
        if any(token not in text for token in tokens):
            errors.append("declared raster bootstrap lost default, original source, cleanup or privacy boundary: " + path)
    stack = load_text(root, STACK)
    if stack.count("V2ConfigFormat.NATIVE_V3") != 1 or stack.count("val source = V2ConfigSource(") != 1:
        errors.append("native v3 request selection must remain uninstalled in production Stack outside the one explicit option")
    replay = base / "internal/replay"
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        relative = file.relative_to(root); text = file.read_text()
        if re.search(r"\brasterSupported\s*=", text) and relative != STACK:
            errors.append("raster capability selection escaped original default-off Stack")
        if "AnnotatedCaptureBinding(" in text and relative != replay / "AndroidAnnotatedReplayCollector.kt":
            errors.append("annotated binding must originate only at original collector")
        if "discoverAnnotatedRoot(" in text and relative not in {replay / "AndroidAnnotatedReplayCollector.kt", replay / "NativeReplayComposition.kt"}:
            errors.append("annotated discovery escaped original composition")


def verify_native_raster_durable_boundary(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics/internal"
    replay = base / "replay"
    authority = replay / "NativeReplayAuthority.kt"
    source = base / "config/V2ConfigSource.kt"
    gate = base / "config/V2ConfigAuthorityGate.kt"
    lifecycle = base / "config/V2ConfigLifecycleDriver.kt"
    storage = replay / "NativeRasterStorage.kt"
    store = replay / "ReplayQueueStore.kt"
    sealer = replay / "NativeRasterSealer.kt"
    candidate = replay / "AnnotatedRasterCandidate.kt"
    required = {
        base / "runtime/RuntimeDiagnosticsState.kt": [
            "RUNTIME_RASTER_SCHEMA_OFFSET = 128", "in 1L..12L, in 25L..54L -> version",
            "133L, 134L, 139L, 140L, 157L, 158L, 163L, 164L,",
            "169L, 170L, 175L, 176L, 181L, 182L -> version - RUNTIME_RASTER_SCHEMA_OFFSET",
            "else -> throw UnsupportedRuntimeStorageSchemaException(version)"],
        SQLITE_DATABASE: ["override fun ensureNativeRasterReplaySchema()", "state.copy(rasterStorage = true).row()",
            "runtimeDatabaseRasterOffset(version)", "runtimeNormalizedDatabaseVersion(version)"],
        source: ["internal class V2RasterConflictReceipt private constructor", "pendingRasterConflict === original",
            "if (!prior.conflicted)", "V2RasterConflictReceipt.issue(parsed.issuedAt, prior.semanticHash, nativeV3.semanticHash)",
            "fun closeSource() = original.close()", "if (format == V2ConfigFormat.NATIVE_V3) V2RasterConflictChannel(this) else null"],
        gate: ["pendingDenial === value", "value.originalChannel.acknowledge(value.receipt)", "value.belongsTo(this)",
            "originalRasterConflicts?.closeSource()", "originalRasterConflicts === channel"],
        lifecycle: ["source.rasterConflictReceipt()", "publishedUpdate?.rasterConflict === conflict", "source.rasterConflictChannel"],
        OWNER: ["if (!gate.ownsDenial(denial))", "ReplayQueueStore.recordRasterDenial(tx, denial.receipt)",
            "if (known) gate.acknowledgeDenial(denial)", "reconcileRasterSourceOnWorker(witness)",
            "fun ensureRestrictionStorage()", "configurationGate?.close() // Fences original source validation",
            "flushRasterSourceDenialOnWorker()", "nativeSettlementUncertain = previousUncertain",
            "request.sourceIdentity !== admission.permit.sourceIdentity", "request.originalCaptureIsCurrent()",
            "NativeRasterStoredRequest.parse(bytes)", "finally { bytes.fill(0) }", "use.enrollment.quarantineRaster(request)",
            "session.activeEpoch == receipt.epoch", "stored.policyRevision == admission.policy.revision",
            "stored.effectivePolicyHash == admission.policy.effectivePolicyHash"],
        authority: ["internal val rasterSupported: Boolean = false", "captureUse = use", "receipt = started.receipt",
            "NativeRasterPermit.issue", "rasterActive = null"],
        store: ["require(state.rasterStorage == tx.nativeRasterReplaySchemaPresent())", "delivery.raster == header.raster",
            "config.issuedAtInstant > V1ConfigJson.parseExactTimestamp(old.issuedAt)", "order == 0 -> checkNotNull(old).copy(conflicted = true)",
            "V1ConfigJson.parseExactTimestamp(state.issuedAt) >", "baseOrder < 0 || (baseOrder == 0 && state.poisoned)",
            "else -> old", "ledger.semanticHash != wrapper.semanticHash", "core.queueCount + state.count + 1 > maximumCount"],
        storage: ["class NativeRasterStoredRequest private constructor", '"elu-sdk-replay-request-v3"',
            "V1StrictCanonicalJson.canonicalBytes(root).contentEquals(input)", "input.copyOf()"],
        candidate: ["internal fun publicationGuard(): () -> Boolean = current"],
        sealer: ["frame.publicationGuard()", "sourceIdentity.isCurrent() && originalCaptureCurrent()",
            "val originalSourceCurrent = sourceIsCurrent", "originalFrameCurrent() && originalSourceCurrent()"],
    }
    for path, tokens in required.items():
        text = load_text(root, path)
        if any(token not in text for token in tokens):
            errors.append("raster durability lost closed schema, original denial or append ownership: " + str(path))
    permitted = {
        "ensureNativeRasterReplaySchema": {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE},
        "ensureNativeRasterStorage": {OWNER, authority},
        "prepareNativeRasterProjection": {OWNER, authority},
        "beginNativeRasterAuthority": {OWNER, authority},
        "makeNativeRasterCaptureAdmission": {OWNER, authority},
        "appendNativeRaster": {OWNER, replay / "NativeReplayCaptureOwner.kt"},
        "prepareRaster": {authority, replay / "NativeReplayComposition.kt"},
        "startRaster": {authority, replay / "NativeReplayCaptureOwner.kt", replay / "NativeReplayComposition.kt"},
        "recordRasterDenial": {OWNER, store}, "acknowledgeDenial": {OWNER, gate},
        "publicationGuard": {candidate, sealer},
    }
    issuers = {"NativeRasterPreparedProjection": OWNER, "NativeRasterCaptureAdmission": OWNER,
        "NativeRasterPreparedAuthority": authority, "NativeRasterPermit": authority,
        "V2RasterConflictReceipt": source}
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        path = file.relative_to(root); text = file.read_text()
        for name, paths in permitted.items():
            if re.search(r"\b" + name + r"\b", text) and path not in paths:
                errors.append("raster durability escaped original internal owner: " + name)
        for name, issuer in issuers.items():
            if re.search(r"\b" + name + r"\s*(?:\.\s*Companion\s*)?(?:\.|::)\s*issue\b", text) and path != issuer:
                errors.append("raster capability escaped its original issuer: " + name)
        if path != gate and re.search(r"\bV2RasterDenialWitness\s*\(", text):
            errors.append("raster denial must retain its original gate and update")
        if path != source and re.search(r"\bV2RasterConflictChannel\s*\(", text):
            errors.append("raster denial channel must retain its original source")
        if path not in {source, lifecycle, gate} and re.search(r"\bV2RasterConflictChannel\b|\.rasterConflictChannel\b", text):
            errors.append("raster denial channel cannot escape source lifecycle gate")
        if "rasterSupported = true" in text:
            errors.append("raster capability must remain uninstalled")
    # An outer marker cannot legalize future/absent old families, and cannot authorize decoding.
    if re.search(r"GZIPInputStream|Inflater|Bitmap|BitmapFactory|NativeRasterPreparedRequest\s*\(", load_text(root, storage)):
        errors.append("restored raster facts cannot decode pixels or revive producer authority")


def verify_native_raster_delivery_boundary(root: pathlib.Path, errors: list[str]) -> None:
    base = MAIN_KOTLIN / "dev/elu/analytics/internal"
    replay = base / "replay"
    required = {
        replay / "ReplayDeliveryModels.kt": [
            "val support: ReplayDeliverySupport = ReplayDeliverySupport.WIREFRAME_ONLY",
            "private sealed interface Payload", "internal val originalSource:",
            "NativeRasterResponseOutcome.RejectedTooLarge -> ReplayDeliveryOutcome.Blocked(ReplayBlockKind.RASTER_TOO_LARGE)",
            "NativeRasterResponseClassifier.classify(response, p.row.request, now, retry)"],
        replay / "ReplayDeliveryMetadata.kt": [
            "row.storageSchemaVersion in setOf(1L, 2L)", "row.storageSchemaVersion == 2L",
            'setOf("replayId", "sourceIssuedAt")', "require(value.protocolGeneration == NativeRasterSealer.GENERATION)",
            "value.row().payload.contentEquals(row.payload)",
            "if (retirement) it == 0L else it in 1..31",
            "if (retirement) require(value.credentialWitness.isEmpty() && value.scopeWitness.isEmpty())"],
        replay / "ReplayQueueStore.kt": [
            "if (rasterEpochBlocked(tx, request.replayId))", "it.raster == raster",
            "originalSource == null", "request.effectivePolicyHash != authorization.effectivePolicyHash",
            "d.replayId == claim.replayId && d.sourceIssuedAt == claim.authorization.sourceIssuedAt",
            "require(claim.format != ReplayDeliveryFormat.RASTER || outcome == ReplayDeliveryOutcome.Accepted)",
            "header.replayId in expiredRaster || header.replayId in retiredRaster",
            "it.replayId !in expiredEpochs && it.replayId !in retiredEpochs",
            "if (mayRemoveRaster(it.replayId)) return@filter true",
            "markRasterRetiring(tx, current, all, it.replayId)",
            "sourceIssuedAt = prior?.sourceIssuedAt ?: anchor",
            "val anchor = checkNotNull(original.rasterSource).issuedAt",
            "d.scopeWitness == authorization.scopeWitness", "it.scopeWitness == authority.scopeWitness",
            "eligibleRaster(readRaster(tx, it).request)", "if (heads.isEmpty()) return null"],
        OWNER: [
            "policy.support != ReplayDeliverySupport.INCLUDING_RASTER", "claim.originalSource !== witness",
            "claim.originalSource ?: configurationGate?.snapshot()", "rasterIdentityMatches",
            "request.identityRevision == identity.revision && request.contextRevision == identity.contextRevision",
            "nativeCaptureEnrollment != null || replayPhysicalClaim?.replayId == replayId",
            "nativeCaptureEnrollment?.withdraw()", "replayPhysicalOperation?.cancel()",
            "nativeSettlementUncertain || nativeCaptureEnrollment != null", "drainRasterRetirementOnWorker(claim)",
            "ReplayQueueStore.removeRasterEpochs(tx, ready)", "configurationGate?.rasterDenial() != null"],
        replay / "OkHttpReplayTransport.kt": [
            "private val format: ReplayDeliveryFormat = ReplayDeliveryFormat.WIREFRAME",
            "endpointPolicy.requireNativeRasterApproved(endpoint)", "claim.format == format",
            "val body = claim.copyBody()", "val classified = claim.classify(result, 0, 0)",
            "rasterRefusal != null -> flight.result.complete(rasterRefusal)", "body.fill(0)"],
        base / "config/LocalEndpointPolicy.kt": [
            'require(endpoint.rawPath == apiPrefix + "/v3/replay")', "requireApproved(legacyRole, V1EndpointRole.REPLAY)"],
    }
    for path, tokens in required.items():
        try: text = load_text(root, path)
        except ValueError as error:
            errors.append(str(error)); continue
        if any(token not in text for token in tokens):
            errors.append("raster delivery lost original source, refusal, scope or settlement boundary: " + str(path))
    try: owner = load_text(root, OWNER)
    except ValueError as error:
        errors.append(str(error)); return
    if "drainRasterRetirementOnWorker(claim)" in owner and owner.index("drainRasterRetirementOnWorker(claim)") > owner.index("if (replayPhysicalOperation === started)"):
        errors.append("raster retirement must settle before clearing original physical slot")
    models = replay / "ReplayDeliveryModels.kt"
    store = replay / "ReplayQueueStore.kt"
    for file in (root / MAIN_KOTLIN).rglob("*.kt"):
        relative = file.relative_to(root); text = file.read_text()
        if "ReplayDeliverySupport.INCLUDING_RASTER" in text and relative not in {OWNER, MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/PrivacyStateProjector.kt"}:
            errors.append("raster delivery support must remain default-off and uninstalled outside original projection")
        if "ReplayDeliveryClaim.raster(" in text and relative != store:
            errors.append("raster delivery claims belong only to the original queue")
        if "requireNativeRasterApproved(" in text and relative not in {base / "config/LocalEndpointPolicy.kt", replay / "OkHttpReplayTransport.kt"}:
            errors.append("raster endpoint role does not grant producer or delivery authority")
        if re.search(r"NativeRasterStoredRequest\.parse\(", text) and relative in {models, replay / "OkHttpReplayTransport.kt", replay / "NativeRasterResponseClassifier.kt"}:
            errors.append("delivery cannot replace the original stored request")


def verify(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    verify_native_raster_durable_boundary(root, errors)
    verify_native_raster_delivery_boundary(root, errors)
    verify_compose_distribution_boundary(root, errors)
    verify_native_raster_response_boundary(root, errors)
    verify_native_v3_parser_boundary(root, errors)
    verify_native_v3_source_boundary(root, errors)
    verify_raster_sealer_boundary(root, errors)
    verify_annotated_root_boundary(root, errors)
    verify_exception_intake(root, errors)
    verify_capture_rate_limiter(root, errors)
    verify_replay_controls(root, errors)
    verify_replay_continuity(root, errors)
    verify_durable_flag_exposures(root, errors)
    verify_local_endpoint_binding(root, errors)
    verify_person_selection(root, errors)
    verify_pins(root, errors)
    verify_contract_status(root, errors)
    verify_no_wiring(root, errors)
    verify_lifecycle_bootstrap(root, errors)
    verify_prepared_replay_boundary(root, errors)
    verify_native_authority_boundary(root, errors)
    verify_frame_observer_boundary(root, errors)
    verify_startup_observer_boundary(root, errors)
    verify_network_observer_boundary(root, errors)
    return errors


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    errors = verify(args.root.resolve())
    if errors:
        for error in errors:
            print(f"feature-flag boundary failed: {error}", file=sys.stderr)
        raise SystemExit(1)
    print("feature-flag isolation verified: contracts pinned, owned public sink and private completion, exact internal standalone composition")


if __name__ == "__main__":
    main()
