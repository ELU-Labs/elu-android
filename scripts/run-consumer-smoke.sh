#!/usr/bin/env bash
# Install an optimized consumer fixture APK on the single connected device, launch
# its smoke activity and wait for the result it logs. Exits non-zero on a smoke
# failure, an app crash or a timeout.
#
# Usage: scripts/run-consumer-smoke.sh <apk> <applicationId> [timeout-seconds]
set -euo pipefail

apk="$1"
package="$2"
timeout="${3:-120}"
test -f "$apk" || { echo "missing APK: $apk" >&2; exit 2; }

adb install -r "$apk"
adb logcat -c || true
adb shell am start -W -n "$package/dev.elu.fixture.smoke.SmokeActivity"

deadline=$((SECONDS + timeout))
while (( SECONDS < deadline )); do
  log="$(adb logcat -d -v brief -s EluConsumerSmoke:V AndroidRuntime:E)"
  if grep -q "ELU_CONSUMER_SMOKE PASS" <<<"$log"; then
    grep "ELU_CONSUMER_SMOKE PASS" <<<"$log"
    adb uninstall "$package" >/dev/null || true
    exit 0
  fi
  if grep -q -e "ELU_CONSUMER_SMOKE FAIL" -e "FATAL EXCEPTION" <<<"$log"; then
    printf '%s\n' "$log"
    echo "consumer smoke failed for $package" >&2
    exit 1
  fi
  sleep 2
done
adb logcat -d -v brief | tail -n 200
echo "consumer smoke timed out for $package" >&2
exit 1
