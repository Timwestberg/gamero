#!/usr/bin/env bash
# Diff two benchmark JSON files. Exits 1 on a significant TTFA regression.
set -eu
ROOT=$(cd "$(dirname "$0")" && pwd)
if [ "$#" -ne 2 ]; then
  printf 'usage: ./compare.sh baseline.json candidate.json\n' >&2
  exit 2
fi
exec "$ROOT/run.sh" --compare "$1" "$2"
