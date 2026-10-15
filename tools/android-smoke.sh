#!/usr/bin/env bash
# Build the Android debug app, run it on an emulator with the Hovel launch extras, step past the
# start screen, and pass only when the web app loads and its first host bridge call is answered.
#
# Usage: tools/android-smoke.sh
#
# Environment:
#   GAMERO_WEB_URL         page to load (default http://127.0.0.1:8080/hovel.html). The debug build
#                          opens HTTPS, or http on loopback (HovelAndroid.kt). A loopback port is
#                          forwarded to this machine with adb reverse, so the local edge just works.
#   GAMERO_EXPECTED_TITLE  document title that proves the page loaded (default Hovel)
#   GAMERO_SMOKE_TIMEOUT   seconds to wait for the page and the bridge (default 90)
#   GAMERO_AVD             emulator to boot when no device is attached (default Pixel_8_Pro_API_34)
#   ANDROID_SERIAL         device to use when several are attached (adb reads it)
#   ANDROID_HOME           Android SDK (default: local.properties sdk.dir, then ~/Library/Android/sdk, ~/Android/Sdk)
#   BUILD_TIMINGS_FILE     CSV that every step's duration is appended to (default .build-timings.csv)
#
# Writes build/android/smoke.png and build/android/smoke.log. Exits non-zero on build failure,
# crash, or timeout. See docs/desktop.md and TEST.md.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$root"
out="$root/build/android"
url="${GAMERO_WEB_URL:-http://127.0.0.1:8080/hovel.html}"
title="${GAMERO_EXPECTED_TITLE:-Hovel}"
timeout="${GAMERO_SMOKE_TIMEOUT:-90}"
avd="${GAMERO_AVD:-Pixel_8_Pro_API_34}"
package=org.formulagen.wrapper

fail() { echo "android-smoke: FAIL: $*" >&2; exit 1; }
step() { echo "android-smoke: $*"; }

