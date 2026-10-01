#!/usr/bin/env bash
# Actual Gradle graph checks only. --dry-run executes no tasks or publishing code.
set -euo pipefail
cd "$(dirname "$0")/.."
output=$(mktemp)
trap 'rm -f "$output"' EXIT
base=(./gradlew --dry-run --offline --no-configuration-cache --no-parallel --console=plain)
"${base[@]}" publishPairedRelease >"$output" 2>&1 || { cat "$output"; exit 1; }
for excluded in \
  :elu-analytics:prepareMavenCentralPublishing \
  :elu-analytics-compose:prepareMavenCentralPublishing \
  :elu-analytics:enableAutomaticMavenCentralPublishing \
  :elu-analytics-compose:enableAutomaticMavenCentralPublishing \
  :verifyPairedReleaseEvidence \
  :verifyStagedPairedPublication; do
  if "${base[@]}" publishPairedRelease -x "$excluded" >"$output" 2>&1; then
    echo "Incomplete publication graph was admitted: $excluded" >&2
    exit 1
  fi
  grep -Fq 'Use publishPairedRelease with the original signed paired Lab export' "$output" || { cat "$output"; exit 1; }
done
for task in :elu-analytics:publishAndReleaseToMavenCentral :elu-analytics-compose:publishToMavenLocal; do
  if "${base[@]}" "$task" >"$output" 2>&1; then
    echo "Partial publication graph was admitted: $task" >&2
    exit 1
  fi
  grep -Fq 'Use publishPairedRelease with the original signed paired Lab export' "$output" || { cat "$output"; exit 1; }
done
echo 'Paired publication graph accepted; eight incomplete or alternate graphs refused (no task actions)'
