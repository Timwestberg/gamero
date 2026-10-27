#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/dev-env.sh"
cd "$ROOT"
bash scripts/build-embedded-macos.sh
./gradlew :composeApp:prepareEmbeddedCompanionApp --console=plain
INPUT="$ROOT/composeApp/build/embedded-input"
STAMP="$ROOT/build/embedded-godot/latest-app.txt"
if [[ -f "$STAMP" ]]; then
  APP="$(cat "$STAMP")"
  if [[ "$APP" == "$ROOT/build/embedded-godot/"* && -d "$APP/Contents/app" ]]; then
    # Refresh a development bundle without copying the entire JDK every launch.
    # Remove the old jars first so a renamed dependency cannot stay on the classpath.
    find "$APP/Contents/app" -maxdepth 1 -name '*.jar' -delete
    cp "$INPUT/"*.jar "$APP/Contents/app/"
    exit 0
  fi
fi
load_desktop_java_options
DEST="$(mktemp -d "$ROOT/build/embedded-godot/app.XXXXXX")"
"$JAVA_HOME/bin/jpackage" --type app-image --name 'Hovel Embedded' --app-version 1.0.0 \
  --input "$INPUT" --main-jar hovel-embedded.jar \
  --dest "$DEST" --runtime-image "$JAVA_HOME" --mac-package-identifier com.hovel.embedded.prototype \
  "${JAVA_OPTIONS[@]}"
printf '%s\n' "$DEST/Hovel Embedded.app" > "$STAMP"
