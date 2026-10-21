#!/bin/bash
# TEMPORARY (revert before merge): before/after proof for the API 23 NativeWindowCompatibilityTest hang.
# Runs the unfixed class 8 times (each run killed after 60 s) and the fixed class 30 times.
set -u
./gradlew :elu-analytics:installDebugAndroidTest --stacktrace || exit 1
RUNNER=dev.elu.analytics.test/androidx.test.runner.AndroidJUnitRunner
PKG=dev.elu.analytics.test
SUMMARY=$(mktemp)
PROBES=$(mktemp)
run() { # label class timeout-seconds
  local out rc start end
  start=$(date +%s)
  out=$(timeout "$3" adb shell am instrument -w -e class "$2" "$RUNNER" 2>&1); rc=$?
  end=$(date +%s)
  if [ $rc -eq 124 ]; then
    adb shell am force-stop "$PKG"
    echo "$1 HUNG (killed after $3 s)"
  elif echo "$out" | grep -q "^OK ("; then
    echo "$1 PASS in $((end - start)) s"
  else
    echo "$1 FAIL"; echo "$out" | tail -25
  fi
}
for i in $(seq 1 8); do
  run "original-$i" dev.elu.analytics.internal.replay.NativeWindowCompatibilityOriginalTest 60 | tee -a "$SUMMARY"
done
for i in $(seq 1 30); do
  adb logcat -c
  run "fixed-$i" dev.elu.analytics.internal.replay.NativeWindowCompatibilityTest 120 | tee -a "$SUMMARY"
  adb logcat -d -s EluIdleProbe:I | grep EluIdleProbe | sed "s/^/fixed-$i /" | tee -a "$PROBES"
done
echo "== summary"
echo "original: $(grep -c '^original-.* PASS' "$SUMMARY") pass, $(grep -c '^original-.* HUNG' "$SUMMARY") hung, $(grep -c '^original-.* FAIL' "$SUMMARY") fail"
echo "fixed: $(grep -c '^fixed-.* PASS' "$SUMMARY") pass, $(grep -c '^fixed-.* HUNG' "$SUMMARY") hung, $(grep -c '^fixed-.* FAIL' "$SUMMARY") fail"
echo "fixed windows that never idled while the spinner ran: $(grep -c 'idleWhileSpinning=false' "$PROBES") of $(grep -c 'EluIdleProbe' "$PROBES")"
! grep -q '^fixed-.* \(HUNG\|FAIL\)' "$SUMMARY"
