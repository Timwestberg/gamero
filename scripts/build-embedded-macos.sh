#!/bin/bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JDK="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
mkdir -p "$ROOT/build/embedded-godot"
clang -dynamiclib -fobjc-arc -framework Cocoa -framework QuartzCore \
  -I"$JDK/include" -I"$JDK/include/darwin" -L"$JDK/lib" -ljawt \
  -Wl,-rpath,"$JDK/lib" "$ROOT/native/macos/embedded_game.m" \
  -o "$ROOT/build/embedded-godot/libhovel_embed.dylib"
