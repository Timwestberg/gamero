#!/usr/bin/env bash
# Build the iOS app, run it on a throwaway simulator, and pass only when the web app loads.
# In Hovel mode (the default) it also steps past the start screen and passes only when the
# page's first host bridge call is answered.
#
# Usage: tools/ios-smoke.sh [--product hovel|gamero]
#   --product gamero  the older check: the plain web view, page load only
#
# Environment:
#   GAMERO_WEB_URL         page to load (default http://127.0.0.1:8080/hovel)
#   GAMERO_EXPECTED_TITLE  document title that proves the page loaded (default Hovel). Signed
#                          out, /hovel moves to /auth?next=%2Fhovel, which keeps that title
#   GAMERO_SMOKE_TIMEOUT   seconds to wait for the page (default 90)
#   GAMERO_SIM_DEVICE      existing simulator name or UDID to reuse; it is left booted
#   GAMERO_SMOKE_ROOT_CERT PEM certificate to trust in curl and the simulator, for an HTTPS
#                          dev server with a self-signed certificate (docs/ios.md)
#
# Writes build/ios/smoke.png and build/ios/smoke.log. Exits non-zero on build
# failure, crash, or timeout. See docs/ios.md.
set -euo pipefail

product=hovel
while (( $# )); do
  case "$1" in
    --product) product="${2:-}"; shift 2 ;;
    *) echo "usage: $0 [--product hovel|gamero]" >&2; exit 2 ;;
  esac
done
[[ "$product" == hovel || "$product" == gamero ]] || { echo "usage: $0 [--product hovel|gamero]" >&2; exit 2; }

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/ios"
url="${GAMERO_WEB_URL:-http://127.0.0.1:8080/hovel}"
title="${GAMERO_EXPECTED_TITLE:-Hovel}"
timeout="${GAMERO_SMOKE_TIMEOUT:-90}"
cert="${GAMERO_SMOKE_ROOT_CERT:-}"
# Scheme and host are case-insensitive, so the origin is compared in lowercase.
origin="$(printf '%s' "$url" | sed -E 's#^([A-Za-z][A-Za-z0-9+.-]*://[^/?#]+).*#\1#' | tr '[:upper:]' '[:lower:]')"
# The app logs the origin without a default port, as TrustedOrigin.rule does.
origin="$(printf '%s' "$origin" | sed -E 's#^(https://[^/]+):443$#\1#; s#^(http://[^/]+):80$#\1#')"

fail() { echo "ios-smoke: FAIL: $*" >&2; exit 1; }
step() { echo "ios-smoke: $*"; }

# xcodebuild needs full Xcode. Use it without sudo when xcode-select points at the Command Line Tools.
if ! xcodebuild -version >/dev/null 2>&1 && [[ -d /Applications/Xcode.app ]]; then
  export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
fi
xcodebuild -version >/dev/null 2>&1 || fail "Xcode not found. Install it, or set DEVELOPER_DIR."

step "checking $url"
[[ -z "$cert" || -f "$cert" ]] || fail "GAMERO_SMOKE_ROOT_CERT $cert does not exist"
curl -fsS -o /dev/null --max-time 10 ${cert:+--cacert "$cert"} "$url" || fail "$url is not responding. Start the web app first (docs/ios.md)."

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
import json, re, sys
ios = [r for r in json.load(sys.stdin)["runtimes"] if r["platform"] == "iOS" and r["isAvailable"]]
phones = [d for d in (ios[-1]["supportedDeviceTypes"] if ios else []) if d["productFamily"] == "iPhone"]
# Prefer the newest numbered iPhone (e.g. "iPhone 17"); the list is not ordered by release.
def rank(d):
    m = re.fullmatch(r"iPhone (\d+)", d["name"])
    return int(m.group(1)) if m else -1
phones = [d["identifier"] for d in sorted(phones, key=rank)]
print(ios[-1]["identifier"], phones[-1]) if phones else print()') || true
  [[ -n "${device_type:-}" ]] || fail "no iOS simulator runtime with an iPhone installed (Xcode > Settings > Components)"
  step "creating simulator ($device_type, $runtime)"
  created="$(xcrun simctl create "gamero-smoke-$$" "$device_type" "$runtime")"
  device="$created"
fi

step "booting $device"
xcrun simctl bootstatus "$device" -b >/dev/null
[[ -z "$cert" ]] || xcrun simctl keychain "$device" add-root-cert "$cert"
xcrun simctl install "$device" "$app"

step "launching $bundle_id"
: >"$out/smoke.log"
# GAMERO_AUTO_ENTER steps past the Hovel start screen, which otherwise waits for a tap. Debug builds only.
SIMCTL_CHILD_GAMERO_PRODUCT="$product" SIMCTL_CHILD_GAMERO_AUTO_ENTER=1 SIMCTL_CHILD_GAMERO_WEB_URL="$url" xcrun simctl launch --console-pty --terminate-running-process \
  "$device" "$bundle_id" >"$out/smoke.log" 2>&1 &
console_pid=$!

expected="GAMERO_PAGE_LOADED origin=$origin title=$title"
# Pass only on a line that ends with the marker: an exact title, not a prefix such as "Hovel Error".
page_loaded() {
  tr -d '\r' <"$out/smoke.log" | awk -v o="$origin" -v t="$title" '
    { n = index($0, "GAMERO_PAGE_LOADED origin="); if (n == 0) next
      rest = substr($0, n + 26); m = index(rest, " title="); if (m == 0) next
      if (tolower(substr(rest, 1, m - 1)) == o && substr(rest, m + 7) == t) found = 1 }
    END { exit !found }'
}
deadline=$((SECONDS + timeout))
while ! page_loaded; do
  kill -0 "$console_pid" 2>/dev/null || { tail -20 "$out/smoke.log" >&2; fail "app exited before the page loaded"; }
  (( SECONDS < deadline )) || { grep GAMERO_PAGE_LOADED "$out/smoke.log" >&2 || true; fail "no '$expected' within ${timeout}s"; }
  sleep 1
done

if [[ "$product" == hovel ]]; then
  # The bridge logs the first answer each page gets. Any answer, `unsupported` included, proves the
  # round trip; `invalid` means the page and the app disagree on the protocol.
  # The page controls its title, which ends the GAMERO_PAGE_LOADED line, so that part of each line
  # is dropped before looking for the bridge marker: a title can't stand in for it.
  answered() { tr -d '\r' <"$out/smoke.log" | sed 's/GAMERO_PAGE_LOADED.*//' | grep -o 'GAMERO_BRIDGE_ANSWERED platform=ios product=[^ ]* method=[^ ]* outcome=[^ ]*' | tail -1; }
  while [[ -z "$(answered)" ]]; do
    kill -0 "$console_pid" 2>/dev/null || { tail -20 "$out/smoke.log" >&2; fail "app exited before the bridge answered"; }
    (( SECONDS < deadline )) || fail "no GAMERO_BRIDGE_ANSWERED within ${timeout}s; the page never called the host bridge"
    sleep 1
  done
  bridge="$(answered)"
  [[ "$bridge" != *" outcome=invalid" ]] || fail "$bridge"
  expected="$expected; $bridge"
fi

xcrun simctl io "$device" screenshot "$out/smoke.png" >/dev/null 2>&1 || step "warning: screenshot failed"
step "PASS: $expected (screenshot: $out/smoke.png)"
