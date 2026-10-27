#!/usr/bin/env bash
# Run the Ember voice benchmark. --self-check and --compare skip /usr/bin/time.
set -eu

ROOT=$(cd "$(dirname "$0")" && pwd)
cd "$ROOT"
. "$ROOT/../mac/script-lib.sh"

SHERPA_VERSION=2.0.0
JVM_JAR="$ROOT/../mac/deps/sherpa/sherpa-onnx-jvm-${SHERPA_VERSION}.jar"
KOTLINC="$ROOT/../mac/deps/kotlin/kotlinc/bin/kotlinc"
STDLIB="$ROOT/../mac/deps/kotlin/kotlinc/lib/kotlin-stdlib.jar"
MAIN_CLASS=ember.voice.bench.BenchKt

if [ ! -f "$JVM_JAR" ] || [ ! -x "$KOTLINC" ]; then
  die "dependencies are missing. From this directory run ./setup.sh"
fi
require_java

plat=$(platform_id)
cpu=$(arch_id)
NATIVE_JAR="$ROOT/../mac/deps/sherpa/sherpa-onnx-native-lib-${plat}-${cpu}-${SHERPA_VERSION}.jar"
if [ ! -f "$NATIVE_JAR" ]; then
  die "native library for ${plat}-${cpu} is missing. Run ./setup.sh on this machine."
fi

CLIP="$ROOT/../../../voice/src/commonMain/kotlin/org/formulagen/wrapper/voice/ReferenceClip.kt"
if [ ! -f "$CLIP" ]; then
  die "missing $CLIP"
fi
out_class="$ROOT/build/classes/ember/voice/bench/BenchKt.class"
newest_src="$out_class"
for src in "$ROOT"/src/*.kt "$CLIP"; do
  if [ ! -f "$out_class" ] || [ "$src" -nt "$newest_src" ]; then
    newest_src="$src"
  fi
done
if [ ! -f "$out_class" ] || [ "$newest_src" != "$out_class" ]; then
  printf 'compiling harness\n'
  mkdir -p "$ROOT/build/classes"
  # shellcheck disable=SC2086
  "$KOTLINC" -jvm-target 17 -cp "$JVM_JAR" -d "$ROOT/build/classes" "$ROOT"/src/*.kt "$CLIP"
fi

CP="$ROOT/build/classes:$STDLIB:$JVM_JAR:$NATIVE_JAR"
JAVA_OPTS="-Xms128m -Xmx1024m"

commit=$(git -C "$ROOT" rev-parse HEAD 2>/dev/null || printf 'unknown')
set -- --git-commit "$commit" "$@"

skip_time=0
for arg in "$@"; do
  if [ "$arg" = "--self-check" ] || [ "$arg" = "--compare" ] || [ "$arg" = "--help" ]; then
    skip_time=1
  fi
done

if [ "$skip_time" -eq 1 ]; then
  # shellcheck disable=SC2086
  exec java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
fi

mkdir -p "$ROOT/results"
output="results/ember-voice-bench.json"
matrix=0
compare_voices=0
prev=""
for arg in "$@"; do
  if [ "$prev" = "--output" ]; then
    output=$arg
  fi
  if [ "$arg" = "--matrix" ]; then
    matrix=1
  fi
  if [ "$arg" = "--compare-voices" ]; then
    compare_voices=1
  fi
  prev=$arg
done
if [ "$compare_voices" -eq 1 ] && [ "$output" = "results/ember-voice-bench.json" ]; then
  output="results/voice-compare/summary.json"
elif [ "$matrix" -eq 1 ] && [ "$output" = "results/ember-voice-bench.json" ]; then
  output="results/matrix-summary.json"
fi

time_file="$ROOT/results/time.txt"
rm -f "$time_file"
set +e
if [ -x /usr/bin/time ]; then
  if [ "$(uname -s)" = "Darwin" ]; then
    # shellcheck disable=SC2086
    /usr/bin/time -l -o "$time_file" java $JAVA_OPTS -cp "$CP" "$MAIN_CLASS" "$@"
    status=$?
  else
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

if [ -f "$time_file" ] && [ -f "$ROOT/$output" ]; then
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
  if [ -n "${kb:-}" ]; then
    tmp="${ROOT}/${output}.tmp"
    awk -v kb="$kb" '
      !replaced && index($0, "\"external_peak_rss_kb\": null") {
        sub(/null/, kb)
        replaced = 1
      }
      { print }
    ' "$ROOT/$output" > "$tmp"
    mv "$tmp" "$ROOT/$output"
    awk -v kb="$kb" 'BEGIN { printf "peak RSS from /usr/bin/time: %s KiB (%.1f MiB)\n", kb, kb / 1024 }'
  fi
fi

exit "$status"
