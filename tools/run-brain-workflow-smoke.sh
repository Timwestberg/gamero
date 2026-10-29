#!/bin/bash
# Opt-in project workflow with real providers and the production native approval UI.
# Requires an existing scripts/package-catalog-macos.sh app image. Uses only a new temp project.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -z "${HOVEL_BRAIN_VERIFY_ROOT:-}" ]]; then
  : "${HOVEL_BRAIN_WORKFLOW_EXECUTABLE:?Set the installed provider executable}"
fi
cd "$ROOT"
./gradlew :composeApp:jvmTestClasses :composeApp:prepareCatalogLauncherApp --console=plain
BASE="$ROOT/build/catalog-launcher/Gamero Adventures.app"
APP="$ROOT/build/brain-workflow-smoke/Gamero Adventures.app"
[[ -d "$BASE" ]] || { echo 'Build the catalog app image first.' >&2; exit 1; }
if [[ ! -d "$APP" ]]; then
  mkdir -p "$(dirname "$APP")"
  cp -cR "$BASE" "$APP"
fi
# This generated app is separate from the normal development launcher.
find "$APP/Contents/app" -maxdepth 1 -name '*.jar' -delete
cp "$ROOT/composeApp/build/catalog-input/"*.jar "$APP/Contents/app/"
jar --update --file "$APP/Contents/app/gamero-catalog.jar" \
  --main-class org.formulagen.wrapper.brain.BrainWorkflowSmokeMainKt \
  -C "$ROOT/composeApp/build/classes/kotlin/jvm/test" .
exec "$APP/Contents/MacOS/Gamero Adventures"
