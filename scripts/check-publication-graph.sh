#!/usr/bin/env bash
# Actual Gradle graph checks only. --dry-run executes no tasks or publishing code.
# Only dev.elu:elu-analytics may be staged for Central, through stageCentralRelease,
# with no automatic release; the Compose module has no Central publication at all.
set -euo pipefail
cd "$(dirname "$0")/.."
output=$(mktemp)
trap 'rm -f "$output"' EXIT
base=(./gradlew --dry-run --offline --no-configuration-cache --no-parallel --console=plain)
refusal='Use stageCentralRelease with the signed release tag'

"${base[@]}" stageCentralRelease >"$output" 2>&1 || { cat "$output"; exit 1; }
for required in :verifyReleaseCandidate :elu-analytics:publishMavenPublicationToMavenCentralRepository \
  :verifyStagedPublication :elu-analytics:publishToMavenCentral :stageCentralRelease; do
  grep -Eq "^${required} SKIPPED\$" "$output" || { cat "$output"; echo "Missing from graph: $required" >&2; exit 1; }
done
for forbidden in enableAutomaticMavenCentralPublishing :elu-analytics-compose:; do
  if grep -Fq "$forbidden" "$output"; then
    cat "$output"
    echo "Unexpected task in the release graph: $forbidden" >&2
    exit 1
  fi
done

refused=0
refuse() {
  if "${base[@]}" "$@" >"$output" 2>&1; then
    echo "Incomplete or alternate publication graph was admitted: $*" >&2
    exit 1
  fi
  grep -Fq "$refusal" "$output" || { cat "$output"; exit 1; }
  refused=$((refused + 1))
}
for excluded in :elu-analytics:prepareMavenCentralPublishing :verifyReleaseCandidate :verifyStagedPublication; do
  refuse stageCentralRelease -x "$excluded"
done
refuse stageCentralRelease -PmavenCentralAutomaticPublishing=true
for task in :elu-analytics:publishAndReleaseToMavenCentral :elu-analytics:publishToMavenCentral \
  :elu-analytics:publishToMavenLocal :elu-analytics-compose:publishToMavenLocal; do
  refuse "$task"
done

for task in :elu-analytics-compose:publishToMavenCentral :elu-analytics-compose:publishAndReleaseToMavenCentral; do
  if "${base[@]}" "$task" >"$output" 2>&1; then
    echo "Compose module has a Central publication task: $task" >&2
    exit 1
  fi
  grep -Fq "not found in project ':elu-analytics-compose'" "$output" || { cat "$output"; exit 1; }
done
echo "Core-only publication graph accepted without automatic release; ${refused} alternate graphs refused; Compose has no Central task (no task actions)"
