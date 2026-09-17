#!/usr/bin/env python3
"""Enforce the exact owned public runtime and closed native proof boundary."""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ET


PINNED_FILES = {
    "elu-analytics/src/main/AndroidManifest.xml":
        "531cc169655bb89c4544a7a52e03328fb3e24a486c1d5c7e07812b6b9f93aae6",
    # Approved public/config surfaces and the runtime dependency manifest.
    "elu-analytics/src/main/kotlin/dev/elu/analytics/Elu.kt":
        "5594960ce9cfed899ec0601fcfeed96ca57bb4d0f6589d64f4a8d0428aab9e77",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluConfigClient.kt":
        "ed9e65335829cf348ee992059efc03a523e61c79ef000575814f3e81f4eae642",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/EluOptions.kt":
        "9ddb19c0fc3ca1a6f255ad8c287f3dc46069d292264326587106b85cc4ea310f",
    "elu-analytics/build.gradle.kts":
        "2c13cd9bfd128400cdab5c4d27fe3ed5d4fa9f323f46cfe45964ccb7e3e99dc2",
    "elu-analytics/consumer-rules.pro":
        "9142fe48201969d44ae8030c19d96987f678df7f9aa5b7a5ebddc1e8f233c03b",
    "elu-analytics/src/main/kotlin/dev/elu/analytics/internal/concurrent/SdkFuture.kt":
        "13b2077148db05774a72c47efb0c864b67b571dac72af77e1343f8a5e97de9a9",
    # These files carry the explicit no-wire release status.
    "elu-analytics/src/test/resources/contracts/v1/manifest.json":
        "98152d8725c286f29402ba3e420bda8dd364200fb6fdf1cfe49b2da9b8f63e54",
    "elu-analytics/src/test/resources/contracts/v1/fixtures/transport-policy.json":
        "992900180683af04f69d5e459b7c0c9e68edf92c6ebf320136ed36dbae8b60ce",
}

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
    provider = re.compile(r"\bcom\s*\.\s*posthog\b|\bPostHog(?:Android|Config|Interface)?\b")
    legacy = re.compile(r"\b(?:EluCore|EluReplayBudget|EluRuntimeMode|EluRuntimeSelector|EluFacadeLane|EmbeddedRuntimeSink)\b")
    platform_future = re.compile(r"\bjava\s*\.\s*util\s*\.\s*concurrent\s*\.\s*(?:CompletableFuture|CompletionStage|CompletionException)\b|\bimport\s+java\.util\.concurrent\.\*")
    for relative, text in sources.items():
        if provider.search(text) or legacy.search(text):
            errors.append(f"provider or runtime selector reintroduced into owned source: {relative}")
        if platform_future.search(text):
            errors.append(f"API23 runtime must use the private completion primitive: {relative}")
    public = sources.get(MAIN_KOTLIN / "dev/elu/analytics/Elu.kt", "")
    if ("@Volatile private var sink: EluFacadeSink? = null" not in public or
        public.count("AndroidStandaloneStack.facade(appContext, key, configHost)") != 1):
        errors.append("public setup must construct exactly the owned standalone sink with the validated host")
    if not (0 <= public.find("val facade = AndroidStandaloneStack.facade(") < public.find("sink = facade") < public.find("facade.start()")):
        errors.append("public setup must publish the exact owned sink before starting")
    build = load_text(root, "elu-analytics/build.gradle.kts")
    if re.search(r"(?i)com\.posthog|posthog-android", build):
        errors.append("provider dependency must not return to the owned runtime")
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
    if router.count(TRANSPORT_NAME) != 3:
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
                         r"EluRuntimeSelector|EluCore|PostHog)\b|java\.net|okhttp|\bcapture\s*\(", text):
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
        if ("ensureNativeReplayAccounting" in text and relative not in {OWNER, composition}) or (
            any(name in text for name in ("observeNativeReplaySession", "beginNativeReplayAccounting")) and relative != OWNER) or (
            "stopNativeReplayAccounting" in text and relative not in {OWNER, replay_package / "NativeReplayAuthority.kt"}
        ):
            errors.append(f"native replay accounting must remain unconstructed: {relative}")
        if "ensureReplaySchema" in text and relative not in {OWNER, DATABASE_INTERFACE, SQLITE_DATABASE}:
            errors.append(f"replay schema migration escaped its internal owner: {relative}")
        if ("ensurePreparedReplayStorage" in text and relative not in {OWNER, composition}) or (
            any(name in text for name in ("appendPreparedReplay", "reconcilePreparedReplay",
                                         "expirePreparedReplay", "storedPreparedReplayForTesting")) and relative != OWNER):
            errors.append(f"prepared replay storage must remain unconstructed: {relative}")
        if "openReplayDeliveryQueue" in text and relative not in {OWNER, composition}:
            errors.append(f"prepared replay delivery binding must remain unconstructed: {relative}")
        if "ReplayDeliveryCoordinator" in text and relative not in {replay_package / "ReplayDeliveryCoordinator.kt", composition}:
            errors.append(f"prepared replay coordinator must remain unconstructed: {relative}")
        if ("readbackProvenReplayTransports" in text or "replayMaskingAdmission" in text or "supportedReplayProtocolGenerations" in text) and relative != OWNER and relative.parent != config_package:
            errors.append(f"production composition must not supply replay proof: {relative}")
        if "NativeReplaySealer" in text and relative not in {replay_package / "NativeReplaySealer.kt", replay_package / "NativeReplayCaptureOwner.kt"}:
            errors.append(f"native replay sealer must remain unconstructed: {relative}")
        if "OkHttpReplayTransport" in text and relative not in {replay_package / "OkHttpReplayTransport.kt", composition}:
            errors.append(f"prepared replay HTTP transport must remain unconstructed: {relative}")
        # This exact composition may use only the owned adapter name, never a raw client/API.
        network_text = re.sub(r"\bOkHttpReplayTransport\b", "", text) if relative == composition else text
        if relative.parent == replay_package and relative != replay_package / "OkHttpReplayTransport.kt" and FORBIDDEN_EGRESS.search(network_text):
            errors.append(f"prepared replay storage must not contain network or HTTP code: {relative}")
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
    initializer = runtime / "EluLifecycleInitializer.kt"
    projector = runtime / "PrivacyStateProjector.kt"
    sources = {path.relative_to(root): path.read_text(encoding="utf-8")
               for path in sorted((root / MAIN_KOTLIN).rglob("*.kt"))}
    allowed = {
        "NativeReplayComposition": {composition, STACK, runtime / "StandaloneRuntime.kt"},
        "NativeReplayAuthority": {authority, loop, composition},
        "NativeReplayFrameBuffer": {replay / "NativeReplayFrameBuffer.kt", loop},
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
        "AndroidNativeReplayCapturePlatform": {loop, composition},
    }
    projection_methods = ("observeNativeReplayProjection", "prepareNativeReplayProjection",
                          "beginNativeReplayAuthority", "flushNativeReplayClockDenial")
    capture_methods = {
        "consumeOriginalRoot": {lifecycle, loop},
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
        for symbol, permitted in allowed.items():
            if re.search(r"\b" + symbol + r"\b", text) and relative not in permitted:
                errors.append(f"native authority reference escaped its exact seam: {symbol}: {relative}")
        if any(name in text for name in projection_methods) and relative not in {OWNER, authority}:
            errors.append(f"native projection calls escaped authority/queue: {relative}")
        for name, permitted in capture_methods.items():
            if re.search(r"\b" + name + r"\b", text) and relative not in permitted:
                errors.append(f"native capture call escaped its exact seam: {name}: {relative}")
        if re.search(r"\bAndroidViewReplayCollector\b", text) and relative not in {replay / "AndroidViewReplayCollector.kt", loop}:
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
                     "old.stop().awaitExact()", "original.settlement.whenComplete",
                     "!closed.get() && !canceled.get() && authorizeIo()",
                     "original = intent; useForce = forceRequested; forceRequested = false; requested = false",
                     "runEvaluation(result, original, useForce, acceptance)", "acceptance() && current(original) && acceptance()", "if (includeDelivery) deliveryEpoch.set(null)",
                     "original != null && deliveryEpoch.get() === original && authorizeIo()",
                     "if (acceptance() && it.current(original) && acceptance()) it.reevaluate(originalAcceptance = acceptance)"]:
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
            "nativeReplayLifecycleChanged": {STACK, facade_file},
            "withdrawNativeReplay": {STACK, facade_file, runtime_file},
            "reevaluateNativeReplay": {facade_file, runtime_file},
        }.items():
            if re.search(r"\b" + method + r"\b", text) and relative not in permitted:
                errors.append("native public-private lifecycle bridge escaped exact assembly: " + method)
    stack_source = sources.get(STACK, "")
    if len(re.findall(r"\bNativeReplayComposition\s*\(", stack_source)) != 1 or stack_source.count("NativeReplayCapabilities()") != 1:
        errors.append("standalone must construct exactly one native composition with empty local proof")
    ready = stack_source.find("native.ready().get()")
    construct = stack_source.find("val runtime = StandaloneRuntime(")
    if not (0 <= ready < construct) or "AndroidProcessLifecycle.nativeObserved" not in stack_source or "facade::nativeReplayIntakeAllowed" not in stack_source:
        errors.append("native assembly must finish preparation and retain original lifecycle/intent before runtime")
    runtime_source = sources.get(runtime_file, "")
    close_native = runtime_source.find("originalNativeClose?.awaitExact()")
    close_queue = runtime_source.find("owner.closeAsync().awaitExact()")
    if not (0 <= close_native < close_queue) or "controlExecutor.execute" not in runtime_source:
        errors.append("native runtime close must join original physical settlement before queue on control worker")
    if "nativeReplay?.reevaluate(force, originalAcceptance)" not in runtime_source:
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
    for name, permitted in capture_methods.items():
        if any(len(re.findall(r"\b" + name + r"\b", sources.get(path, ""))) != 1 for path in permitted):
            errors.append(f"native capture call must retain exactly its original declaration/call: {name}")
    if (len(re.findall(r"originalUse\s*===\s*use", account)) != 3 or
            "if (taken || physicalFinished || intakeClosed || released || quarantined)" not in account or
            "taken = true; NativeReplayCapturePhysicalUse.issue(this)" not in account):
        errors.append("native physical use must retain one-shot original-use checks")
    owner = sources.get(OWNER, "")
    if not re.search(r'if\s*\(request\.transport\.codec\s*==\s*"elu-native-wireframe-v1"\)\s*return@submit\s+ReplayAppendResult\.Rejected\(ReplayAppendRejection\.AUTHORITY\)', owner):
        errors.append("generic replay append must reject native codec without original capture admission")
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
    for relative, source in sources.items():
        if "observeNativeReplayOutline" in source and relative not in {collector_path, lifecycle}:
            errors.append("native outline observation must remain within collector/selection")
    if (life.count("observeNativeReplayOutline(decor, decor)") != 1 or
            'observeNativeReplayOutline(decor, decor) { check(current())' not in life or
            collector.count("observeNativeReplayOutline(view, originalWindowDecor, ::check)") != 1):
        errors.append("native outline observation must consume original selection/collection withdrawal checks")
    if "fun <T> read(getter: () -> T): T { check(); val value = getter(); check(); return value }" not in collector:
        errors.append("native outline getters must retain pre/post original withdrawal checks")
    for required in ["view === originalWindowDecor", 'view.javaClass.name == "com.android.internal.policy.DecorView"',
                     "view.javaClass.classLoader === View::class.java.classLoader", "background.javaClass === ColorDrawable::class.java",
                     "val originalWindowDecor = read { root.rootView }", "read { root.rootView } !== originalWindowDecor"]:
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
    if (life.count("matchesDiscovery(selectedActivity, selectedRoot)") != 2 or
            "matchesDiscovery(activity, root)" not in life or
            "matchesDiscovery(checkNotNull(activity), checkNotNull(root))" not in life or
            "NativeReplaySelection.issue(access, selectedActivity, selectedRoot, facts, discovered)" not in life):
        errors.append("native discovered-root ownership must survive every main collection boundary")
    disposal = life.split("private fun discardUnpublished", 1)[-1].split("internal class NativeReplaySelection", 1)[0]
    registered = disposal.find("unsettledSelections.add(selection)")
    closing = disposal.find("selection.closeAndWait()")
    if not (0 <= registered < closing) or "if (cleanupError == null) synchronized(monitor) { unsettledSelections.remove(selection) }" not in disposal:
        errors.append("native unpublished disposal must reserve before close and release only proven cleanup")
    if not re.search(r"fun\s+consumeOriginalRoot\s*\(\s*current:\s*\(\)\s*->\s*Boolean,\s*consume:\s*\(Any,\s*\(\)\s*->\s*Boolean\)\s*->\s*NativeMaskedSnapshot\?,\s*\):\s*SdkFuture<NativeMaskedSnapshot\?>", life):
        errors.append("native root consumption must return only a detached masked snapshot")
    if "facts.apiLevel < 29" not in life or "Build.VERSION.SDK_INT < 29" not in life:
        errors.append("native selection must refuse API levels below 29")
    for callback in ["onActivityPrePaused", "onActivityPreStopped", "onActivityPreDestroyed", "onActivityPaused"]:
        if not re.search(r"override\s+fun\s+" + callback + r"\(activity:\s*Activity\)\s*=\s*nativeObserved\.withdrawing\(activity\)", initial):
            errors.append(f"native lifecycle withdrawal must be synchronous at {callback}")
    if auth.count("stopNativeReplayAccounting") != 1:
        errors.append("native authority must retain its single original accounting settlement call")


def verify(root: pathlib.Path) -> list[str]:
    errors: list[str] = []
    verify_pins(root, errors)
    verify_contract_status(root, errors)
    verify_no_wiring(root, errors)
    verify_lifecycle_bootstrap(root, errors)
    verify_prepared_replay_boundary(root, errors)
    verify_native_authority_boundary(root, errors)
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
