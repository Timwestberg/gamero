#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/dev-env.sh"
: "${GAMERO_BROWSER_JAVA_HOME:?Set the installed JBR/JCEF runtime}"
[[ -f "$GAMERO_BROWSER_JAVA_HOME/lib/libjcef.dylib" ]] || { echo 'This runtime has no JCEF.' >&2; exit 1; }
cd "$ROOT"
./gradlew :composeApp:prepareCatalogLauncherApp --console=plain
APP="$ROOT/build/catalog-launcher/Gamero Adventures.app"
INPUT="$ROOT/composeApp/build/catalog-input"
if [[ -d "$APP/Contents/app" ]]; then
  # Refresh only the jars. Remove the old set first: dependency jars carry an
  # index in their names, so a changed dependency would otherwise leave a stale
  # copy on the classpath. Delete the app to pick up changed JVM options.
  find "$APP/Contents/app" -maxdepth 1 -name '*.jar' -delete
  cp "$INPUT/"*.jar "$APP/Contents/app/"
else
  load_desktop_java_options
  mkdir -p "$ROOT/build/catalog-launcher"
  "$JAVA_HOME/bin/jpackage" --type app-image --name 'Gamero Adventures' --app-version 1.0.0 \
    --input "$INPUT" --main-jar gamero-catalog.jar \
    --dest "$ROOT/build/catalog-launcher" --runtime-image "$GAMERO_BROWSER_JAVA_HOME" \
    --mac-package-identifier com.gamero.adventures.prototype "${JAVA_OPTIONS[@]}"
fi
if [[ ! -d "$APP/Contents/runtime/Contents/Frameworks" ]]; then
  # JBR stores signed Chromium frameworks beside Home, outside jpackage's copy.
  ditto "$GAMERO_BROWSER_JAVA_HOME/../Frameworks" "$APP/Contents/runtime/Contents/Frameworks"
fi
PLIST="$APP/Contents/Info.plist"
if [[ -f "$PLIST" ]] && ! /usr/libexec/PlistBuddy -c 'Print :NSMicrophoneUsageDescription' "$PLIST" >/dev/null 2>&1; then
  /usr/libexec/PlistBuddy -c 'Add :NSMicrophoneUsageDescription string Ember hears you on this Mac to turn what you say into a chat message. The recording is not saved.' "$PLIST"
fi
AUDIO="$ROOT/spikes/ember-voice/mac/deps/ember-audio/libember_audio.dylib"
if [[ -f "$AUDIO" ]]; then
  mkdir -p "$APP/Contents/Frameworks"
  cp "$AUDIO" "$APP/Contents/Frameworks/libember_audio.dylib"
  codesign -s - --force "$APP/Contents/Frameworks/libember_audio.dylib"
fi
