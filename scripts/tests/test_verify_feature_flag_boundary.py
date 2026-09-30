from __future__ import annotations

import importlib.util
import json
import pathlib
import shutil
import subprocess
import sys
import tempfile
import unittest


REPOSITORY = pathlib.Path(__file__).resolve().parents[2]
SCRIPT = REPOSITORY / "scripts" / "verify-feature-flag-boundary.py"
SPEC = importlib.util.spec_from_file_location("verify_feature_flag_boundary", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
BOUNDARY = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(BOUNDARY)


class FeatureFlagBoundaryGuardTest(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory(prefix="elu-flag-boundary-")
        self.root = pathlib.Path(self.temporary.name)
        main_source = REPOSITORY / BOUNDARY.MAIN_KOTLIN
        shutil.copytree(main_source, self.root / BOUNDARY.MAIN_KOTLIN)
        for relative in {*BOUNDARY.PINNED_FILES, *BOUNDARY.CONTRACT_FILES, *BOUNDARY.ANNOTATED_EXTRA_FILES}:
            source = REPOSITORY / relative
            target = self.root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def run_guard(self) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, str(SCRIPT), "--root", str(self.root)],
            check=False,
            capture_output=True,
            text=True,
        )

    def test_raster_durable_exact_schema_and_original_issuers_remain_closed(self) -> None:
        def errors() -> str:
            result: list[str] = []
            BOUNDARY.verify_native_raster_durable_boundary(self.root, result)
            return "\n".join(result)
        self.assertEqual("", errors())
        relative = BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/RuntimeDiagnosticsState.kt"
        path = self.root / relative; original = path.read_text()
        for changed in (original.replace("in 25L..54L -> version", "in 25L..55L -> version"),
                original.replace("181L, 182L ->", "181L, 182L, 183L ->"),
                original.replace("RUNTIME_RASTER_SCHEMA_OFFSET = 128", "RUNTIME_RASTER_SCHEMA_OFFSET = 129")):
            path.write_text(changed); self.assertIn("raster durability lost", errors())
        path.write_text(original)
        foreign = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/RasterEscape.kt"
        for source in ("fun escape() = V2RasterConflictReceipt.issue(a, b, c)",
                "fun escape() = NativeRasterCaptureAdmission.issue(a, b, c)",
                "fun escape() = V2RasterDenialWitness(a, b, c)",
                "fun escape() = V2RasterConflictChannel(a)",
                "fun escape() = owner.appendNativeRaster(a, b, c)",
                "val rasterSupported = true", "fun escape() = frame.publicationGuard()"):
            foreign.write_text(source); self.assertTrue(errors())
        foreign.unlink(); self.assertEqual("", errors())

    def test_raster_durable_denial_and_pixel_free_append_checks_cannot_be_removed(self) -> None:
        for relative, token in (
            ("runtime/RuntimeQueueOwner.kt", "if (known) gate.acknowledgeDenial(denial)"),
            ("runtime/RuntimeQueueOwner.kt", "request.originalCaptureIsCurrent()"),
            ("replay/NativeRasterSealer.kt", "frame.publicationGuard()"),
            ("replay/NativeRasterSealer.kt", "originalFrameCurrent() && originalSourceCurrent()"),
            ("config/V2ConfigAuthorityGate.kt", "pendingDenial === value"),
            ("config/V2ConfigAuthorityGate.kt", "originalRasterConflicts?.closeSource()"),
            ("config/V2ConfigSource.kt", "fun closeSource() = original.close()"),
            ("runtime/RuntimeQueueOwner.kt", "configurationGate?.close() // Fences original source validation"),
            ("replay/ReplayQueueStore.kt", "V1ConfigJson.parseExactTimestamp(state.issuedAt) >"),
            ("replay/ReplayQueueStore.kt", "else -> old")):
            path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal" / relative
            original = path.read_text(); path.write_text(original.replace(token, "false"))
            errors: list[str] = []; BOUNDARY.verify_native_raster_durable_boundary(self.root, errors)
            self.assertTrue(errors, relative); path.write_text(original)
        storage = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeRasterStorage.kt"
        original = storage.read_text()
        for forbidden in ("GZIPInputStream", "BitmapFactory", "NativeRasterPreparedRequest("):
            storage.write_text(original + "\n// " + forbidden)
            errors = []; BOUNDARY.verify_native_raster_durable_boundary(self.root, errors)
            self.assertIn("restored raster facts cannot decode", "\n".join(errors))
        storage.write_text(original)

    def test_compose_distribution_cannot_enable_remote_publication_or_substitute_project_consumer(self) -> None:
        def errors() -> str:
            result: list[str] = []
            BOUNDARY.verify_compose_distribution_boundary(self.root, result)
            return "\n".join(result)
        self.assertEqual(errors(), "")
        cases = [
            ("elu-analytics/build.gradle.kts", 'version = sdkVersion', 'version = "0.1.0"', "project identity"),
            ("elu-analytics-compose/build.gradle.kts", 'group = "dev.elu"', 'group = "other"', "project identity"),
            ("elu-analytics-compose/build.gradle.kts", 'id("com.vanniktech.maven.publish.base")',
             'id("com.vanniktech.maven.publish")', "metadata only"),
            ("elu-analytics-compose/build.gradle.kts", 'coordinates("dev.elu", "elu-analytics-compose", sdkVersion)',
             'coordinates("dev.elu", "elu-analytics-compose", sdkVersion); publishToMavenCentral()', "metadata only"),
            ("elu-analytics-compose/build.gradle.kts", 'check(allTasks.none { it.project == optionalProject && it is AbstractPublishToMaven })',
             'check(true)', "metadata only"),
            ("elu-analytics-compose/build.gradle.kts", 'check(optionalProject.extensions.getByType<PublishingExtension>().repositories.isEmpty())',
             'check(true)', "metadata only"),
            ("fixtures/compose-consumer/settings.gradle.kts", 'filter { includeGroup("dev.elu") }',
             'filter { includeGroup("unrelated") }', "staged Maven"),
            ("fixtures/compose-consumer/settings.gradle.kts", 'mavenPom(); ignoreGradleMetadataRedirection()',
             'mavenPom()', "staged Maven"),
            ("fixtures/compose-consumer/build.gradle.kts", 'implementation("dev.elu:elu-analytics-compose:$candidateVersion")',
             'implementation(project(":elu-analytics-compose"))', "staged Maven"),
            ("fixtures/compose-consumer/build.gradle.kts", 'check(actual == digest(staged.readBytes()))',
             'check(true)', "staged Maven"),
        ]
        for relative, before, after, message in cases:
            with self.subTest(relative=relative, before=before):
                path = self.root / relative; original = path.read_text()
                self.assertIn(before, original); path.write_text(original.replace(before, after))
                self.assertIn(message, errors()); path.write_text(original)

    def test_raster_response_binds_original_schema_and_only_static_helpers(self) -> None:
        def errors() -> str:
            result: list[str] = []
            BOUNDARY.verify_native_raster_response_boundary(self.root, result)
            return "\n".join(result)
        self.assertEqual(errors(), "")
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeRasterResponseClassifier.kt"
        original = path.read_text()
        for before, after, expected in [
            ('response.status == 200', 'response.status in 200..299', 'original request'),
            ('ack.number("schemaVersion") == 3L', 'ack.number("schemaVersion") == 2L', 'original request'),
            ('ack.number("sequence") == request.sequence', 'true', 'original request'),
            ('conflict.string("requestId", 72, 72) == request.requestId', 'true', 'original request'),
            ('"sequence" -> NativeRasterConflictScope.SEQUENCE', '"session" -> NativeRasterConflictScope.SEQUENCE', 'original request'),
            ('require(response.retryAfter == null)', 'Unit', 'bounded retry'),
            ('RetryAfterParser.parseDelayMillis(it, now)', 'RetryAfterParser.other(it, now)', 'exact static'),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original); path.write_text(original.replace(before, after))
                self.assertIn(expected, errors()); path.write_text(original)
        for token in ["NativeRasterPreparedRequest()", "request.clearRejected()", "RuntimeQueueOwner", "NativeReplayAuthority",
                      "ReplayDeliveryClaim", "ReplayDeliveryOutcome", "ReplayResponseClassifier", "PreparedReplayRequest",
                      "AnnotatedRasterCandidate", "Thread", "Executors", "URL.openConnection()"]:
            with self.subTest(token=token):
                path.write_text(original + "\n// " + token)
                self.assertIn("cannot construct", errors()); path.write_text(original)

    def test_raster_response_never_installs_in_runtime_or_sibling_sources(self) -> None:
        for relative in [BOUNDARY.STACK, BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/CopiedRasterResponse.kt"]:
            path = self.root / relative; original = path.read_text() if path.exists() else None
            for token in ["NativeRasterResponseClassifier.classify(value)", "NativeRasterResponseOutcome.Accepted", "NativeRasterConflictScope.REQUEST"]:
                path.write_text((original or "") + "\n// " + token)
                errors: list[str] = []; BOUNDARY.verify_native_raster_response_boundary(self.root, errors)
                self.assertIn("must remain uninstalled", "\n".join(errors))
            if original is None: path.unlink()
            else: path.write_text(original)

    def test_native_v3_parser_preserves_original_bytes_and_cannot_install_authority(self) -> None:
        self.assertEqual(self.run_guard().returncode, 0)
        config = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/config"
        parser = config / "NativeV3ConfigParser.kt"
        original = parser.read_text()
        for before, after, expected in [
            ("onMalformedInput(CodingErrorAction.REPORT)", "onMalformedInput(CodingErrorAction.REPLACE)", "original endpoint"),
            ("trusted(it.flags, V1EndpointRole.FLAGS)", "Unit", "original endpoint"),
            ("replay.advertisedTransports.size != 1", "false", "closed policy"),
            ("V1StrictCanonicalJson.canonicalize(candidate) != V1StrictCanonicalJson.canonicalize(expected)", "false", "closed policy"),
            ("minOf(limits.replayChunkBytes, 5_242_880)", "5_242_880", "closed policy"),
            ("internal object NativeV3ConfigParser", "// V2ConfigAuthorityGate\ninternal object NativeV3ConfigParser", "cannot install authority"),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                parser.write_text(original.replace(before, after))
                self.assertIn(expected, self.run_guard().stderr)
                parser.write_text(original)
        strict = config / "V1StrictCanonicalJson.kt"
        strict_original = strict.read_text()
        strict.write_text(strict_original.replace("depth == 1 && name == retainedRootProperty", "name == retainedRootProperty"))
        self.assertIn("complete strict parse", self.run_guard().stderr)
        strict.write_text(strict_original)
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\n// NativeV3ConfigParser.parse(bytes)\n")
        self.assertIn("must remain uninstalled", self.run_guard().stderr)

    def test_native_v3_source_keeps_original_receipt_selection_and_conflict_fences(self) -> None:
        self.assertEqual(self.run_guard().returncode, 0)
        config = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/config"
        def check() -> str:
            errors = []
            BOUNDARY.verify_native_v3_source_boundary(self.root, errors)
            BOUNDARY.verify_native_v3_parser_boundary(self.root, errors)
            return "\n".join(errors)
        cases = [
            ("V2ConfigTransport.kt", "tail[1] == format.pathVersion", "true", "exact selection"),
            ("V2ConfigSource.kt", "private val format: V2ConfigFormat = V2ConfigFormat.V2", "private val format: V2ConfigFormat = V2ConfigFormat.NATIVE_V3", "exact selection"),
            ("V2ConfigSource.kt", "prior.conflicted = true", "Unit", "conflict"),
            ("V2ConfigSource.kt", "parsed.data.contentEquals(strictConfigUtf8(receiptBody))", "true", "receipt"),
            ("V2ConfigSource.kt", "parsed.configV2Data.contentEquals(strictConfigUtf8(body))", "true", "receipt"),
            ("V2ConfigSource.kt", "minOf(deadline, prior?.deadline ?: deadline)", "deadline", "lease fence"),
            ("V2ConfigLifecycleDriver.kt", "sameReceipt(published, snapshot)", "published?.body == snapshot?.body", "receipt"),
            ("V2ConfigAuthorityGate.kt", "lease.body == body && lease.validReceiptBinding()", "true", "receipt"),
            ("V2ConfigAuthorityGate.kt", "lease?.receiptBody == witness.receiptBody && lease?.nativeV3 === witness.nativeV3", "true", "receipt"),
        ]
        for name, before, after, expected in cases:
            path = config / name; original = path.read_text()
            with self.subTest(name=name, before=before):
                self.assertIn(before, original)
                try:
                    path.write_text(original.replace(before, after)); self.assertIn(expected, check())
                finally: path.write_text(original)
        source = config / "V2ConfigSource.kt"; original = source.read_text()
        for addition, expected in [("envelopeBoundary = null", "erase"), ("transport.fetch(endpoint)", "fallback"),
                ("NativeRasterSealer", "cannot install")]:
            try:
                source.write_text(original + "\n// " + addition + "\n"); self.assertIn(expected, check())
            finally: source.write_text(original)
        stack = self.root / BOUNDARY.STACK; original = stack.read_text()
        try:
            stack.write_text(original + "\n// V2ConfigFormat.NATIVE_V3\n")
            self.assertIn("uninstalled in production Stack", check())
        finally: stack.write_text(original)
        sibling = config / "UnexpectedV3Source.kt"
        try:
            sibling.write_text("// NativeV3ConfigParser.parse(bytes)\n")
            self.assertIn("belongs only", check()); self.assertIn("must remain uninstalled", check())
        finally: sibling.unlink()

    def test_annotated_capture_remains_uninstalled_and_geometry_only(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        self.assertEqual(self.run_guard().returncode, 0)
        cases = [
            ("EluReplayRegionGeometry.kt", "public class EluReplayRegionGeometry(", "// Bitmap\npublic class EluReplayRegionGeometry(", "geometry only"),
            ("EluAnnotatedReplayRootScope.kt", "AnnotatedRootRegistry(view)", "AnnotatedRootRegistry(view).also { it.attach() }", "must not attach"),
            ("internal/replay/AndroidAnnotatedReplayCollector.kt", "{ current() && source.isCurrent() && registry.policyCurrent(acceptedPolicy) }", "{ current() && registry.policyCurrent(original.policyVersion) }", "declared ownership"),
            ("internal/replay/AndroidAnnotatedReplayCollector.kt", "validate(original, plan(window, checks))", "validate(original, original)", "revalidate original geometry"),
            ("internal/replay/AnnotatedRootRegistry.kt", "if (isClosed) null else reader.read()", "null", "declared ownership"),
            ("internal/replay/AnnotatedRasterCandidate.kt", "bitmap.eraseColor(Color.TRANSPARENT)", "Unit", "declared ownership"),
            ("internal/replay/AnnotatedRasterCandidate.kt", "try { close() } catch", "try { if (failure != null) close() } catch", "declared ownership"),
        ]
        for relative, before, after, expected in cases:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text(); self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn(expected, self.run_guard().stderr)
                path.write_text(original)
        stack = self.root / BOUNDARY.STACK
        collector = base / "internal/replay/AndroidAnnotatedReplayCollector.kt"
        original = collector.read_text()
        guard = 'if (Build.VERSION.SDK_INT < 29) error("unsupported-platform")'
        self.assertEqual(original.count(guard), 2)
        collector.write_text(original.replace(guard, "Unit", 1))
        self.assertIn("both refuse below API29", self.run_guard().stderr)
        collector.write_text(original)
        stack.write_text(stack.read_text() + "\n// AndroidAnnotatedReplayCollector(registry)\n")
        self.assertIn("must remain uninstalled", self.run_guard().stderr)

    def test_annotated_compose_fixture_keeps_core_dependency_and_compiler_boundaries(self) -> None:
        path = self.root / "elu-analytics/build.gradle.kts"; original = path.read_text()
        for changed, expected in [
            (original.replace('androidTestImplementation(project(":elu-analytics-compose"))', 'implementation(project(":elu-analytics-compose"))'), "androidTest-only"),
            (original.replace('"compileReleaseUnitTestKotlin"', '"compileReleaseUnitTestKotlin", "compileDebugAndroidTestKotlin"'), "strict AndroidTest"),
        ]:
            path.write_text(changed); self.assertIn(expected, self.run_guard().stderr)
        path.write_text(original)

    def test_raster_sealer_keeps_original_source_and_only_reuses_two_old_helpers(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        self.assertEqual(self.run_guard().returncode, 0)
        cases = [
            ("NativeRasterSealer.kt", "frame.sourceIdentity === sourceIdentity", "true", "original source"),
            ("NativeRasterSealer.kt", "timestamp - it >= 1_000", "timestamp - it >= 0", "original source"),
            ("NativeRasterSealer.kt", "private val sourceIsCurrent: () -> Boolean", "private val sourceIsCurrent: () -> Boolean = { true }", "raw images"),
            ("NativeRasterSealer.kt", "NativeReplaySealer.timestamp(timestamp)", "NativeReplaySealer(replayId)", "sealer must remain unconstructed"),
            ("NativeRasterSealer.kt", "NativeReplaySealer.timestamp(timestamp)", "NativeReplaySealer.envelope(timestamp)", "sealer must remain unconstructed"),
            ("NativeReplaySealer.kt", "private fun envelope(", "internal fun envelope(", "only timestamp/gzip"),
            ("AnnotatedRootRegistry.kt", "originalSource?.withdraw()", "Unit", "withdrawal binding"),
        ]
        for name, before, after, message in cases:
            with self.subTest(name=name, before=before):
                path = base / name; original = path.read_text(); self.assertIn(before, original)
                path.write_text(original.replace(before, after)); self.assertIn(message, self.run_guard().stderr)
                path.write_text(original)
        for code, message in [
            ("NativeRasterSealer()", "must remain uninstalled"),
            ("AnnotatedRasterCandidate.validated()", "only originate at the original collector"),
            ("AnnotatedRasterSourceIdentity()", "only originate at the original registry"),
        ]:
            copied = base / "UnrelatedRasterCaller.kt"
            copied.write_text("package dev.elu.analytics.internal.replay\n// " + code)
            self.assertIn(message, self.run_guard().stderr)
            copied.unlink()

    def test_exception_intake_keeps_exact_schema_and_original_commit(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal"
        cases = [
            ("runtime/AndroidSQLiteRuntimeDatabase.kt", "else -> runtimeNormalizedDatabaseVersion(version)", "else -> version"),
            ("runtime/RuntimeDiagnosticsState.kt", "in 49L..54L -> RUNTIME_EXCEPTION_SCHEMA_OFFSET", "in 49L..60L -> RUNTIME_EXCEPTION_SCHEMA_OFFSET"),
            ("runtime/AndroidSQLiteRuntimeDatabase.kt", "validateTableSql(sqlite, EXCEPTIONS_TABLE, CREATE_EXCEPTIONS)", "Unit"),
            ("runtime/RuntimeQueueOwner.kt", "consumedDigest = imported.report.digest()", "consumedDigest = null"),
            ("runtime/RuntimeQueueOwner.kt", "exceptionIntake?.let { barriers += it.close() }", "Unit"),
            ("runtime/RuntimeQueueOwner.kt", "exceptionIntake?.joinClosedWriter()", "Unit"),
            ("diagnostics/NativeExceptionIntake.kt", "writer.join()", "Unit"),
            ("diagnostics/NativeExceptionIntake.kt", "!original.sourceIsCurrent()", "false"),
            ("runtime/RuntimeQueueOwner.kt", "originalSource?.isCurrent() == true", "true"),
            ("diagnostics/NativeExceptionIntake.kt", "spool.publish(work.report) { current(work.arm) }", "spool.publish(work.report) { true }"),
            ("diagnostics/NativeExceptionIntake.kt", "if (!current(original) && pending.compareAndSet(work, null))", "if (false)"),
            ("diagnostics/AndroidExceptionSpool.kt", "if (!mayPublish()) return false", "Unit"),
        ]
        self.assertEqual(self.run_guard().returncode, 0)
        for relative, before, after in cases:
            path = base / relative; original = path.read_text(); self.assertIn(before, original)
            path.write_text(original.replace(before, after))
            self.assertIn("exception intake lost exact schema", self.run_guard().stderr)
            path.write_text(original)

    def test_exception_raw_policy_cannot_bypass_production_selection(self) -> None:
        path = self.root / BOUNDARY.STACK
        path.write_text(path.read_text() + "\nfun escaped() = queue.prepareExceptionIntake()\n")
        self.assertIn("exception intake raw policy entry escaped its original queue", self.run_guard().stderr)

    def test_automatic_exceptions_require_original_closed_grant_and_bounded_observation(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal"
        cases = [
            ("runtime/RuntimeQueueOwner.kt", "parsed.captureExceptions?.allowsUncaughtReports == true", "true"),
            ("runtime/RuntimeQueueOwner.kt", "originalIntent() && originalSource.isCurrent() && originalIntent()", "true"),
            ("runtime/StandaloneRuntime.kt", "diagnosticsOptions.enabled && diagnosticsOptions.crashReports", "true"),
            ("runtime/StandaloneRuntime.kt", "!exceptionSuspended.get()", "true"),
            ("runtime/StandaloneRuntime.kt", "exceptionAdmission.get()?.snapshotForPublication()", "exceptionAdmission.get()"),
            ("runtime/StandaloneRuntime.kt", "exceptionHandler = handler\n            if (!handler.install())", "if (!handler.install())"),
            ("runtime/StandaloneRuntime.kt", "exceptionHandler?.close() != false", "true"),
            ("diagnostics/NativeExceptionIntake.kt", "original.completion.get(100, TimeUnit.MILLISECONDS)", "original.completion.get(1000, TimeUnit.MILLISECONDS)"),
            ("diagnostics/AndroidUncaughtExceptionOwner.kt", "if (!originalAdmission.allowsObservation()) return", "Unit"),
            ("config/V1ConfigJson.kt", "return V1CaptureExceptions(rules.length() == 0)", "return V1CaptureExceptions(true)"),
        ]
        self.assertEqual(self.run_guard().returncode, 0)
        for relative, before, after in cases:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text(); self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("automatic exception activation lost", self.run_guard().stderr)
                path.write_text(original)
        options = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/EluDiagnosticsOptions.kt"
        options.write_text(options.read_text().replace("private var reportUncaught = false", "private var reportUncaught = true"))
        self.assertIn("disabled by default", self.run_guard().stderr)

    def test_native_touch_observer_has_only_exact_projection_access(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/AndroidReplayTouchObserver.kt"
        original = path.read_text()
        self.assertEqual(self.run_guard().returncode, 0)
        for call in ["AndroidViewReplayCollector()", "collector.collect(root)"]:
            path.write_text(original + "\nfun escaped() = " + call + "\n")
            self.assertIn("may only inspect the original collector projection", self.run_guard().stderr)
        for name in ["RuntimeQueueOwner", "RuntimeQueueDatabase", "NativeReplayAuthority", "NativeReplayLifecycle", "NativeReplayCaptureOwner"]:
            path.write_text(original + "\nval escaped: " + name + "? = null\n")
            self.assertIn("native touch observer cannot enter authority queue or lifecycle", self.run_guard().stderr)
        for call in ["NativeReplaySelection.issue()", "NativeReplayPermit.issue()", "beginNativeReplayAuthority()", "appendNativeReplay()", "consumeOriginalRoot()"]:
            path.write_text(original + "\nfun escaped() = " + call + "\n")
            self.assertNotEqual(self.run_guard().returncode, 0, call)
        path.write_text(original)
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nval escaped: AndroidReplayTouchObserver? = null\n")
        self.assertIn("native touch observer installation escaped original capture owner", self.run_guard().stderr)

    def test_native_touch_capture_keeps_original_commit_and_cleanup(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        for name, token in [
            ("NativeReplayCaptureOwner.kt", "checkNotNull(enrollment).retainOriginalTouch(checkNotNull(physicalUse), originalTouch)"),
            ("NativeReplayCaptureOwner.kt", "row.projection === acceptedProjection"),
            ("NativeReplayCaptureOwner.kt", "if (interactions != null && initialCommitted && !touchArmed)"),
            ("NativeReplayCaptureOwner.kt", "selection.closeOriginalTouchObserver(originalTouch).awaitExact()"),
            ("NativeReplayAccounting.kt", 'check(originalTouch == null) { "Original UI cleanup is unresolved" }'),
            ("AndroidReplayTouchObserver.kt", "isCurrent = { false }"),
            ("AndroidReplayTouchObserver.kt", "it.kind.text.value != NativeWireframeV2Encoder.MASK"),
        ]:
            path = base / name; original = path.read_text(); self.assertIn(token, original)
            path.write_text(original.replace(token, "Unit"))
            self.assertIn("native touch capture", self.run_guard().stderr)
            path.write_text(original)
        stack = self.root / BOUNDARY.STACK; original = stack.read_text()
        for call in ["consumeOriginalWindow()", "closeOriginalTouchObserver()", "retainOriginalTouch()", "originalTouchSettled()"]:
            stack.write_text(original + "\nfun escaped() = " + call + "\n")
            self.assertIn("native capture call escaped its exact seam", self.run_guard().stderr)
            stack.write_text(original)

    def test_native_touch_overflow_veto_requires_actual_and_encoded_point(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/AndroidViewReplayCollector.kt"
        original = path.read_text()
        for token in ["clip.intersect(exactRoot).wire(density)", "contains(checkNotNull(touchPaintClip), x, y)",
                      "contains(touchPaintClip, floor(x), floor(y))",
                      "val lawfulLeaf = !localBlocked && !localMasked && maskingProfile.readsText",
                      "kind.text.value != NativeWireframeV2Encoder.MASK",
                      "encoded.geometry == NativeGeometryKind.VISIBLE_CLIP && encoded.kind === kind",
                      "if (!lawfulLeaf && (contains(checkNotNull(touchPaintClip), x, y)"]:
            self.assertIn(token, original)
            path.write_text(original.replace(token, "false"))
            self.assertIn("private paint clip veto", self.run_guard().stderr)
            path.write_text(original)

    def test_native_continuity_rejects_missing_original_guards(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        cases = [
            ("NativeReplayComposition.kt", "current(original.intent) && recordingEnabled() && original.privacy() && original.prepared.isCurrent()"),
            ("NativeReplayComposition.kt", "if (capture === opened) retireFresh()"),
            ("NativeReplayComposition.kt", "if (!rootObservationCurrent(original)) { cancelRootObservation(); return }"),
            ("NativeReplayLifecycle.kt", "return actual === selectedRoot && allowed()"),
            ("NativeReplayLifecycle.kt", "activity != null && allowed() && current(activity, original.second) && allowed()"),
            ("NativeReplayCaptureOwner.kt", "originalPermit?.started?.guard?.isCurrent() == true && privacyCurrent() && fence.mayCollect()"),
            ("NativeReplayCaptureOwner.kt", "if (viewport != null && viewport != captured.frame.viewport) throw NativeReplayRootBoundary()"),
        ]
        for name, token in cases:
            with self.subTest(name=name, token=token):
                path = base / name; original = path.read_text()
                self.assertIn(token, original)
                path.write_text(original.replace(token, "true"))
                self.assertIn("native continuity lost original", self.run_guard().stderr)
                path.write_text(original)

    def test_native_v2_buffer_borrows_only_exact_capacity_constants(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayV2Buffer.kt"
        original = path.read_text()
        self.assertEqual(self.run_guard().returncode, 0)
        for reference in ["NativeReplayFrameBuffer(0)", "NativeReplayFrameBuffer::class", "NativeReplayFrameBuffer.UNKNOWN_LIMIT"]:
            with self.subTest(reference=reference):
                path.write_text(original + "\nfun escaped() = " + reference + "\n")
                self.assertIn("native v2 buffer may borrow only exact existing capacity constants", self.run_guard().stderr)
        path.write_text(original)

    def test_native_v2_buffer_cannot_enter_original_authority_queue_or_lifecycle(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayV2Buffer.kt"
        original = path.read_text()
        for reference in ["RuntimeQueueOwner", "RuntimeQueueDatabase", "NativeReplayAuthority", "NativeReplayLifecycle", "android.view.View"]:
            with self.subTest(reference=reference):
                path.write_text(original + "\nval escaped: " + reference + "? = null\n")
                self.assertIn("native v2 buffer must not enter authority queue lifecycle or platform APIs", self.run_guard().stderr)
        path.write_text(original)
        for call in ["beginNativeReplayAuthority", "appendNativeReplay", "consumeOriginalRoot"]:
            with self.subTest(call=call):
                path.write_text(original + "\nfun escaped() = queue." + call + "()\n")
                self.assertNotEqual(self.run_guard().returncode, 0)
        path.write_text(original)

    def test_native_root_observer_cannot_renew_or_poll_durable_state(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayComposition.kt"
        original = path.read_text()
        for added in ["queue.observeNativeReplayProjection()", "queue.prepareNativeReplayProjection(input, privacy)",
                      "queue.snapshot()", "authority.prepare(selection)", "observeMissingRoot(key, intent, acceptance)"]:
            with self.subTest(added=added):
                path.write_text(original.replace("private fun observeRoot(original: RootObservation) {",
                    "private fun observeRoot(original: RootObservation) {\n" + added, 1))
                self.assertIn("must not renew authority or poll SQLite", self.run_guard().stderr)
        path.write_text(original)

    def test_native_observation_allowance_does_not_grant_start_or_escape_composition(self) -> None:
        composition = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayComposition.kt"
        for path, methods in [(composition, ["beginNativeReplayAuthority", "flushNativeReplayClockDenial"]),
                              (self.root / BOUNDARY.STACK, ["observeNativeReplayProjection", "prepareNativeReplayProjection"])]:
            original = path.read_text()
            for method in methods:
                with self.subTest(path=path, method=method):
                    path.write_text(original + "\nfun escaped() = queue." + method + "()\n")
                    self.assertIn("native projection calls escaped authority/queue", self.run_guard().stderr)
            path.write_text(original)

    def test_replay_controls_reject_status_shortcut_unguarded_tail_and_minimum_bypass(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        changes = [
            ("internal/facade/AndroidStandaloneStack.kt", "recordingAllowed = facade::nativeReplayRecordingAllowed", "recordingAllowed = { true }"),
            ("internal/replay/NativeReplayComposition.kt", "original?.recordingStarted() == true", "true"),
            ("internal/replay/NativeReplayComposition.kt", "restrictionGeneration === originalRestriction", "true"),
            ("internal/replay/NativeReplayCaptureOwner.kt", "fence.gracefulStopRequested() && !discardTail", "fence.gracefulStopRequested()"),
            ("internal/replay/NativeReplayCaptureOwner.kt", "privacyCurrent() && fence.isCurrent()", "fence.isCurrent()"),
            ("internal/replay/NativeReplayFrameBuffer.kt", "frames.isEmpty() || (!firstChunkCommitted && !ready)", "frames.isEmpty()"),
            ("internal/replay/NativeReplayLifecycle.kt", "locallyStopped() && authorized() && locallyStopped()", "locallyStopped()"),
        ]
        for relative, before, after in changes:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text(); self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode); self.assertIn("replay controls", result.stderr)
                path.write_text(original)

    def test_person_mode_cannot_be_omitted_from_production_or_raw_reopen(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        changes = [
            ("internal/facade/AndroidStandaloneStack.kt", "personProfiles = personProfiles,", "personProfiles = dev.elu.analytics.EluPersonProfilesMode.ALWAYS,"),
            ("internal/runtime/AndroidRuntimeQueue.kt", "personProfiles = personProfiles,", "personProfiles = null,"),
            ("internal/runtime/RuntimeQueueOwner.kt", 'if (personProfiles == null) corrupt("Person metadata requires a selected profile mode")', "Unit"),
            ("internal/runtime/RuntimeQueueOwner.kt", "person = transitionedPerson", "person = before.person"),
            ("internal/runtime/RuntimeQueueOwner.kt", "putAll(checkNotNull(person).stamps(identity, personProfiles))", "putAll(draft.properties)"),
            ("internal/runtime/AndroidSQLiteRuntimeDatabase.kt", 'readPerson(sqlite) ?: corrupt("Missing person state")', "readPerson(sqlite)"),
        ]
        for relative, before, after in changes:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("person selection", self.run_guard().stderr)
                path.write_text(original)

    def test_durable_exposure_cannot_lose_atomicity_scope_or_same_body_refresh(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        changes = [
            ("internal/runtime/RuntimeQueueOwner.kt", "created.after.copy(exposures = nextExposures)", "created.after"),
            ("internal/runtime/RuntimeQueueOwner.kt", "left.exposures == right.exposures", "true"),
            ("internal/runtime/RuntimeQueueOwner.kt", "RuntimeFlagExposureState.initial(committedState) else before.exposures", "before.exposures"),
            ("internal/runtime/RuntimeQueueOwner.kt", "if (!originalContextMatches()) throw PassiveCaptureWithdrawn()", "Unit"),
            ("internal/facade/StandaloneFacade.kt", "metadata.logicalDigest != flagEvaluationDigest", "false"),
            ("internal/facade/AndroidStandaloneStack.kt", "facade.configurationRefreshed(token)", "Unit"),
            ("internal/config/V2ConfigLifecycleDriver.kt", "minOf(5 * MINUTE", "minOf(10 * MINUTE"),
            ("internal/config/V2ConfigLifecycleDriver.kt", "result is V2ConfigSourceResult.Document && retained", "retained"),
        ]
        for relative, before, after in changes:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("durable flag exposure", self.run_guard().stderr)
                path.write_text(original)

    def test_capture_limiter_rejects_lost_selection_debit_stream_and_retry_fences(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        changes = [
            ("internal/facade/AndroidStandaloneStack.kt", "rateLimiting = rateLimiting,", "rateLimiting = dev.elu.analytics.EluRateLimitingOptions(999.0),"),
            ("internal/runtime/RuntimeCaptureRateLimiter.kt", "stored ?: held", "held ?: stored"),
            ("internal/runtime/RuntimeQueueOwner.kt", "attempt.claim(this, command)", "true"),
            ("internal/runtime/RuntimeQueueOwner.kt", "checkExposureLedger = true", "checkExposureLedger = false"),
            ("internal/runtime/RuntimeQueueOwner.kt", "command.flagExposure?.takeIf { checkExposureLedger }?.let { exposure ->", "command.flagExposure?.takeIf { checkExposureLedger }?.let { exposure ->\n if (MAX_RUNTIME_FLAG_EXPOSURES > 0) return RuntimeCaptureRejection.QUEUE_LIMIT"),
            ("internal/runtime/AndroidSQLiteRuntimeDatabase.kt", "readCaptureRate(readOnly).streamId != state.stream.streamId", "false"),
            ("internal/facade/StandaloneFacade.kt", "val second = send(attempt).await()", "val second = send(RuntimeCaptureRateAttempt()).await()"),
        ]
        for relative, before, after in changes:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("capture limiter", self.run_guard().stderr)
                path.write_text(original)

    def test_clean_internal_transport_boundary_passes(self) -> None:
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_local_endpoint_binding_rejects_lost_transport_and_namespace_policy(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        for relative, before, after in [
            ("EluConfigHostPolicy.kt", "selfHostedOrigin(it) ?: return null", "selfHostedOrigin(it)"),
            ("internal/config/V2ConfigSource.kt", "boundEndpoint = endpoint", "boundEndpoint = null"),
            ("internal/config/V2ConfigTransport.kt", "endpoint == boundEndpoint", "true"),
            ("internal/config/V1ConfigManager.kt", "endpointPolicy.matchesRole(uri, role, schemaVersion)", "true"),
            ("internal/runtime/AndroidRuntimeQueue.kt", "constructorSiteKey, endpointPolicy).canonicalFile", "constructorSiteKey).canonicalFile"),
            ("internal/runtime/StandaloneRuntime.kt", "endpointPolicy = owner.endpointPolicy", "endpointPolicy = LocalEndpointPolicy.CLOUD"),
            ("internal/replay/NativeReplayComposition.kt", "NativeReplayHttpRouter(queue.endpointPolicy)", "NativeReplayHttpRouter(LocalEndpointPolicy.CLOUD)"),
            ("internal/flags/HttpURLConnectionFlagTransport.kt", "requireApprovedEndpoint(endpoint, endpointPolicy)", "requireApprovedEndpoint(endpoint)"),
        ]:
            with self.subTest(relative=relative):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("local endpoint", self.run_guard().stderr)
                path.write_text(original)

    def test_startup_observer_rejects_privacy_lifecycle_and_durable_boundary_bypasses(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        for relative, before, after in [
            ("EluDiagnosticsOptions.kt", "val launchTimings: Boolean = false", "val launchTimings: Boolean = true"),
            ("internal/facade/AndroidStandaloneStack.kt", "android.os.Build.VERSION.SDK_INT >= 35", "android.os.Build.VERSION.SDK_INT >= 30"),
            ("internal/diagnostics/AndroidStartupAccess.kt", "val times = record.startupTimestamps", "val times = record.startupTimestamps; record.intent"),
            ("internal/diagnostics/NativeStartupObservation.kt", "matching.size != 1", "matching.isEmpty()"),
            ("internal/diagnostics/NativeStartupObservation.kt", "record.state != STATE_STARTED || record.firstFrameUptimeNanos != null", "false"),
            ("internal/diagnostics/NativeStartupMonitor.kt", "context() != admitted", "false"),
            ("internal/runtime/RuntimeQueueOwner.kt", "!nativeSettlementUncertain && !diagnosticsClosurePending", "!nativeSettlementUncertain"),
            ("internal/runtime/RuntimeQueueOwner.kt", "!diagnosticsClosurePending && diagnosticsConfiguration.enabled && diagnosticsConfiguration.launchTimings", "true"),
        ]:
            with self.subTest(relative=relative, before=before):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("startup observation", self.run_guard().stderr)
                path.write_text(original)

    def test_frame_observer_rejects_default_on_and_unsupported_timestamp_floor(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics"
        for relative, before, after in [
            ("EluFrameMetricsOptions.kt", "val enabled: Boolean = false", "val enabled: Boolean = true"),
            ("internal/performance/AndroidPerformanceMonitor.kt", "Build.VERSION.SDK_INT >= 26", "Build.VERSION.SDK_INT >= 24"),
            ("internal/performance/AndroidFrameMetricsAccess.kt", "Build.VERSION.SDK_INT >= 31", "true"),
        ]:
            with self.subTest(relative=relative):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("frame observation", self.run_guard().stderr)
                path.write_text(original)

    def test_frame_observer_rejects_content_read_and_lost_listener_removal(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/performance/AndroidFrameMetricsAccess.kt"
        original = path.read_text()
        for before, after in [
            ("val originalWindow = originalActivity.window", "val originalWindow = originalActivity.window; originalWindow.decorView"),
            ("originalWindow.removeOnFrameMetricsAvailableListener(listener)", "Unit"),
            ("metrics.getMetric(FrameMetrics.TOTAL_DURATION)", "metrics.getMetric(FrameMetrics.DRAW_DURATION)"),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("frame observation", self.run_guard().stderr)
        path.write_text(original)

    def test_frame_observer_rejects_context_or_stale_timestamp_bypass(self) -> None:
        base = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/performance"
        for relative, before in [
            ("NativeFrameMetricsOwner.kt", "lifecycle.isCurrent(original.selection) && context() == original.context"),
            ("NativeFrameMetricsOwner.kt", "original.watcher?.close()"),
            ("NativeFrameMetrics.kt", "frameStartedAtNanos < registeredAtNanos"),
            ("NativeFrameMetrics.kt", "count >= MAXIMUM_FRAMES"),
        ]:
            with self.subTest(before=before):
                path = base / relative; original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, "false"))
                self.assertIn("frame observation", self.run_guard().stderr)
                path.write_text(original)

    def test_network_observer_cannot_expand_content_or_repeat_the_request(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/network/NativeNetworkInterceptor.kt"
        original = path.read_text()
        changes = [
            ('"\\$network_failed" to failed,', '"\\$network_failed" to failed, "\\$network_url" to "private",'),
            ("val request = chain.request()", "val request = chain.request(); request.body"),
            ("val response = chain.proceed(request)", "chain.proceed(request); val response = chain.proceed(request)"),
            ('host == "elu.dev" || host.endsWith(".elu.dev")', "false"),
        ]
        for before, after in changes:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("network observation", self.run_guard().stderr)
        path.write_text(original)

    def test_network_observer_cannot_drop_admission_boundaries(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/facade/StandaloneFacade.kt"
        original = path.read_text()
        for before, after in [("networkObservations >= 200", "networkObservations >= 2000"),
                              ("if (networkContext() != original) return@submit", ""),
                              ("value != networkConfigHost", "true"),
                              ("session?.id, session?.startedAt, flagIntentRevision, consentIntentRevision, nativeIntentEpoch",
                               "session?.id, session?.startedAt, 0, 0, nativeIntentEpoch")]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                self.assertIn("network observation", self.run_guard().stderr)
        path.write_text(original)

    def test_network_observer_requires_transaction_current_session_and_final_rollback(self) -> None:
        path = self.root / BOUNDARY.OWNER
        original = path.read_text()
        for before in ["before.state.identity.session?.startedAt == expected.sessionStartedAt && expected.isCurrent()",
                       "source.expectation != null || source.networkExpectation?.sessionId != null",
                       "if (!originalContextMatches()) throw PassiveCaptureWithdrawn()"]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, "true"))
                self.assertIn("network durable enqueue", self.run_guard().stderr)
        path.write_text(original)

    def test_standalone_must_not_supply_replay_proof(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nval readbackProvenReplayTransports = setOf(pair)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not supply replay proof", result.stderr)

    def test_pending_consent_cannot_follow_runtime_startup(self) -> None:
        cases = [
            ("EluConsentHandoff.kt", "pending?.apply(target)", ""),
            ("StandaloneFacade.kt", "applyConsentOnLane(intent)\n                    // A failed", "// A failed"),
        ]
        for filename, before, after in cases:
            with self.subTest(filename=filename):
                path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/facade" / filename
                original = path.read_text()
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("consent", result.stderr)
                path.write_text(original)

    def test_owned_native_capability_selection_cannot_add_a_codec_or_generation(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for before, after in [
            ('Collections.unmodifiableSet(setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2))',
             'setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2)'),
            ('setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2)', 'NativeReplayProtocol.values().toSet()'),
            ('setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2)',
             'setOf(NativeReplayProtocol.V1, NativeReplayProtocol.V2, NativeReplayProtocol.FUTURE)'),
            ('installedNativeReplayProtocols.map { it.transport }.toSet()',
             'setOf(V1ReplayTransport("foreign-codec", V1ReplayCompression.GZIP))'),
            ('installedNativeReplayProtocols.map { it.generation }.toSet()', 'setOf("protocol-generation-v1", "future")'),
            ('readbackProvenReplayTransports = nativeReplayTransports', 'readbackProvenReplayTransports = emptySet()'),
            ('supportedReplayProtocolGenerations = nativeReplayGenerations', 'supportedReplayProtocolGenerations = emptySet()'),
            ('transports = nativeReplayTransports', 'transports = emptySet()'),
            ('generations = nativeReplayGenerations', 'generations = emptySet()'),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("exact owned native capability selection", result.stderr)
        path.write_text(original)

    def test_retired_preview_reader_cannot_return(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/compat/PreviewImport.kt"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("internal class PreviewImport")
        self.assertIn("retired preview import readers", self.run_guard().stderr)

    def test_clean_setup_cannot_reopen_preview_storage(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/AndroidRuntimeQueue.kt"
        original = path.read_text()
        for injected in ["applicationContext.filesDir", "applicationContext.cacheDir", "AndroidCoreStateStore.forProduction(file)",
                         "legacy.readBytes()", "legacy.readText()", "legacy.inputStream()"]:
            with self.subTest(injected=injected):
                path.write_text(original + "\n" + injected)
                self.assertIn("clean setup must not read or import", self.run_guard().stderr)
        path.write_text(original)

    def test_native_capability_forwarding_cannot_supply_masking_admission(self) -> None:
        for relative in [BOUNDARY.STACK, BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/AndroidRuntimeQueue.kt"]:
            with self.subTest(relative=relative):
                path = self.root / relative
                original = path.read_text()
                path.write_text(original + "\nval replayMaskingAdmission = allowEverything\n")
                self.assertIn("must not supply masking admission", self.run_guard().stderr)
                path.write_text(original)

    def test_native_accounting_must_remain_unconstructed(self) -> None:
        stack = self.root / BOUNDARY.STACK
        original = stack.read_text()
        for name in ["ensureNativeReplayAccounting", "observeNativeReplaySession", "beginNativeReplayAccounting", "stopNativeReplayAccounting"]:
            with self.subTest(name=name):
                stack.write_text(original + f"\nfun activateNative() = owner.{name}()\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("native replay accounting must remain unconstructed", result.stderr)

    def test_native_schema_must_not_escape_exact_storage_files(self) -> None:
        unexpected = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/UnexpectedNative.kt"
        unexpected.write_text("fun activateNative() = db.ensureNativeReplaySchema(row)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("native replay schema escaped its internal owner", result.stderr)

    def test_native_authority_and_selection_cannot_escape_to_facade(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for added in ["val authority = NativeReplayAuthority(queue)",
                      "val selection = NativeReplaySelection.issue(access, activity, root, facts, current)"]:
            with self.subTest(added=added):
                path.write_text(original + "\n" + added + "\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("native authority reference escaped", result.stderr)

    def test_native_buffer_and_root_consumer_remain_unconstructed(self) -> None:
        replay = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        for path in [self.root / BOUNDARY.STACK, replay / "UnexpectedCapture.kt"]:
            original = path.read_text() if path.exists() else ""
            for added, message in [
                ("val frames = NativeReplayFrameBuffer(0)", "native authority reference escaped"),
                ("fun collect() = selection.consumeOriginalRoot(current, collector)", "native capture call escaped"),
            ]:
                with self.subTest(path=path, added=added):
                    path.write_text(original + "\n" + added + "\n")
                    result = self.run_guard()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)
            if original:
                path.write_text(original)
            else:
                path.unlink()

    def test_native_buffer_declaration_stays_internal(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayFrameBuffer.kt"
        path.write_text(path.read_text().replace("internal class NativeReplayFrameBuffer(", "class NativeReplayFrameBuffer("))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("native frame buffer must have one internal declaration", result.stderr)

    def test_native_root_consumer_cannot_export_raw_or_generic_values(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        original = path.read_text()
        for replacement in ["Any", "android.view.View", "T"]:
            with self.subTest(replacement=replacement):
                changed = original.replace("NativeReplayCollectionAttempt?", replacement + "?")
                self.assertNotEqual(original, changed)
                path.write_text(changed)
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("return only a detached masked snapshot", result.stderr)

    def test_native_physical_owner_platform_and_clock_do_not_escape(self) -> None:
        replay = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        for path in [self.root / BOUNDARY.STACK, replay / "UnexpectedLoop.kt"]:
            original = path.read_text() if path.exists() else ""
            for added, message in [
                ("val loop = NativeReplayCaptureOwner.start(queue, authority, prepared, versions)", "native authority reference escaped"),
                ("val platform: NativeReplayCapturePlatform = AndroidNativeReplayCapturePlatform", "native authority reference escaped"),
                ("val clock = queue.nativeReplayCaptureClock()", "native capture call escaped"),
                ("val same = queue.nativeReplayCaptureMatches(use)", "native capture call escaped"),
            ]:
                with self.subTest(path=path, added=added):
                    path.write_text(original + "\n" + added + "\n")
                    result = self.run_guard()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)
            if original: path.write_text(original)
            else: path.unlink()

    def test_native_loop_factory_and_old_api_denial_remain_closed(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayCaptureOwner.kt"
        original = path.read_text()
        for before, after, message in [
            ("NativeReplayCaptureOwner private constructor(", "NativeReplayCaptureOwner constructor(", "retain one private factory"),
            ("platform.apiLevel < 29", "platform.apiLevel < 23", "deny API levels below 29"),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn(message, result.stderr)

    def test_native_current_root_calls_cannot_escape_lifecycle(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for name in ("currentRoot", "selectCurrent", "observeChanges"):
            with self.subTest(name=name):
                path.write_text(original + f"\nfun bypass() = lifecycle.{name}()\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("discovery/subscription must remain", result.stderr)
        path.write_text(original)

    def test_native_unpublished_disposal_reservation_and_success_only_release(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        original = path.read_text()
        for before, after in [
            ("unsettledSelections.add(selection)", "Unit"),
            ("if (cleanupError == null) synchronized(monitor)", "if (true) synchronized(monitor)"),
        ]:
            with self.subTest(before=before):
                path.write_text(original.replace(before, after, 1))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("unpublished disposal must reserve", result.stderr)

    def test_native_discovery_cannot_return_a_raw_root_or_drop_original_window(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        original = path.read_text()
        for before, after in [
            ("selectCurrent(reusing: NativeReplaySelection? = null): SdkFuture<NativeReplaySelection?>",
             "selectCurrent(reusing: NativeReplaySelection? = null): SdkFuture<Any?>"),
            ("read { selected.window } !== window", "false"),
            ("window.peekDecorView()", "window.decorView"),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after, 1))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("current-root discovery", result.stderr)
        path.write_text(original)

    def test_discovered_root_identity_cannot_be_dropped_before_physical_collection(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        original = path.read_text()
        before = "return actual === selectedRoot && allowed()"
        self.assertIn(before, original)
        path.write_text(original.replace(before, "true", 1))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("discovered-root ownership", result.stderr)

    def test_native_outline_observation_cannot_escape_to_public_composition(self) -> None:
        path = self.root / BOUNDARY.STACK
        path.write_text(path.read_text() + "\nfun bypass() = observeNativeReplayOutline(view) {}\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("outline observation must remain", result.stderr)

    def test_readable_text_cannot_wait_for_appcompat_future_or_publish_changed_layout(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/AndroidViewReplayCollector.kt"
        original = path.read_text()
        for before, after in [
            ("{ layout.text }", "{ view.text }"),
            ("observed.view.layout } !== observed.layout", "observed.view.layout } == null"),
            ("observed.layout.text } !== observed.text", "observed.layout.text } == null"),
            ("textKind(observed.view, false, false) != observed.kind", "false"),
        ]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after, 1))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("native readable text", result.stderr)
        path.write_text(original)

    def test_native_outline_observation_cannot_drop_original_withdrawal_checks(self) -> None:
        life = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        collector = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/AndroidViewReplayCollector.kt"
        for path, before, after in [
            (life, 'observeNativeReplayOutline(decor, decor) { check(current())', 'observeNativeReplayOutline(decor, decor) { check(true)'),
            (collector, "observeNativeReplayOutlineProfiled(view, originalWindowDecor, ::check, profile)", "observeNativeReplayOutline(view, originalWindowDecor) {}"),
            (collector, "profile?.returned(); profile?.position = 3\n    check()", "profile?.returned(); profile?.position = 3"),
        ]:
            original = path.read_text()
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("withdrawal checks", result.stderr)
                path.write_text(original)

    def test_layout_uncertainty_cannot_adopt_foreign_window_or_subclass(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/AndroidViewReplayCollector.kt"
        original = path.read_text()
        for before, after in [("view === originalWindowDecor", "true"),
                              ("background.javaClass === ColorDrawable::class.java", "background is ColorDrawable"),
                              ("guardedNativeViewRead(profile, readGuard) { root.rootView } !== originalWindowDecor", "false")]:
            with self.subTest(before=before):
                self.assertIn(before, original)
                path.write_text(original.replace(before, after))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("layout uncertainty must bind", result.stderr)
        path.write_text(original)

    def test_native_authority_cannot_construct_capture_or_sealer(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayAuthority.kt"
        original = path.read_text()
        for name in ["AndroidViewReplayCollector", "NativeReplaySealer"]:
            with self.subTest(name=name):
                path.write_text(original + f"\nval recorder = {name}(binding)\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("must remain unconstructed", result.stderr)

    def test_native_capture_capabilities_keep_private_unique_factories(self) -> None:
        replay = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        for symbol, filename in [("NativeReplayCapturePhysicalUse", "NativeReplayAccounting.kt"),
                                 ("NativeReplayCaptureEnrollment", "NativeReplayAccounting.kt"),
                                 ("NativeReplayCaptureAdmission", "NativeReplayAuthority.kt")]:
            path = replay / filename
            original = path.read_text()
            private = symbol + " private constructor("
            self.assertIn(private, original)
            for changed, message in [
                (original.replace(private, symbol + " internal constructor("), "retain its private constructor"),
                (original + f"\nfun anotherFactory() = {symbol}(unexpected)\n", "exactly one private factory"),
            ]:
                with self.subTest(symbol=symbol, message=message):
                    path.write_text(changed)
                    result = self.run_guard()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn(message, result.stderr)
            path.write_text(original)

    def test_native_capture_issuers_cannot_escape_the_exact_file(self) -> None:
        replay = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        for symbol, filename in [("NativeReplayCapturePhysicalUse", "NativeReplayAuthority.kt"),
                                 ("NativeReplayCaptureEnrollment", "NativeReplayAccounting.kt"),
                                 ("NativeReplayCaptureAdmission", "NativeReplayAuthority.kt")]:
            path = replay / filename
            original = path.read_text()
            for expression in [f"{symbol}.issue(unexpected)", f"{symbol}.Companion.issue(unexpected)",
                               f"{symbol}::issue"]:
                with self.subTest(expression=expression):
                    path.write_text(original + f"\nval unexpected = {expression}\n")
                    result = self.run_guard()
                    self.assertNotEqual(0, result.returncode)
                    self.assertIn("capture issuer escaped", result.stderr)
            path.write_text(original)

    def test_native_capture_issuer_cannot_be_duplicated_inside_its_file(self) -> None:
        path = self.root / BOUNDARY.OWNER
        original = path.read_text()
        for symbol in ["NativeReplayCaptureEnrollment", "NativeReplayCaptureAdmission"]:
            with self.subTest(symbol=symbol):
                path.write_text(original + f"\nval extra = {symbol}.issue(unexpected)\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("exactly one original issuer", result.stderr)

    def test_native_capture_capabilities_cannot_be_constructed_by_facade(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for symbol in ["NativeReplayCapturePhysicalUse", "NativeReplayCaptureEnrollment", "NativeReplayCaptureAdmission"]:
            with self.subTest(symbol=symbol):
                path.write_text(original + f"\nval capture = {symbol}(unexpected)\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("capture constructor escaped", result.stderr)

    def test_native_capture_calls_cannot_escape_to_facade(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for method in ["enrollNativeReplayCapture", "finishNativeReplayCapture", "appendNativeReplay",
                       "stopNativeReplayCaptureAccounting", "makeNativeReplayCaptureAdmission"]:
            with self.subTest(method=method):
                path.write_text(original + f"\nval unexpected = queue.{method}(value)\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("capture call escaped its exact seam", result.stderr)

    def test_native_generic_append_cannot_bypass_original_admission(self) -> None:
        path = self.root / BOUNDARY.OWNER
        original = path.read_text()
        refusal = 'if (NativeReplayProtocol.isNativeCodec(request.transport.codec)) return@submit ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)'
        self.assertIn(refusal, original)
        path.write_text(original.replace(refusal, ""))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("generic replay append must reject native codec", result.stderr)

    def test_native_tuples_cannot_cross_generation_or_bypass_independent_proof(self) -> None:
        replay = BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay"
        manager = BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/config/V1ConfigManager.kt"
        cases = [
            (replay / "NativeReplayProtocol.kt", '"protocol-generation-v2"', '"protocol-generation-v1"'),
            (replay / "NativeReplayProtocol.kt", 'it.transport == transport && it.generation == generation', 'it.transport == transport'),
            (replay / "NativeReplayProtocol.kt", 'values().any { it.codec == codec }', 'codec == "elu-native-wireframe-v1"'),
            (BOUNDARY.OWNER, 'replayTransportGenerations = NativeReplayProtocol.generationBindings(),', ''),
            (BOUNDARY.OWNER, 'NativeReplayProtocol.match(request.transport, request.captureProtocolGeneration) != null', 'true'),
            (BOUNDARY.OWNER, 'NativeReplayProtocol.match(projection.transport, projection.protocolGeneration) == null', 'false'),
            (BOUNDARY.OWNER, 'request.transport == admission.permit.prepared.projection.privacy.transport', 'true'),
            (manager, '!generationMatches(selectedPair, replayCapabilities.replayProtocolGeneration)', 'false'),
            (manager, '!generationMatches(pair, generation)', 'false'),
            (manager, 'pair !in readbackProvenReplayTransports || !generationMatches(pair, generation)', '!generationMatches(pair, generation)'),
            (replay / "NativeReplayAuthority.kt", 'it in transports && NativeReplayProtocol.match(it, generation) != null', 'it in transports'),
        ]
        for relative, old, new in cases:
            with self.subTest(relative=relative, old=old):
                path = self.root / relative; original = path.read_text()
                self.assertIn(old, original)
                path.write_text(original.replace(old, new))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("native", result.stderr)
                path.write_text(original)

    def test_native_physical_use_cannot_drop_one_shot_or_original_identity(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayAccounting.kt"
        original = path.read_text()
        for old, new in [("originalUse === use", "true"),
                         ("if (taken || physicalFinished", "if (physicalFinished"),
                         ("taken = true; NativeReplayCapturePhysicalUse.issue(this)", "NativeReplayCapturePhysicalUse.issue(this)")]:
            with self.subTest(old=old):
                self.assertIn(old, original)
                path.write_text(original.replace(old, new))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("one-shot original-use checks", result.stderr)

    def test_native_capability_proof_defaults_stay_empty(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayAuthority.kt"
        original = path.read_text()
        for old, new in [("transports: Set<V1ReplayTransport> = emptySet()", "transports: Set<V1ReplayTransport> = setOf(pair)"),
                         ("generations: Set<String> = emptySet()", 'generations: Set<String> = setOf("remote")')]:
            self.assertIn(old, original)
            path.write_text(original.replace(old, new))
            result = self.run_guard()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("proof must default empty", result.stderr)

    def test_native_selection_does_not_enable_pre29(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayLifecycle.kt"
        original = path.read_text()
        for old in ["facts.apiLevel < 29", "Build.VERSION.SDK_INT < 29"]:
            self.assertIn(old, original)
            path.write_text(original.replace(old, "false"))
            result = self.run_guard()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("must refuse API levels below 29", result.stderr)

    def test_native_pause_cannot_wait_for_queued_owner_work(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/EluLifecycleInitializer.kt"
        original = path.read_text()
        old = "override fun onActivityPrePaused(activity: Activity) = withdrawing(activity)"
        self.assertIn(old, original)
        path.write_text(original.replace(old, "override fun onActivityPrePaused(activity: Activity) = worker.execute { withdrawing(activity) }"))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("withdrawal must be synchronous", result.stderr)

    def test_native_withdrawal_cannot_defer_either_observer(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/EluLifecycleInitializer.kt"
        original = path.read_text()
        for call in ["performanceObserved.withdrawing(activity)", "nativeObserved.withdrawing(activity)"]:
            with self.subTest(call=call):
                self.assertIn(call, original)
                path.write_text(original.replace(call, "worker.execute { " + call + " }"))
                self.assertIn("withdrawal must synchronously retire", self.run_guard().stderr)
        path.write_text(original)

    def test_native_second_application_callback_source_is_rejected(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/EluLifecycleInitializer.kt"
        path.write_text(path.read_text() + "\nfun duplicate(application: Application) = application.registerActivityLifecycleCallbacks(extra)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("sole Application callback source", result.stderr)

    def test_native_projection_cannot_be_started_by_facade(self) -> None:
        path = self.root / BOUNDARY.STACK
        path.write_text(path.read_text() + "\nval projection = queue.beginNativeReplayAuthority(prepared)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("native projection calls escaped", result.stderr)

    def test_replay_activation_must_remain_unconstructed(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nfun activateReplay() = owner.ensurePreparedReplayStorage()\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must remain unconstructed", result.stderr)

    def test_replay_delivery_binding_must_remain_unconstructed(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nfun startReplay() = owner.openReplayDeliveryQueue(policy)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("delivery binding must remain unconstructed", result.stderr)

    def test_replay_delivery_coordinator_must_remain_unconstructed(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nval replay = ReplayDeliveryCoordinator(queue, transport)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("coordinator must remain unconstructed", result.stderr)

    def test_native_sealer_must_remain_unconstructed(self) -> None:
        for relative in [BOUNDARY.STACK, BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/UnexpectedSealer.kt"]:
            with self.subTest(path=relative):
                target = self.root / relative
                original = target.read_text() if target.exists() else None
                target.write_text((original or "") + "\nval sealer = NativeReplaySealer(binding)\n")
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("native replay sealer must remain unconstructed", result.stderr)
                if original is None:
                    target.unlink()
                else:
                    target.write_text(original)

    def test_native_sealer_declaration_must_remain_internal_and_unique(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplaySealer.kt"
        original = path.read_text()
        for changed in [original.replace("internal class NativeReplaySealer", "class NativeReplaySealer"),
                        original + "\nval unexpected = NativeReplaySealer(binding)\n"]:
            path.write_text(changed)
            result = self.run_guard()
            self.assertNotEqual(0, result.returncode)
            self.assertIn("sealer must have one internal declaration", result.stderr)

    def test_replay_http_transport_must_remain_unconstructed(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nval replayHttp = OkHttpReplayTransport(key, endpoint)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("HTTP transport must remain unconstructed", result.stderr)

    def test_replay_http_cookie_and_authentication_isolation_is_required(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/OkHttpReplayTransport.kt"
        original = path.read_text()
        for required in [".cookieJar(CookieJar.NO_COOKIES)", ".authenticator(Authenticator.NONE)",
                         ".proxyAuthenticator(Authenticator.NONE)", ".followRedirects(false)"]:
            with self.subTest(required=required):
                path.write_text(original.replace(required, ""))
                result = self.run_guard()
                self.assertNotEqual(0, result.returncode)
                self.assertIn("HTTP isolation requirement missing", result.stderr)

    def test_replay_storage_must_not_contain_network(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/Unexpected.kt"
        path.write_text("import java.net.HttpURLConnection\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not contain network", result.stderr)

    def test_owner_masking_predicate_must_default_deny(self) -> None:
        path = self.root / BOUNDARY.OWNER
        path.write_text(path.read_text().replace(
            "replayMaskingAdmission: ReplayMaskingAdmission = ReplayMaskingAdmission { _, _ -> false }",
            "replayMaskingAdmission: ReplayMaskingAdmission = ReplayMaskingAdmission { _, _ -> true }"))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("masking admission must default deny", result.stderr)

    def test_replay_append_must_not_escape_owner(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nfun appendReplay() = owner.appendPreparedReplay(request, profile, privacy)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must remain unconstructed", result.stderr)

    def test_supported_generation_registry_must_default_empty(self) -> None:
        path = self.root / BOUNDARY.OWNER
        path.write_text(path.read_text().replace(
            "supportedReplayProtocolGenerations: Set<String> = emptySet()",
            "supportedReplayProtocolGenerations: Set<String> = setOf(\"arbitrary-generation\")"))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("supported replay generations must default empty", result.stderr)

    def test_public_facade_mutation_fails(self) -> None:
        facade = self.root / "elu-analytics/src/main/kotlin/dev/elu/analytics/Elu.kt"
        facade.write_text(facade.read_text(encoding="utf-8") + "\n// accidental public cutover\n", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("pinned feature-flag boundary changed", result.stderr)

    def test_concrete_transport_fails(self) -> None:
        conformer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/WiredTransport.kt"
        conformer.write_text(
            """package dev.elu.analytics.internal.flags

internal class WiredTransport : FlagTransport {
    override fun send(request: FlagTransportRequest) = error("wired")
}
""",
            encoding="utf-8",
        )
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("production FlagTransport reference/conformer is forbidden", result.stderr)

    def test_only_the_named_transport_file_may_contain_network_code(self) -> None:
        unexpected = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/AnotherNetwork.kt"
        unexpected.write_text("package dev.elu.analytics.internal.flags\nimport java.net.HttpURLConnection\n", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("concrete network/HTTP code is forbidden", result.stderr)

    def test_named_transport_cannot_be_referenced_or_constructed_elsewhere(self) -> None:
        consumer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/Composition.kt"
        consumer.write_text(
            "package dev.elu.analytics.internal.flags\nval factory = ::HttpURLConnectionFlagTransport\n",
            encoding="utf-8",
        )
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must remain unconstructed and unreferenced", result.stderr)

    def test_approved_transport_cannot_be_made_public(self) -> None:
        transport = self.root / BOUNDARY.TRANSPORT
        transport.write_text(transport.read_text(encoding="utf-8").replace(
            "internal class HttpURLConnectionFlagTransport(", "public class HttpURLConnectionFlagTransport("
        ), encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must remain an internal class", result.stderr)

    def test_approved_transport_cannot_construct_itself(self) -> None:
        transport = self.root / BOUNDARY.TRANSPORT
        transport.write_text(transport.read_text(encoding="utf-8") +
            "\nval createTransport = ::HttpURLConnectionFlagTransport\n", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("one declaration and no construction", result.stderr)

    def test_approved_file_cannot_smuggle_another_transport(self) -> None:
        transport = self.root / BOUNDARY.TRANSPORT
        transport.write_text(transport.read_text(encoding="utf-8") +
            "\ninternal class Another : FlagTransport { }\n", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("exactly one FlagTransport conformance", result.stderr)

    def test_missing_named_transport_fails(self) -> None:
        (self.root / BOUNDARY.TRANSPORT).unlink()
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("approved internal flag transport source is missing", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_missing_local_endpoint_policy_fails_with_required_file_diagnostic(self) -> None:
        relative = BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/config/LocalEndpointPolicy.kt"
        (self.root / relative).unlink()
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn(f"required file is missing: {relative}", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_removed_provider_wrappers_cannot_return(self) -> None:
        for relative in BOUNDARY.REMOVED_RUNTIME_FILES:
            with self.subTest(relative=relative):
                path = self.root / BOUNDARY.MAIN_KOTLIN / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// accidentally restored wrapper\n")
                self.assertIn("must remain absent", self.run_guard().stderr)
                path.unlink()

    def test_public_setup_cannot_bypass_owned_sink_or_validated_host(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/Elu.kt"
        original = path.read_text()
        for old, new in [("AndroidStandaloneStack.facade(appContext, key, configHost, options.performance, options.diagnostics, options.apiHost, options.personProfiles, options.persistence, options.rateLimiting)", "AndroidStandaloneStack.facade(appContext, key, anotherHost)"),
                         ("consent.install(facade, facade::start)", "facade.start()")]:
            with self.subTest(old=old):
                self.assertIn(old, original); path.write_text(original.replace(old, new))
                self.assertIn("public setup must", self.run_guard().stderr)

    def test_unapproved_dependency_and_private_future_regression_fail(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/UnexpectedProvider.kt"
        for source, diagnostic in [("import java.util.concurrent.CompletableFuture", "API23 runtime must use"),
                                   ("import java.util.concurrent.CompletionStage as HiddenStage", "API23 runtime must use"),
                                   ("import java.util.concurrent.*", "API23 runtime must use")]:
            with self.subTest(source=source):
                path.write_text(source + "\n")
                self.assertIn(diagnostic, self.run_guard().stderr)
        path.unlink()
        build = self.root / "elu-analytics/build.gradle.kts"
        build.write_text(build.read_text() + '\nimplementation("com.example:unapproved-runtime:1.0.0")\n')
        self.assertIn("runtime dependencies must remain", self.run_guard().stderr)

    def test_private_future_cannot_become_public(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/concurrent/SdkFuture.kt"
        original = path.read_text()
        self.assertIn("internal open class SdkFuture", original)
        path.write_text(original.replace("internal open class SdkFuture", "public open class SdkFuture"))
        self.assertIn("primitive must not become public", self.run_guard().stderr)

    def test_api23_floor_and_required_time_desugaring_cannot_be_removed(self) -> None:
        path = self.root / "elu-analytics/build.gradle.kts"
        original = path.read_text()
        for old, new in [("minSdk = 23", "minSdk = 24"),
                         ("isCoreLibraryDesugaringEnabled = true", "isCoreLibraryDesugaringEnabled = false"),
                         ('coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")', "// missing dependency")]:
            with self.subTest(old=old):
                self.assertIn(old, original); path.write_text(original.replace(old, new))
                self.assertIn("must retain API23", self.run_guard().stderr)

    def test_production_cannot_flip_the_frozen_selector(self) -> None:
        consumer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/UnexpectedSelection.kt"
        consumer.write_text("fun change() = EluRuntimeSelector.select(EluRuntimeMode.STANDALONE)", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("production runtime selection change is forbidden", result.stderr)

    def test_configured_router_cannot_be_constructed_outside_stack(self) -> None:
        consumer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/flags/UnexpectedRouter.kt"
        consumer.write_text("val factory = ::V2ConfigBoundFlagTransport", encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("escaped standalone composition", result.stderr)

    def test_missing_configured_router_fails(self) -> None:
        (self.root / BOUNDARY.ROUTER).unlink()
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("required internal configuration-bound transport", result.stderr)
        self.assertNotIn("Traceback", result.stderr)

    def test_lifecycle_initializer_must_not_be_exported(self) -> None:
        manifest = self.root / "elu-analytics/src/main/AndroidManifest.xml"
        manifest.write_text(manifest.read_text().replace('android:exported="false"', 'android:exported="true"'))
        self.assertIn("exact nonexported", self.run_guard().stderr)

    def test_lifecycle_authority_must_use_application_id(self) -> None:
        manifest = self.root / "elu-analytics/src/main/AndroidManifest.xml"
        manifest.write_text(manifest.read_text().replace("${applicationId}.elu-analytics-lifecycle", "global-authority"))
        self.assertIn("application-namespaced provider", self.run_guard().stderr)

    def test_lifecycle_bootstrap_must_not_add_permission(self) -> None:
        manifest = self.root / "elu-analytics/src/main/AndroidManifest.xml"
        manifest.write_text(manifest.read_text().replace("</manifest>", '<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" /></manifest>'))
        self.assertIn("must not add permissions", self.run_guard().stderr)

    def test_lifecycle_bootstrap_must_not_start_analytics(self) -> None:
        initializer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/EluLifecycleInitializer.kt"
        initializer.write_text(initializer.read_text() + "\nval factory = AndroidStandaloneStack\n")
        self.assertIn("must not start collection", self.run_guard().stderr)

    def test_lifecycle_observer_must_not_open_network(self) -> None:
        observer = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/ProcessActivityLifecycle.kt"
        observer.write_text(observer.read_text() + "\nimport java.net.URLConnection\n")
        self.assertIn("must not start collection", self.run_guard().stderr)

    def test_native_composition_remains_unconstructed_outside_exact_assembly(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/UnexpectedComposition.kt"
        path.write_text("val factory = ::NativeReplayComposition\n")
        self.assertIn("native authority reference escaped", self.run_guard().stderr)

    def test_native_composition_cannot_acquire_raw_root_or_issue_physical_permission(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayComposition.kt"
        original = path.read_text()
        for injected, expected in [("access.currentRoot(activity, current)", "native root discovery/subscription"),
                                   ("queue.enrollNativeReplayCapture()", "native capture call escaped"),
                                   ("queue.appendPreparedReplay(request)", "prepared replay storage must remain unconstructed"),
                                   ("val readbackProvenReplayTransports = setOf(pair)", "must not supply replay proof")]:
            with self.subTest(injected=injected):
                path.write_text(original + "\n" + injected + "\n")
                self.assertIn(expected, self.run_guard().stderr)

    def test_native_composition_owned_adapter_allowance_does_not_allow_raw_http(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayComposition.kt"
        path.write_text(path.read_text() + "\nimport okhttp3.OkHttpClient\n")
        self.assertIn("must not contain network or HTTP code", self.run_guard().stderr)

    def test_native_composition_cannot_bypass_intent_or_physical_cleanup(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/NativeReplayComposition.kt"
        original = path.read_text()
        for needle in ["if (capabilities.hasLocalEvidence())", "intakeAllowed() && synchronized(monitor) { !closed && intent === original }",
                       "selected.closeAndWait().awaitExact()", "queue.retainNativeReplayCleanupFailure().awaitExact()",
                       "original.settlement.whenComplete", "!closed.get() && !canceled.get() && authorizeIo()",
                       "runEvaluation(result, original, useForce, acceptance)", "acceptance() && current(original) && acceptance()", "if (includeDelivery) deliveryEpoch.set(null)",
                       "original != null && deliveryEpoch.get() === original && authorizeIo()",
                       "else if (acceptance() && it.current(original) && acceptance())"]:
            with self.subTest(needle=needle):
                self.assertIn(needle, original)
                path.write_text(original.replace(needle, "removedFence"))
                self.assertIn("native composition lost", self.run_guard().stderr)

    def test_native_cleanup_quarantine_is_one_way_and_internal(self) -> None:
        path = self.root / BOUNDARY.OWNER
        original = path.read_text()
        path.write_text(original.replace("nativeSettlementUncertain = true\n    }\n\n    fun closeAsync", "nativeSettlementUncertain = false\n    }\n\n    fun closeAsync"))
        self.assertNotEqual(original, path.read_text())
        self.assertIn("native cleanup quarantine must invalidate", self.run_guard().stderr)
        path.write_text(original)
        path = self.root / BOUNDARY.STACK
        path.write_text(path.read_text() + "\nfun poison() = owner.retainNativeReplayCleanupFailure()\n")
        self.assertIn("native cleanup quarantine escaped", self.run_guard().stderr)

    def test_native_public_assembly_cannot_skip_ready_or_supply_nonempty_proof(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for old, new in [("native.ready().get()", "native.ready()"),
                         ("transports = nativeReplayTransports", "transports = setOf(pair)")]:
            with self.subTest(old=old):
                self.assertIn(old, original); path.write_text(original.replace(old, new))
                self.assertNotEqual(0, self.run_guard().returncode)

    def test_native_public_private_hooks_cannot_escape_to_other_files(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/replay/UnexpectedBridge.kt"
        for method in ["nativeReplayIntakeAllowed", "nativeReplayRecordingAllowed", "startNativeRecording", "stopNativeRecording", "nativeRecordingStarted", "nativeReplayLifecycleChanged", "withdrawNativeReplay", "reevaluateNativeReplay"]:
            with self.subTest(method=method):
                path.write_text("fun invoke() = runtime." + method + "()\n")
                self.assertIn("bridge escaped exact assembly", self.run_guard().stderr)

    def test_native_facade_cannot_drop_original_intent_or_exact_settlement(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/facade/StandaloneFacade.kt"
        original = path.read_text()
        for old in ["if (restrictive) nativeIntentEpoch = Any()", "token.settled.compareAndSet(false, true)",
                    "operation.settled.compareAndSet(false, true)", "nativeIntentEpoch === epoch",
                    "affectsFlags = hasContextMutation(eventProperties)", "nativeEpochIsCurrent(epoch) && nativeReplayIntakeAllowed()"]:
            with self.subTest(old=old):
                self.assertIn(old, original); path.write_text(original.replace(old, "removedFence"))
                self.assertIn("native facade lost", self.run_guard().stderr)

    def test_native_runtime_cannot_replace_original_caller_acceptance(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/StandaloneRuntime.kt"
        original = path.read_text()
        self.assertIn("nativeReplay.also { nativeStartTrace.mark(NativeStartPhase.NATIVE_PRESENT, it != null) }?.reevaluate(force, originalAcceptance)", original)
        path.write_text(original.replace("nativeReplay.also { nativeStartTrace.mark(NativeStartPhase.NATIVE_PRESENT, it != null) }?.reevaluate(force, originalAcceptance)", "nativeReplay?.reevaluate(force)"))
        self.assertIn("lost original caller acceptance", self.run_guard().stderr)

    def test_native_runtime_cannot_close_queue_before_original_native_settlement(self) -> None:
        path = self.root / BOUNDARY.MAIN_KOTLIN / "dev/elu/analytics/internal/runtime/StandaloneRuntime.kt"
        path.write_text(path.read_text().replace("originalNativeClose?.awaitExact()", "originalNativeClose"))
        self.assertIn("must join original physical settlement", self.run_guard().stderr)

    def test_contract_status_mutation_fails(self) -> None:
        manifest_path = self.root / "elu-analytics/src/test/resources/contracts/v1/manifest.json"
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        manifest["transport"]["status"] = "wired"
        manifest_path.write_text(json.dumps(manifest), encoding="utf-8")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("transport status must remain specified-not-wired", result.stderr)


if __name__ == "__main__":
    unittest.main()
