#!/usr/bin/env bash
# Build production-optimized Gamero releases for one or more platforms.
#
# Usage: tools/build-release.sh <platform>...
#   android   R8-shrunk release AAB and APK (signed when a keystore is set)
#   desktop   ProGuard-shrunk release installer for this OS (DMG on a Mac)
#   ios       Release archive, exported as an IPA when a team is set (Mac only)
#   web       the game-platform host's production build (scripts/build-web.sh there)
#   all       web, android, desktop, then ios
#
# Environment (see docs/release.md):
#   GAMERO_WEB_URL          page the apps open: HTTPS, or http on loopback for local desktop and
#                           iOS simulator testing (default: game-platform's local edge,
#                           http://127.0.0.1:8080/hovel.html). Android needs HTTPS.
#   GAMERO_PRODUCT          product the apps present (default hovel)
#   GAMERO_VERSION          version name, MAJOR.MINOR.PATCH (default 1.0.0)
#   GAMERO_BUILD_NUMBER     integer build number (default 1)
#   GAMERO_ANDROID_KEYSTORE, GAMERO_ANDROID_KEYSTORE_PASSWORD,
#   GAMERO_ANDROID_KEY_ALIAS, GAMERO_ANDROID_KEY_PASSWORD
#                           release signing; without them the Android outputs are unsigned
#   GAMERO_BROWSER_JAVA_HOME  JetBrains Runtime SDK with JCEF; bundled into the desktop app
#   GAMERO_MAC_SIGN_IDENTITY  Developer ID Application identity for the DMG (optional)
#   GAMERO_NOTARIZE_APPLE_ID, GAMERO_NOTARIZE_PASSWORD, GAMERO_NOTARIZE_TEAM_ID
#                           notarize the DMG (optional, needs the signing identity)
#   GAMERO_IOS_TEAM_ID      Apple team; without it the iOS archive is unsigned and not exported
#   GAMERO_IOS_EXPORT_METHOD  app-store-connect (default), release-testing or enterprise
#   GAME_PLATFORM_DIR       game-platform checkout for web (default: next to this repo)
#   GAMERO_SEQUENTIAL       1 to run the web build in the foreground instead of alongside the others
#   BUILD_TIMINGS_FILE      CSV that every step's duration is appended to
#                           (default: .build-timings.csv at the repo root, git-ignored)
#
# Every platform's settings are checked before anything builds. The web build runs
# in the background (log: build/release/web/build.log) while Android and desktop
# share one Gradle run, then iOS archives. Every step's duration is appended to
# BUILD_TIMINGS_FILE, down to Gradle tasks (from --profile) and Xcode build phases
# (from the archive's result bundle); tools/build-timings.sh compares recent runs.
#
# Outputs land in build/release/<platform>/. Exits non-zero at the first failure.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
out="$root/build/release"
product="${GAMERO_PRODUCT:-hovel}"
version="${GAMERO_VERSION:-1.0.0}"
build_number="${GAMERO_BUILD_NUMBER:-1}"
# game-platform serves the web build at /hovel.html on its edge, port 8080 (infra/edge/defaults.json).
local_url="http://127.0.0.1:8080/hovel.html"
url="${GAMERO_WEB_URL:-$local_url}"

