#!/usr/bin/env bash
set -euo pipefail

readonly SOURCE_VERSION="0.1.0"
readonly APP_ID="dev.elu.analytics.upgradeevidence"
readonly TEST_COMPONENT="${APP_ID}.test/androidx.test.runner.AndroidJUnitRunner"
readonly TEST_CLASS="dev.elu.analytics.upgradeevidence.UpgradeContinuityTest"

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

command -v adb >/dev/null || { echo "adb is required" >&2; exit 1; }

evidence_dir="$repo_root/build/reports/upgrade-evidence/$SOURCE_VERSION"
raw_dir="$evidence_dir/raw"
mkdir -p "$raw_dir"

apk_dir="$(mktemp -d)"
trap 'rm -rf "$apk_dir"' EXIT

./gradlew \
  :upgrade-evidence:clean \
  :upgrade-evidence:assembleDebug \
  :upgrade-evidence:assembleDebugAndroidTest \
  :upgrade-evidence:verifyUpgradeDependencySelection \
  -PeluUpgradeDependency=published \
  -PeluUpgradeSourceVersion="$SOURCE_VERSION" \
  --stacktrace

cp upgrade-evidence/build/outputs/apk/debug/upgrade-evidence-debug.apk \
  "$apk_dir/published.apk"
cp upgrade-evidence/build/outputs/apk/androidTest/debug/upgrade-evidence-debug-androidTest.apk \
  "$apk_dir/published-androidTest.apk"
cp upgrade-evidence/build/reports/upgrade-dependency-selection.json \
  "$apk_dir/published-dependency-selection.json"

./gradlew \
  :upgrade-evidence:clean \
  :upgrade-evidence:assembleDebug \
  :upgrade-evidence:assembleDebugAndroidTest \
  :upgrade-evidence:verifyUpgradeDependencySelection \
  -PeluUpgradeDependency=candidate \
  -PeluUpgradeSourceVersion="$SOURCE_VERSION" \
  --stacktrace

cp upgrade-evidence/build/outputs/apk/debug/upgrade-evidence-debug.apk \
  "$apk_dir/candidate.apk"
cp upgrade-evidence/build/outputs/apk/androidTest/debug/upgrade-evidence-debug-androidTest.apk \
  "$apk_dir/candidate-androidTest.apk"
cp upgrade-evidence/build/reports/upgrade-dependency-selection.json \
  "$apk_dir/candidate-dependency-selection.json"

adb uninstall "$APP_ID" >/dev/null 2>&1 || true
adb uninstall "${APP_ID}.test" >/dev/null 2>&1 || true

adb install -t "$apk_dir/published.apk" >"$raw_dir/anonymous-published-app-install.txt" 2>&1
adb install -t "$apk_dir/published-androidTest.apk" >"$raw_dir/anonymous-published-test-install.txt" 2>&1
adb shell am instrument -w -r \
  -e class "${TEST_CLASS}#establishPublishedAnonymousState" \
  "$TEST_COMPONENT" >"$raw_dir/anonymous-published-state.instrumentation.txt" 2>&1

adb shell am force-stop "$APP_ID"
adb install -r -t "$apk_dir/candidate.apk" >"$raw_dir/anonymous-candidate-app-replace.txt" 2>&1
adb install -r -t "$apk_dir/candidate-androidTest.apk" >"$raw_dir/anonymous-candidate-test-replace.txt" 2>&1
adb shell am instrument -w -r \
  -e class "${TEST_CLASS}#verifyAnonymousReplacementContinuity" \
  "$TEST_COMPONENT" >"$raw_dir/anonymous-continuity.instrumentation.txt" 2>&1

adb uninstall "$APP_ID" >/dev/null 2>&1 || true
adb uninstall "${APP_ID}.test" >/dev/null 2>&1 || true

adb install -t "$apk_dir/published.apk" >"$raw_dir/identified-published-app-install.txt" 2>&1
adb install -t "$apk_dir/published-androidTest.apk" >"$raw_dir/identified-published-test-install.txt" 2>&1
adb shell am instrument -w -r \
  -e class "${TEST_CLASS}#establishPublishedIdentifiedState" \
  "$TEST_COMPONENT" >"$raw_dir/identified-published-state.instrumentation.txt" 2>&1

adb shell am force-stop "$APP_ID"
adb install -r -t "$apk_dir/candidate.apk" >"$raw_dir/identified-candidate-app-replace.txt" 2>&1
adb install -r -t "$apk_dir/candidate-androidTest.apk" >"$raw_dir/identified-candidate-test-replace.txt" 2>&1
adb shell am instrument -w -r \
  -e class "${TEST_CLASS}#verifyIdentifiedReplacementContinuity" \
  "$TEST_COMPONENT" >"$raw_dir/identified-continuity.instrumentation.txt" 2>&1

python3 scripts/normalize-android-upgrade-evidence.py \
  --raw-dir "$raw_dir" \
  --output "$evidence_dir/manifest.json" \
  --digest-output "$evidence_dir/manifest.sha256" \
  --source-version "$SOURCE_VERSION" \
  --candidate-revision "$(git rev-parse HEAD)" \
  --api-level "$(adb shell getprop ro.build.version.sdk | tr -d '\r')" \
  --assertions "upgrade-evidence/$SOURCE_VERSION/assertions.json" \
  --published-selection-report "$apk_dir/published-dependency-selection.json" \
  --candidate-selection-report "$apk_dir/candidate-dependency-selection.json" \
  --published-apk "$apk_dir/published.apk" \
  --candidate-apk "$apk_dir/candidate.apk"
