#!/usr/bin/env bash
# Run the PocketTTS timing harness. Pass --play to listen with afplay (macOS)
# or aplay/paplay/ffplay when one of those exists.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"
. "$ROOT/script-lib.sh"

SHERPA_VERSION=2.0.0
CONF="$ROOT/config/harness.conf"
JVM_JAR="$ROOT/deps/sherpa/sherpa-onnx-jvm-${SHERPA_VERSION}.jar"
KOTLINC="$ROOT/deps/kotlin/kotlinc/bin/kotlinc"
STDLIB="$ROOT/deps/kotlin/kotlinc/lib/kotlin-stdlib.jar"
MAIN_CLASS=EmberVoiceSpikeKt

if [ ! -f "$JVM_JAR" ] || [ ! -x "$KOTLINC" ]; then
  die "dependencies are missing. From this directory run ./setup.sh"
fi
require_java

plat=$(platform_id)
cpu=$(arch_id)
NATIVE_JAR="$ROOT/deps/sherpa/sherpa-onnx-native-lib-${plat}-${cpu}-${SHERPA_VERSION}.jar"
if [ ! -f "$NATIVE_JAR" ]; then
  die "native library for ${plat}-${cpu} is missing. Run ./setup.sh on this machine."
fi
if [ ! -f "$STDLIB" ]; then
  die "kotlin-stdlib is missing at $STDLIB. Run ./setup.sh again."
fi

src="$ROOT/src/EmberVoiceSpike.kt"
out="$ROOT/build/classes/${MAIN_CLASS}.class"
if [ ! -f "$out" ] || [ "$src" -nt "$out" ]; then
  printf 'compiling harness\n'
  mkdir -p "$ROOT/build/classes"
  "$KOTLINC" -jvm-target 17 -cp "$JVM_JAR" -d "$ROOT/build/classes" "$src"
fi

CP="$ROOT/build/classes:$STDLIB:$JVM_JAR:$NATIVE_JAR"
# Heap is capped so RSS is mostly the ONNX model, not an idle JVM reservation.
JAVA_OPTS="-Xms128m -Xmx1024m"

self_check=0
for arg in "$@"; do
  if [ "$arg" = "--self-check" ]; then
    self_check=1
  fi
done

if [ "$self_check" -eq 1 ]; then
  exec java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
fi

mkdir -p "$ROOT/results"
time_file="$ROOT/results/time.txt"
rm -f "$time_file"

set +e
if [ -x /usr/bin/time ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    # BSD time. -l reports maximum resident set size in bytes.
    # shellcheck disable=SC2086
    /usr/bin/time -l -o "$time_file" java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
    status=$?
  else
    # GNU time. -v reports maximum resident set size in kilobytes.
    # shellcheck disable=SC2086
    /usr/bin/time -v -o "$time_file" java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
    status=$?
  fi
else
  printf 'note: /usr/bin/time is not installed. Peak RSS is the in-process sample only.\n' >&2
  # shellcheck disable=SC2086
  java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
  status=$?
fi
set -e

json_file="$ROOT/results/ember-voice-results.json"
if [ -f "$time_file" ] && [ -f "$json_file" ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    raw=$(awk '/maximum resident set size/ {print $1; exit}' "$time_file")
    case "$raw" in
      ''|*[!0-9]*) kb="" ;;
      *) kb=$((raw / 1024)) ;;
    esac
  else
    kb=$(awk '/Maximum resident set size/ {print $NF; exit}' "$time_file")
    case "$kb" in
      ''|*[!0-9]*) kb="" ;;
    esac
  fi
  if [ -n "$kb" ]; then
    tmp="${json_file}.tmp"
    awk -v kb="$kb" '
      !replaced && index($0, "\"external_peak_rss_kb\": null") {
        sub(/null/, kb)
        replaced = 1
      }
      { print }
    ' "$json_file" > "$tmp"
    mv "$tmp" "$json_file"
    awk -v kb="$kb" 'BEGIN { printf "peak RSS from /usr/bin/time: %s KiB (%.1f MiB)\n", kb, kb / 1024 }'
  else
    printf 'note: could not parse peak RSS from %s\n' "$time_file" >&2
  fi
fi

exit "$status"
