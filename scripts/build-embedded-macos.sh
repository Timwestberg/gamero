#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
source "$ROOT/scripts/dev-env.sh"
mkdir -p "$(dirname "$HOVEL_EMBED_LIBRARY")"
# JAWT_GetAWT is left for the loader to resolve, not linked to $JAVA_HOME's libjawt.
# The catalog app runs on GAMERO_BROWSER_JAVA_HOME's JBR and the embedded app on
# JAVA_HOME's JDK; MacGameLayer loads the running runtime's jawt before this
# library, so the symbol always binds to the AWT that owns the window. Every other
# undefined symbol still fails the link.
clang -dynamiclib -fobjc-arc -framework Cocoa -framework QuartzCore \
  -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/darwin" -Wl,-U,_JAWT_GetAWT \
  "$ROOT/native/macos/embedded_game.m" -o "$HOVEL_EMBED_LIBRARY"
