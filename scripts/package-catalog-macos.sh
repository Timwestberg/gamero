#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
: "${GAMERO_BROWSER_JAVA_HOME:?Set the installed JBR/JCEF runtime}"
[[ -f "$GAMERO_BROWSER_JAVA_HOME/lib/libjcef.dylib" ]] || { echo 'This runtime has no JCEF.' >&2; exit 1; }
cd "$ROOT"
./gradlew :composeApp:prepareCatalogLauncherApp --console=plain
APP="$ROOT/build/catalog-launcher/Gamero Adventures.app"
if [[ -d "$APP/Contents/app" ]]; then
  cp "$ROOT/composeApp/build/catalog-input/"*.jar "$APP/Contents/app/"
else
  mkdir -p "$ROOT/build/catalog-launcher"
  "$JAVA_HOME/bin/jpackage" --type app-image --name 'Gamero Adventures' --app-version 1.0.0 \
    --input "$ROOT/composeApp/build/catalog-input" --main-jar gamero-catalog.jar \
    --dest "$ROOT/build/catalog-launcher" --runtime-image "$GAMERO_BROWSER_JAVA_HOME" \
    --mac-package-identifier com.gamero.adventures.prototype \
    --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
    --java-options '--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED' \
    --java-options '--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED' \
    --java-options '--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED'
fi
if [[ ! -d "$APP/Contents/runtime/Contents/Frameworks" ]]; then
  # JBR stores signed Chromium frameworks beside Home, outside jpackage's copy.
  ditto "$GAMERO_BROWSER_JAVA_HOME/../Frameworks" "$APP/Contents/runtime/Contents/Frameworks"
fi
