#!/usr/bin/env bash
# Compile the JVM voice module and run the logic checks.
# --speak also synthesizes one line when the spike model is already on disk.
set -eu
HERE=$(CDPATH= cd "$(dirname "$0")" && pwd)
ROOT=$(CDPATH= cd "$HERE/.." && pwd)
# shellcheck source=../spikes/ember-voice/mac/script-lib.sh
. "$ROOT/spikes/ember-voice/mac/script-lib.sh"
require_java
KOTLINC=${KOTLINC:-$ROOT/spikes/ember-voice/mac/deps/kotlin/kotlinc/bin/kotlinc}
STDLIB=${STDLIB:-$ROOT/spikes/ember-voice/mac/deps/kotlin/kotlinc/lib/kotlin-stdlib.jar}
if [ ! -x "$KOTLINC" ]; then
  die "kotlinc is missing. Run spikes/ember-voice/mac/setup.sh first."
fi
SHERPA="$ROOT/spikes/ember-voice/mac/deps/sherpa"
PLATFORM=$(platform_id)
ARCH=$(arch_id)
JVM_JAR="$SHERPA/sherpa-onnx-jvm-2.0.0.jar"
NATIVE_JAR="$SHERPA/sherpa-onnx-native-lib-${PLATFORM}-${ARCH}-2.0.0.jar"
if [ ! -f "$JVM_JAR" ] || [ ! -f "$NATIVE_JAR" ]; then
  die "sherpa-onnx jars are missing. Run spikes/ember-voice/mac/setup.sh from the repo root first."
fi
OUT="$HERE/build/jvm-check"
rm -rf "$OUT"
mkdir -p "$OUT"
# The flat kotlinc run is one module, so it cannot compile expect/actual.
# Those stay in the Gradle KMP target. This check calls SherpaPocketEngine directly.
SOURCES=$(find "$HERE/src/commonMain" "$HERE/src/sherpaMain" "$HERE/jvm-check" -name '*.kt' ! -name 'Engines.kt' | sort)
# shellcheck disable=SC2086
"$KOTLINC" -cp "$JVM_JAR:$NATIVE_JAR" -d "$OUT" $SOURCES
CP="$OUT:$JVM_JAR:$NATIVE_JAR:$STDLIB"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA="$JAVA_HOME/bin/java"
else
  JAVA=java
fi
exec "$JAVA" -cp "$CP" org.formulagen.wrapper.voice.CheckKt "$@"