fail() { echo "build-release: FAIL: $*" >&2; exit 1; }
step() { echo "build-release: $*"; }
started=$SECONDS
timings=()
timings_file="${BUILD_TIMINGS_FILE:-$root/.build-timings.csv}"
case "$timings_file" in /*) ;; *) timings_file="$PWD/$timings_file" ;; esac
run_id="$(date -u +%Y%m%dT%H%M%SZ)-$$"
commit="$(git -C "$root" rev-parse --short HEAD 2>/dev/null || echo unknown)"
host="$(hostname -s 2>/dev/null || echo unknown)"
# One CSV row per step: run_id,finished_at,script,step,seconds,detail,commit,host.
# game-platform's build-web.sh writes the same columns, so a full run shares one file.
record() {
  [[ -s "$timings_file" ]] || echo "run_id,finished_at,script,step,seconds,detail,commit,host" >"$timings_file"
  printf '%s,%s,build-release,%s,%s,%s,%s,%s\n' "$run_id" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$1" "$2" "${3//,/;}" "$commit" "$host" >>"$timings_file"
}
# timed <step> <start> [detail] [end]: end defaults to now, in $SECONDS.
timed() {
  local took=$((${4:-$SECONDS} - $2))
  timings+=("$(printf '%-16s %5ss' "$1" "$took")")
  step "$1 took ${took}s"
  record "$1" "$took" "${3:-}"
}
# usage [status]: help goes to stdout and exits 0; a usage error goes to stderr and exits 2.
usage() {
  local status="${1:-2}"
  if [[ "$status" == 0 ]]; then
    sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  else
    sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2
  fi
  exit "$status"
}

[[ $# -gt 0 ]] || usage
platforms=()
for arg in "$@"; do
  case "$arg" in
    all) platforms+=(web android desktop ios) ;;
    android | desktop | ios | web) platforms+=("$arg") ;;
    -h | --help) usage 0 ;;
    *) echo "build-release: unknown platform '$arg'" >&2; usage ;;
  esac
done

want() { [[ " ${platforms[*]} " == *" $1 "* ]]; }

[[ "$version" =~ ^[1-9][0-9]*\.[0-9]+\.[0-9]+$ ]] || fail "GAMERO_VERSION must be MAJOR.MINOR.PATCH with MAJOR > 0 (macOS requires it), not '$version'"
[[ "$build_number" =~ ^[1-9][0-9]*$ ]] || fail "GAMERO_BUILD_NUMBER must be a positive integer, not '$build_number'"

# The same rule as WebUrlPolicy.kt: HTTPS, or http on loopback; a host, no userinfo or fragment.
require_url() {
  [[ "$url" =~ ^[Hh][Tt][Tt][Pp][Ss]://[^/@#?]+(/[^#]*)?$ \
    || "$url" =~ ^[Hh][Tt][Tt][Pp]://(127\.0\.0\.1|localhost|\[::1\])(:[0-9]+)?(/[^#]*)?$ ]] \
    || fail "GAMERO_WEB_URL must be https://host/... or http on loopback, without userinfo or a fragment, not '$url'"
  if [[ "$url" =~ ^[Hh][Tt][Tt][Pp]:// ]]; then
    step "GAMERO_WEB_URL is $url, a loopback page: this build is for local testing, not for users"
  fi
}

gradle() {
  "$root/gradlew" -p "$root" --console=plain \
    -Pgamero.product="$product" -Pgamero.webUrl="$url" \
    -Pgamero.version="$version" -Pgamero.buildNumber="$build_number" "$@"
}

web_dir() {
  local game_platform="${GAME_PLATFORM_DIR:-$root/../game-platform}"
  [[ -f "$game_platform/scripts/build-web.sh" ]] || fail "no scripts/build-web.sh in $game_platform. Check out game-platform there or set GAME_PLATFORM_DIR."
  (cd "$game_platform" && pwd)
}

web_pid=""
web_started=0
start_web() {
  local game_platform
  game_platform="$(web_dir)"
  rm -rf "$out/web" && mkdir -p "$out/web"
  web_started=$SECONDS
  if [[ "${GAMERO_SEQUENTIAL:-}" == 1 ]]; then
    step "web: building $game_platform"
    BUILD_TIMINGS_FILE="$timings_file" BUILD_RUN_ID="$run_id" \
      bash "$game_platform/scripts/build-web.sh" 2>&1 | tee "$out/web/build.log"
    finish_web
  else
    step "web: building $game_platform in the background (log: $out/web/build.log)"
    # The job notes when it ended, so the web row is its own duration, not the wait for iOS.
    (
      BUILD_TIMINGS_FILE="$timings_file" BUILD_RUN_ID="$run_id" \
        bash "$game_platform/scripts/build-web.sh" >"$out/web/build.log" 2>&1
      rc=$?
      echo "$SECONDS" >"$out/web/.finished"
      exit "$rc"
    ) &
    web_pid=$!
  fi
}

finish_web() {
  if [[ -n "$web_pid" ]]; then
    local pid="$web_pid"
    web_pid=""
    wait "$pid" || { tail -30 "$out/web/build.log" >&2; fail "web: build failed (log: $out/web/build.log)"; }
  fi
  local dist finished=$SECONDS
  [[ ! -f "$out/web/.finished" ]] || finished="$(cat "$out/web/.finished")"
  dist="$(web_dir)/apps/game-bridge-host/dist"
  printf '%s\n' "$dist" >"$out/web/DIST_PATH"
  timed web "$web_started" "" "$finished"
  step "web: done -> $dist"
}

check_android() {
  require_url
  # HovelAndroid.kt opens HTTPS only; any other URL silently starts the legacy WorldWinner page instead.
  [[ "$url" =~ ^[Hh][Tt][Tt][Pp][Ss]:// ]] \
    || fail "android: the release app opens HTTPS pages only, so set GAMERO_WEB_URL to one (it is '$url')"
  if [[ -z "${GAMERO_ANDROID_KEYSTORE:-}" ]]; then
    step "android: GAMERO_ANDROID_KEYSTORE is not set, so the AAB and APK are unsigned"
  else
    [[ -f "$GAMERO_ANDROID_KEYSTORE" ]] || fail "GAMERO_ANDROID_KEYSTORE $GAMERO_ANDROID_KEYSTORE does not exist"
    for name in GAMERO_ANDROID_KEYSTORE_PASSWORD GAMERO_ANDROID_KEY_ALIAS GAMERO_ANDROID_KEY_PASSWORD; do
      [[ -n "${!name:-}" ]] || fail "$name is required with GAMERO_ANDROID_KEYSTORE"
    done
  fi
}

collect_android() {
  rm -rf "$out/android" && mkdir -p "$out/android"
  find "$root/composeApp/build/outputs/bundle/release" "$root/composeApp/build/outputs/apk/release" \
    -maxdepth 1 \( -name '*.aab' -o -name '*.apk' \) -exec cp {} "$out/android/" \;
  [[ -n "$(ls "$out/android")" ]] || fail "android: Gradle produced no AAB or APK"
  if [[ -f "$root/composeApp/build/outputs/mapping/release/mapping.txt" ]]; then
    cp "$root/composeApp/build/outputs/mapping/release/mapping.txt" "$out/android/"
  fi
  step "android: done -> $out/android"
}

check_desktop() {
  require_url
  # Signing and notarization are macOS-only and fail late, inside Gradle, when half-configured.
  if [[ -n "${GAMERO_MAC_SIGN_IDENTITY:-}" || -n "${GAMERO_NOTARIZE_APPLE_ID:-}" ]]; then
    [[ "$(uname -s)" == Darwin ]] || fail "GAMERO_MAC_SIGN_IDENTITY and GAMERO_NOTARIZE_APPLE_ID apply to macOS builds only; unset them on $(uname -s)"
  fi
  if [[ -n "${GAMERO_NOTARIZE_APPLE_ID:-}" ]]; then
    for name in GAMERO_MAC_SIGN_IDENTITY GAMERO_NOTARIZE_PASSWORD GAMERO_NOTARIZE_TEAM_ID; do
      [[ -n "${!name:-}" ]] || fail "$name is required with GAMERO_NOTARIZE_APPLE_ID (notarization needs a signed DMG)"
    done
  fi
  local jbr="${GAMERO_BROWSER_JAVA_HOME:-}"
  [[ -n "$jbr" ]] || fail "set GAMERO_BROWSER_JAVA_HOME to a JetBrains Runtime SDK with JCEF; it is bundled into the app (docs/release.md)"
  for tool in java jlink jpackage; do
    [[ -x "$jbr/bin/$tool" ]] || fail "$jbr/bin/$tool is missing. Packaging needs a JBR SDK (jbrsdk_jcef), not the runtime-only JBR that ships inside IntelliJ."
  done
  # ProGuard reads the JDK classes from jmods/; runtime-only JBRs (IntelliJ, Android Studio) lack it.
  [[ -d "$jbr/jmods" ]] || fail "$jbr has no jmods directory, which ProGuard needs. Use a JBR SDK (jbrsdk_jcef)."
  "$jbr/bin/java" --list-modules 2>/dev/null | grep -qE '^jcef(@|$)' || fail "$jbr has no jcef module; use a JBR SDK with JCEF"
}

collect_desktop() {
  rm -rf "$out/desktop" && mkdir -p "$out/desktop"
  find "$root/composeApp/build/compose/binaries/main-release" -mindepth 2 -maxdepth 2 \
    \( -name '*.dmg' -o -name '*.msi' -o -name '*.deb' -o -name '*.pkg' \) -exec cp {} "$out/desktop/" \;
  [[ -n "$(ls "$out/desktop")" ]] || fail "desktop: Gradle produced no installer"
  step "desktop: done -> $out/desktop"
}

# Android and desktop in one Gradle run: one configuration, one daemon warm-up, and
# the shared Kotlin compilation done once.
build_gradle() {
  local tasks=() flags=() names=()
  if want android; then
    flags+=(-Pgamero.mobileTargets=true)
    tasks+=(:composeApp:bundleRelease :composeApp:assembleRelease)
    names+=(android)
  fi
  if want desktop; then
    tasks+=(:composeApp:packageReleaseDistributionForCurrentOS)
    [[ -z "${GAMERO_NOTARIZE_APPLE_ID:-}" ]] || tasks+=(:composeApp:notarizeReleaseDmg)
    names+=(desktop)
  fi
  local t=$SECONDS label
  label="$(IFS=+; echo "${names[*]}")"
  step "$label: one Gradle run ($product, $url, $version/$build_number)"
  # --profile writes a per-task HTML report; its rows go into the timings CSV below.
  rm -rf "$root/build/reports/profile"
  if want desktop; then
    GAMERO_BROWSER_JAVA_HOME="$GAMERO_BROWSER_JAVA_HOME" gradle --profile "${flags[@]}" "${tasks[@]}"
  else
    gradle --profile "${flags[@]}" "${tasks[@]}"
  fi
  ! want android || collect_android
  ! want desktop || collect_desktop
  local report
  report="$(ls -t "$root"/build/reports/profile/*.html 2>/dev/null | head -1 || true)"
  if [[ -n "$report" ]]; then
    mkdir -p "$out/reports" && cp "$report" "$out/reports/gradle-profile.html"
    python3 "$root/tools/gradle-profile-steps.py" "$report" 1 2>/dev/null \
      | while IFS=, read -r task secs; do record "gradle$task" "${secs%.*}" "${secs}s; $label run"; done || true
  fi
  timed "$label" "$t" "${report:+per-task report: build/release/reports/gradle-profile.html}"
}

check_ios() {
  require_url
  [[ "$(uname -s)" == Darwin ]] || fail "ios builds need a Mac with Xcode"
  if ! xcodebuild -version >/dev/null 2>&1 && [[ -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  fi
  xcodebuild -version >/dev/null 2>&1 || fail "Xcode not found. Install it, or set DEVELOPER_DIR."
  # Both go into ExportOptions.plist, so check them here rather than at export time.
  local team="${GAMERO_IOS_TEAM_ID:-}" method="${GAMERO_IOS_EXPORT_METHOD:-app-store-connect}"
  [[ -z "$team" || "$team" =~ ^[A-Z0-9]{10}$ ]] \
    || fail "GAMERO_IOS_TEAM_ID must be a 10-character Apple team ID such as ABCDE12345, not '$team'"
  case "$method" in
    app-store-connect | release-testing | enterprise) ;;
    *) fail "GAMERO_IOS_EXPORT_METHOD must be app-store-connect, release-testing or enterprise, not '$method'" ;;
  esac
}

build_ios() {
  local t=$SECONDS

  local team="${GAMERO_IOS_TEAM_ID:-}" archive="$out/ios/Gamero.xcarchive"
  local signing=(DEVELOPMENT_TEAM="$team")
  [[ -n "$team" ]] || signing=(CODE_SIGNING_ALLOWED=NO)
  rm -rf "$out/ios" && mkdir -p "$out/ios"
  step "ios: archiving Release ($url, $version/$build_number${team:+, team $team}; log: $out/ios/xcodebuild.log)"
  # Command-line settings override Config.xcconfig, including its Release GAMERO_WEB_URL.
  # DEVELOPMENT_TEAM rather than TEAM_ID, which Config.xcconfig also appends to the bundle ID.
  xcodebuild -project "$root/iosApp/iosApp.xcodeproj" -scheme iosApp -configuration Release \
    -destination 'generic/platform=iOS' -archivePath "$archive" \
    GAMERO_WEB_URL="$url" MARKETING_VERSION="$version" CURRENT_PROJECT_VERSION="$build_number" \
    COMPILER_INDEX_STORE_ENABLE=NO "${signing[@]}" ${team:+-allowProvisioningUpdates} \
    -resultBundlePath "$out/ios/Gamero.xcresult" archive \
    >"$out/ios/xcodebuild.log" 2>&1 || { grep -E "error:" "$out/ios/xcodebuild.log" | head -20 >&2; fail "ios: archive failed"; }

  # Per-phase task time, the same totals as Xcode's build timing summary, which `archive`
  # does not print. The Kotlin framework is the PhaseScriptExecution phase.
  xcrun xcresulttool get log --path "$out/ios/Gamero.xcresult" --type build --compact 2>/dev/null \
    | python3 "$root/tools/xcresult-steps.py" 1 \
    | while IFS=, read -r phase secs count; do record "ios:$phase" "${secs%.*}" "${secs}s; $count tasks"; done || true

  if [[ -z "$team" ]]; then
    step "ios: GAMERO_IOS_TEAM_ID is not set, so $archive is unsigned and no IPA was exported"
    timed ios "$t"
    return
  fi
  cat >"$out/ios/ExportOptions.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key><string>${GAMERO_IOS_EXPORT_METHOD:-app-store-connect}</string>
  <key>teamID</key><string>$team</string>
  <key>signingStyle</key><string>automatic</string>
  <key>stripSwiftSymbols</key><true/>
  <key>uploadSymbols</key><true/>
</dict>
</plist>
EOF
  step "ios: exporting IPA (${GAMERO_IOS_EXPORT_METHOD:-app-store-connect})"
  xcodebuild -exportArchive -archivePath "$archive" -exportPath "$out/ios" \
    -exportOptionsPlist "$out/ios/ExportOptions.plist" -allowProvisioningUpdates \
    >>"$out/ios/xcodebuild.log" 2>&1 || { grep -E "error:" "$out/ios/xcodebuild.log" | tail -20 >&2; fail "ios: export failed"; }
  timed ios "$t"
  step "ios: done -> $out/ios"
}

# Check every platform's settings first, so a missing variable fails in seconds, not after a build.
! want android || check_android
! want desktop || check_desktop
! want ios || check_ios

# Every process below pid, children first in the list after their parent.
descendants() {
  local child
  for child in $(pgrep -P "$1" 2>/dev/null); do
    echo "$child"
    descendants "$child"
  done
}
# If anything below fails, stop a web build still running in the background: the
# subshell and everything it started (build-web.sh, Godot, tsc, Vite). The tree is
# listed before any kill, since a killed parent's children are reparented.
stop_web() {
  [[ -n "$web_pid" ]] || return 0
  # shellcheck disable=SC2046 # one pid per word
  kill "$web_pid" $(descendants "$web_pid") 2>/dev/null || true
}
trap stop_web EXIT

! want web || start_web
if want android || want desktop; then build_gradle; fi
! want ios || build_ios
[[ -z "$web_pid" ]] || finish_web

timed total "$started" "${platforms[*]}"
step "all done: ${platforms[*]}"
printf 'build-release:   %s\n' "${timings[@]}"
step "timings appended to $timings_file (compare runs: tools/build-timings.sh)"
