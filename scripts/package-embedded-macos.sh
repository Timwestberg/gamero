#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
export JAVA_HOME="$JDK"
cd "$ROOT"
bash scripts/build-embedded-macos.sh
./gradlew :composeApp:prepareEmbeddedCompanionApp --console=plain
STAMP="$ROOT/build/embedded-godot/latest-app.txt"
if [[ -f "$STAMP" ]]; then
  APP="$(cat "$STAMP")"
  if [[ "$APP" == "$ROOT/build/embedded-godot/"* && -d "$APP/Contents/app" ]]; then
    # Refresh a development bundle without copying the entire JDK every launch.
    cp "$ROOT/composeApp/build/embedded-input/"*.jar "$APP/Contents/app/"
    exit 0
  fi
fi
DEST="$(mktemp -d "$ROOT/build/embedded-godot/app.XXXXXX")"
"$JDK/bin/jpackage" --type app-image --name 'Hovel Embedded' --app-version 1.0.0 \
  --input "$ROOT/composeApp/build/embedded-input" --main-jar hovel-embedded.jar \
  --dest "$DEST" --runtime-image "$JDK" --mac-package-identifier com.hovel.embedded.prototype \
  --java-options '--add-opens=java.desktop/sun.awt=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED' \
  --java-options '--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED'
printf '%s\n' "$DEST/Hovel Embedded.app" > "$STAMP"
