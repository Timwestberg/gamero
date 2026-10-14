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
usage() { sed -n '2,10p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//' >&2; exit 2; }

[[ $# -gt 0 ]] || usage
platforms=()
for arg in "$@"; do
  case "$arg" in
    all) platforms+=(web android desktop ios) ;;
    android | desktop | ios | web) platforms+=("$arg") ;;
    -h | --help) usage ;;
    *) echo "build-release: unknown platform '$arg'" >&2; usage ;;
  esac
done

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

build_web() {
  local game_platform="${GAME_PLATFORM_DIR:-$root/../game-platform}"
  [[ -f "$game_platform/scripts/build-web.sh" ]] || fail "no scripts/build-web.sh in $game_platform. Check out game-platform there or set GAME_PLATFORM_DIR."
  game_platform="$(cd "$game_platform" && pwd)"
  step "web: building $game_platform"
  bash "$game_platform/scripts/build-web.sh"
  mkdir -p "$out/web"
  printf '%s\n' "$game_platform/apps/game-bridge-host/dist" >"$out/web/DIST_PATH"
  step "web: done -> $game_platform/apps/game-bridge-host/dist"
}

build_android() {
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
  step "android: bundleRelease and assembleRelease ($product, $url, $version/$build_number)"
  gradle -Pgamero.mobileTargets=true :composeApp:bundleRelease :composeApp:assembleRelease
  rm -rf "$out/android" && mkdir -p "$out/android"
  find "$root/composeApp/build/outputs/bundle/release" "$root/composeApp/build/outputs/apk/release" \
    -maxdepth 1 \( -name '*.aab' -o -name '*.apk' \) -exec cp {} "$out/android/" \;
  [[ -n "$(ls "$out/android")" ]] || fail "android: Gradle produced no AAB or APK"
  if [[ -f "$root/composeApp/build/outputs/mapping/release/mapping.txt" ]]; then
    cp "$root/composeApp/build/outputs/mapping/release/mapping.txt" "$out/android/"
  fi
  step "android: done -> $out/android"
}

build_desktop() {
  require_url
  local jbr="${GAMERO_BROWSER_JAVA_HOME:-}"
  [[ -n "$jbr" ]] || fail "set GAMERO_BROWSER_JAVA_HOME to a JetBrains Runtime SDK with JCEF; it is bundled into the app (docs/release.md)"
  for tool in java jlink jpackage; do
    [[ -x "$jbr/bin/$tool" ]] || fail "$jbr/bin/$tool is missing. Packaging needs a JBR SDK (jbrsdk_jcef), not the runtime-only JBR that ships inside IntelliJ."
  done
  # ProGuard reads the JDK classes from jmods/; runtime-only JBRs (IntelliJ, Android Studio) lack it.
  [[ -d "$jbr/jmods" ]] || fail "$jbr has no jmods directory, which ProGuard needs. Use a JBR SDK (jbrsdk_jcef)."
  "$jbr/bin/java" --list-modules 2>/dev/null | grep -qE '^jcef(@|$)' || fail "$jbr has no jcef module; use a JBR SDK with JCEF"

  local task=packageReleaseDistributionForCurrentOS
  [[ -z "${GAMERO_NOTARIZE_APPLE_ID:-}" ]] || task="$task notarizeReleaseDmg"
  step "desktop: $task ($product, $url, $version)"
  # shellcheck disable=SC2086
  GAMERO_BROWSER_JAVA_HOME="$jbr" gradle $(printf ':composeApp:%s ' $task)
  rm -rf "$out/desktop" && mkdir -p "$out/desktop"
  find "$root/composeApp/build/compose/binaries/main-release" -mindepth 2 -maxdepth 2 \
    \( -name '*.dmg' -o -name '*.msi' -o -name '*.deb' -o -name '*.pkg' \) -exec cp {} "$out/desktop/" \;
  [[ -n "$(ls "$out/desktop")" ]] || fail "desktop: Gradle produced no installer"
  step "desktop: done -> $out/desktop"
}

build_ios() {
  require_url
  [[ "$(uname -s)" == Darwin ]] || fail "ios builds need a Mac with Xcode"
  if ! xcodebuild -version >/dev/null 2>&1 && [[ -d /Applications/Xcode.app ]]; then
    export DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer
  fi
  xcodebuild -version >/dev/null 2>&1 || fail "Xcode not found. Install it, or set DEVELOPER_DIR."

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
    "${signing[@]}" ${team:+-allowProvisioningUpdates} archive \
    >"$out/ios/xcodebuild.log" 2>&1 || { grep -E "error:" "$out/ios/xcodebuild.log" | head -20 >&2; fail "ios: archive failed"; }

  if [[ -z "$team" ]]; then
    step "ios: GAMERO_IOS_TEAM_ID is not set, so $archive is unsigned and no IPA was exported"
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
  step "ios: done -> $out/ios"
}

for platform in "${platforms[@]}"; do
  "build_$platform"
done
step "all done: ${platforms[*]}"
