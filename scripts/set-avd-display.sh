#!/usr/bin/env bash
# Pin an AVD's display before its first boot by writing hw.lcd.width, hw.lcd.height and
# hw.lcd.density into its config.ini. Each key ends up exactly once, so values appended
# earlier (for example by the emulator action) cannot shadow it.
#
# CI uses this instead of a device profile: a profile would also change RAM, heap and
# density. avdmanager without a profile gives 320x640 at density 160.
#
# Usage: scripts/set-avd-display.sh <avd-config.ini> <width>x<height> <density>
set -euo pipefail

usage='usage: set-avd-display.sh <avd-config.ini> <width>x<height> <density>'
config="${1:?$usage}"
display="${2:?$usage}"
density="${3:?$usage}"
test -f "$config" || { echo "missing AVD config: $config" >&2; exit 2; }
[[ "$display" =~ ^([1-9][0-9]*)x([1-9][0-9]*)$ ]] || { echo "display must be <width>x<height>: $display" >&2; exit 2; }
width="${BASH_REMATCH[1]}"
height="${BASH_REMATCH[2]}"
[[ "$density" =~ ^[1-9][0-9]*$ ]] || { echo "density must be a positive integer: $density" >&2; exit 2; }

set_key() {
  local key="$1" value="$2" pattern tmp
  pattern="^${key//./\\.}[[:space:]]*="
  tmp="$(mktemp)"
  grep -v -E "$pattern" "$config" > "$tmp" || true
  printf '%s=%s\n' "$key" "$value" >> "$tmp"
  cat "$tmp" > "$config"
  rm -f "$tmp"
}

check_key() {
  local key="$1" value="$2" pattern lines
  pattern="^${key//./\\.}[[:space:]]*="
  lines="$(grep -E "$pattern" "$config")"
  test "$lines" = "$key=$value" || { printf 'AVD config %s is not %s=%s:\n%s\n' "$config" "$key" "$value" "$lines" >&2; exit 1; }
}

set_key hw.lcd.width "$width"
set_key hw.lcd.height "$height"
set_key hw.lcd.density "$density"
check_key hw.lcd.width "$width"
check_key hw.lcd.height "$height"
check_key hw.lcd.density "$density"
grep -E '^hw\.lcd\.(width|height|density)=' "$config"
