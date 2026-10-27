#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")" && pwd)"
source "$ROOT/scripts/dev-env.sh"
cd "$ROOT"
exec ./gradlew :composeApp:runCompanionGlassPreview --console=plain
