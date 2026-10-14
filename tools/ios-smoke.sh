#!/usr/bin/env bash
# Build the iOS app, run it on a throwaway simulator, and pass only when the web app loads.
#
# Usage: tools/ios-smoke.sh
#
# Environment:
#   GAMERO_WEB_URL         page to load (default http://127.0.0.1:8080/hovel.html)
#   GAMERO_EXPECTED_TITLE  document title that proves the page loaded (default Hovel)
#   GAMERO_SMOKE_TIMEOUT   seconds to wait for the page (default 90)
#   GAMERO_SIM_DEVICE      existing simulator name or UDID to reuse; it is left booted
#
# Writes build/ios/smoke.png and build/ios/smoke.log. Exits non-zero on build
# failure, crash, or timeout. See docs/ios.md.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/ios"
url="${GAMERO_WEB_URL:-http://127.0.0.1:8080/hovel.html}"
title="${GAMERO_EXPECTED_TITLE:-Hovel}"
timeout="${GAMERO_SMOKE_TIMEOUT:-90}"
origin="$(printf '%s' "$url" | sed -E 's#^([a-z]+://[^/?#]+).*#\1#')"

fail() { echo "ios-smoke: FAIL: $*" >&2; exit 1; }
step() { echo "ios-smoke: $*"; }

# xcodebuild needs full Xcode. Use it without sudo when xcode-select points at the Command Line Tools.
if ! xcodebuild -version >/dev/null 2>&1 && [[ -d /Applications/Xcode.app ]]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
xcodebuild -version >/dev/null 2>&1 || fail "Xcode not found. Install it, or set DEVELOPER_DIR."

step "checking $url"
curl -fsS -o /dev/null --max-time 10 "$url" || fail "$url is not responding. Start the web app first (docs/ios.md)."

mkdir -p "$out"
step "building (log: $out/xcodebuild.log)"
xcodebuild -project "$root/iosApp/iosApp.xcodeproj" -scheme iosApp -sdk iphonesimulator \
  -configuration Debug -derivedDataPath "$out" CODE_SIGNING_ALLOWED=NO build \
  >"$out/xcodebuild.log" 2>&1 || { grep -E "error:" "$out/xcodebuild.log" | head -20 >&2; fail "build failed"; }

app="$(find "$out/Build/Products/Debug-iphonesimulator" -maxdepth 1 -name '*.app' | head -1)"
[[ -n "$app" ]] || fail "no .app in $out/Build/Products/Debug-iphonesimulator"
bundle_id="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "$app/Info.plist")"

created=""
console_pid=""
cleanup() {
  [[ -n "$console_pid" ]] && kill "$console_pid" 2>/dev/null || true
  if [[ -n "$created" ]]; then
    xcrun simctl shutdown "$created" >/dev/null 2>&1 || true
    xcrun simctl delete "$created" >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

if [[ -n "${GAMERO_SIM_DEVICE:-}" ]]; then
  device="$GAMERO_SIM_DEVICE"
else
  read -r runtime device_type < <(xcrun simctl list runtimes -j | /usr/bin/python3 -c '
import json, sys
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
phones = [d["identifier"] for d in (ios[-1]["supportedDeviceTypes"] if ios else []) if d["productFamily"] == "iPhone"]
print(ios[-1]["identifier"], phones[-1]) if phones else print()') || true
  [[ -n "${device_type:-}" ]] || fail "no iOS simulator runtime with an iPhone installed (Xcode > Settings > Components)"
  step "creating simulator ($device_type, $runtime)"
  created="$(xcrun simctl create "gamero-smoke-$$" "$device_type" "$runtime")"
  device="$created"
fi

step "booting $device"
xcrun simctl bootstatus "$device" -b >/dev/null
xcrun simctl install "$device" "$app"

step "launching $bundle_id"
: >"$out/smoke.log"
SIMCTL_CHILD_GAMERO_WEB_URL="$url" xcrun simctl launch --console-pty --terminate-running-process \
  "$device" "$bundle_id" >"$out/smoke.log" 2>&1 &
console_pid=$!

expected="GAMERO_PAGE_LOADED origin=$origin title=$title"
deadline=$((SECONDS + timeout))
while ! grep -qF "$expected" "$out/smoke.log"; do
  kill -0 "$console_pid" 2>/dev/null || { tail -20 "$out/smoke.log" >&2; fail "app exited before the page loaded"; }
  (( SECONDS < deadline )) || { grep GAMERO_PAGE_LOADED "$out/smoke.log" >&2 || true; fail "no '$expected' within ${timeout}s"; }
  sleep 1
done

xcrun simctl io "$device" screenshot "$out/smoke.png" >/dev/null 2>&1 || step "warning: screenshot failed"
step "PASS: $expected (screenshot: $out/smoke.png)"
