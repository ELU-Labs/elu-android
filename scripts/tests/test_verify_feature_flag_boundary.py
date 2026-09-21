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
        for relative in {*BOUNDARY.PINNED_FILES, *BOUNDARY.CONTRACT_FILES}:
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

    def test_clean_internal_transport_boundary_passes(self) -> None:
        result = self.run_guard()
        self.assertEqual(0, result.returncode, result.stderr)

    def test_standalone_must_not_supply_replay_proof(self) -> None:
        stack = self.root / BOUNDARY.STACK
        stack.write_text(stack.read_text() + "\nval readbackProvenReplayTransports = setOf(pair)\n")
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("must not supply replay proof", result.stderr)

    def test_owned_native_capability_selection_cannot_add_a_codec_or_generation(self) -> None:
        path = self.root / BOUNDARY.STACK
        original = path.read_text()
        for before, after in [('setOf(V1ReplayTransport("elu-native-wireframe-v1", V1ReplayCompression.GZIP))',
                               'setOf(V1ReplayTransport("foreign-codec", V1ReplayCompression.GZIP))'),
                              ('setOf("protocol-generation-v1")', 'setOf("protocol-generation-v1", "future")')]:
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
        for injected in ["applicationContext.filesDir", "applicationContext.cacheDir", "AndroidCoreStateStore.forProduction(file)"]:
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
        before = "matchesDiscovery(selectedActivity, selectedRoot)"
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
        refusal = 'if (request.transport.codec == "elu-native-wireframe-v1") return@submit ReplayAppendResult.Rejected(ReplayAppendRejection.AUTHORITY)'
        self.assertIn(refusal, original)
        path.write_text(original.replace(refusal, ""))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("generic replay append must reject native codec", result.stderr)

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
        old = "override fun onActivityPrePaused(activity: Activity) = nativeObserved.withdrawing(activity)"
        self.assertIn(old, original)
        path.write_text(original.replace(old, "override fun onActivityPrePaused(activity: Activity) = worker.execute { nativeObserved.withdrawing(activity) }"))
        result = self.run_guard()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("withdrawal must be synchronous", result.stderr)

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
        for old, new in [("AndroidStandaloneStack.facade(appContext, key, configHost, options.performance)", "AndroidStandaloneStack.facade(appContext, key, anotherHost)"),
                         ("sink = facade", "sink = null")]:
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
        for method in ["nativeReplayIntakeAllowed", "nativeReplayLifecycleChanged", "withdrawNativeReplay", "reevaluateNativeReplay"]:
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