timings_file="${BUILD_TIMINGS_FILE:-$root/.build-timings.csv}"
case "$timings_file" in /*) ;; *) timings_file="$PWD/$timings_file" ;; esac
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
commit="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"
host="$(hostname -s 2>/dev/null || echo unknown)"
# Same columns as build-release.sh and test-all-targets.sh, so tools/build-timings.sh reads all three.
record() {
  [[ -s "$timings_file" ]] || echo "run_id,finished_at,script,step,seconds,detail,commit,host" >"$timings_file"
  printf '%s,%s,android-smoke,%s,%s,%s,%s,%s\n' "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3//,/;}" "$commit" "$host" >>"$timings_file"
}
# Returns the command's status: under `||`, set -e would not stop a failing command here.
timed() { local name="$1" begun=$SECONDS rc=0; shift; "$@" || rc=$?; record "$name" $((SECONDS - begun)) "${rc/#0/}"; return $rc; }

# Scheme and host are case-insensitive, so the origin is compared in lowercase.
origin="$(printf '%s' "$url" | sed -E 's#^([A-Za-z][A-Za-z0-9+.-]*://[^/?#]+).*#\1#' | tr '[:upper:]' '[:lower:]')"
# The app logs the origin without a default port, as TrustedOrigin.rule does.
origin="$(printf '%s' "$origin" | sed -E 's#^(https://[^/]+):443$#\1#; s#^(http://[^/]+):80$#\1#')"
scheme="${origin%%://*}"
authority="${origin#*://}"
port=""
if [[ "$authority" =~ ^(localhost|127\.0\.0\.1|\[::1\])(:([0-9]+))?$ ]]; then
  port="${BASH_REMATCH[3]:-}"
  [[ -n "$port" ]] || { [[ "$scheme" == http ]] && port=80 || port=443; }
elif [[ "$scheme" != https ]]; then
  fail "GAMERO_WEB_URL must be HTTPS, or http on loopback (HovelAndroid.kt)"
fi

step "checking $url"
curl -fsS -o /dev/null --max-time 10 "$url" || fail "$url is not responding. Start the web app first (docs/ios.md)."

if [[ -z "${ANDROID_HOME:-}" ]]; then
  sdk="$(sed -n 's/^sdk\.dir=//p' local.properties 2>/dev/null || true)"
  for candidate in "$sdk" "$HOME/Library/Android/sdk" "$HOME/Android/Sdk"; do
    if [[ -n "$candidate" && -d "$candidate" ]]; then export ANDROID_HOME="$candidate"; break; fi
  done
fi
[[ -n "${ANDROID_HOME:-}" ]] || fail "Android SDK not found. Set ANDROID_HOME."
adb="$ANDROID_HOME/platform-tools/adb"
[[ -x "$adb" ]] || fail "no adb at $adb"

mkdir -p "$out"
# A failed build must not leave an older APK to install.
rm -f composeApp/build/outputs/apk/debug/*.apk
step "building the debug app (log: $out/gradle.log)"
timed build ./gradlew -Pgamero.mobileTargets=true :composeApp:assembleDebug >"$out/gradle.log" 2>&1 \
  || { grep -E "^e: |error:|FAILURE" "$out/gradle.log" | head -20 >&2; fail "build failed"; }
apk="$(find composeApp/build/outputs/apk/debug -maxdepth 1 -name '*.apk' | head -1)"
[[ -n "$apk" ]] || fail "no debug APK in composeApp/build/outputs/apk/debug"

started_emulator=""
logcat_pid=""
cleanup() {
  [[ -n "$logcat_pid" ]] && kill "$logcat_pid" 2>/dev/null || true
  [[ -n "$port" ]] && "$adb" reverse --remove "tcp:$port" >/dev/null 2>&1 || true
  [[ -n "$started_emulator" ]] && "$adb" emu kill >/dev/null 2>&1 || true
}
trap cleanup EXIT

devices="$("$adb" devices | awk 'NR > 1 && $2 == "device"' | wc -l | tr -d ' ')"
(( devices <= 1 )) || [[ -n "${ANDROID_SERIAL:-}" ]] || fail "$devices devices attached; set ANDROID_SERIAL to pick one"
if [[ -n "${ANDROID_SERIAL:-}" ]]; then
  "$adb" get-state >/dev/null 2>&1 || fail "ANDROID_SERIAL $ANDROID_SERIAL is not attached"
elif (( devices == 0 )); then
  step "booting emulator $avd"
  "$ANDROID_HOME/emulator/emulator" -avd "$avd" -no-snapshot-save -no-audio >"$out/emulator.log" 2>&1 &
  started_emulator=1
fi
boot() {
  "$adb" wait-for-device
  local deadline=$((SECONDS + 180))
  until [[ "$("$adb" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == 1 ]]; do
    (( SECONDS < deadline )) || fail "emulator did not boot within 180s (log: $out/emulator.log)"
    sleep 2
  done
}
timed boot boot

if [[ -n "$port" ]]; then
  step "forwarding device port $port to this machine"
  "$adb" reverse "tcp:$port" "tcp:$port" >/dev/null
fi

timed install "$adb" install -r "$apk" >/dev/null

step "launching $package"
"$adb" logcat -c
"$adb" logcat -v brief >"$out/smoke.log" 2>&1 &
logcat_pid=$!
launched=$SECONDS
# GAMERO_AUTO_ENTER steps past the Hovel start screen, which otherwise waits for a tap. Debug builds only.
"$adb" shell am start -S -W -n "$package/.MainActivity" \
  -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL "'$url'" -e GAMERO_AUTO_ENTER 1 >/dev/null

expected="GAMERO_PAGE_LOADED origin=$origin title=$title"
# Only the app's own log tags count. WebView console messages also reach logcat (tag chromium), so a
# page's console.log could otherwise print either marker.
native() { tr -d '\r' <"$out/smoke.log" | grep -E "^I/$1 *\(" || true; }
# Pass only on a line that ends with the marker: an exact title, not a prefix such as "Hovel Error".
page_loaded() {
  native MainActivity | awk -v o="$origin" -v t="$title" '
    { n = index($0, "GAMERO_PAGE_LOADED origin="); if (n == 0) next
      rest = substr($0, n + 26); m = index(rest, " title="); if (m == 0) next
      if (tolower(substr(rest, 1, m - 1)) == o && substr(rest, m + 7) == t) found = 1 }
    END { exit !found }'
}
answered() { native HostBridge | grep -o 'GAMERO_BRIDGE_ANSWERED platform=android product=[^ ]* method=[^ ]* outcome=[^ ]*' | tail -1; }
running() { "$adb" shell pidof "$package" >/dev/null 2>&1; }

deadline=$((SECONDS + timeout))
while ! page_loaded; do
  running || { tail -20 "$out/smoke.log" >&2; fail "app exited before the page loaded"; }
  (( SECONDS < deadline )) || { grep GAMERO_PAGE_LOADED "$out/smoke.log" >&2 || true; fail "no '$expected' within ${timeout}s"; }
  sleep 1
done
record page_loaded $((SECONDS - launched)) "$origin"

# Any answer, `unsupported` included, proves the round trip; `invalid` means the page and the app
# disagree on the protocol.
while [[ -z "$(answered)" ]]; do
  running || { tail -20 "$out/smoke.log" >&2; fail "app exited before the bridge answered"; }
  (( SECONDS < deadline )) || fail "no GAMERO_BRIDGE_ANSWERED within ${timeout}s; the page never called the host bridge"
  sleep 1
done
bridge="$(answered)"
record bridge_answered $((SECONDS - launched)) "${bridge##* outcome=}"
[[ "$bridge" != *" outcome=invalid" ]] || fail "$bridge"

"$adb" exec-out screencap -p >"$out/smoke.png" 2>/dev/null || step "warning: screenshot failed"
step "PASS: $expected; $bridge (screenshot: $out/smoke.png)"
