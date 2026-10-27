#!/bin/zsh
set -eu
cd "$(dirname "$0")"
if [[ -z "${JAVA_HOME:-}" && -d "$HOME/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home" ]]; then
  export JAVA_HOME="$HOME/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home"
fi
exec ./gradlew :composeApp:runCompanionGlassPreview --console=plain
