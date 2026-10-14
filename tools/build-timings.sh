#!/usr/bin/env bash
# Compare step durations across recent release builds.
#
# Usage: tools/build-timings.sh [runs] [csv]
#   runs  how many of the most recent runs to show (default 5)
#   csv   timings file (default: BUILD_TIMINGS_FILE, then .build-timings.csv at the repo root)
#
# One row per step, one column per run (start time UTC and commit), newest on the
# right, in seconds. A blank
# cell means the step did not run. "best" is the fastest time seen for that step
# in the whole file. Rows come from tools/build-release.sh and game-platform's
# scripts/build-web.sh.
set -euo pipefail

root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
runs="${1:-5}"
file="${2:-${BUILD_TIMINGS_FILE:-$root/.build-timings.csv}}"
[[ "$runs" =~ ^[1-9][0-9]*$ ]] || { echo "usage: tools/build-timings.sh [runs] [csv]" >&2; exit 2; }
[[ -s "$file" ]] || { echo "build-timings: no timings in $file yet. Run tools/build-release.sh first." >&2; exit 1; }

awk -F, -v want="$runs" '
  NR == 1 { next }
  {
    key = $3 ":" $4
    if (!($1 in seen)) { seen[$1] = 1; order[++n] = $1 }
    # The header shows gamero'"'"'s commit; build-web rows carry game-platform'"'"'s.
    if ($3 == "build-release" || !($1 in commit)) commit[$1] = $7
    if (!(key in known)) { known[key] = 1; keys[++k] = key }
    secs[$1, key] = $5
    if (!(key in best) || $5 + 0 < best[key] + 0) best[key] = $5
  }
  END {
    first = n - want + 1; if (first < 1) first = 1
    # Header: when each run started (UTC month-day hour-minute), then its commit.
    printf "%-56s", "step"
    for (i = first; i <= n; i++) printf " %10s", substr(order[i], 5, 4) "-" substr(order[i], 10, 4)
    printf " %8s\n%-56s", "best", ""
    for (i = first; i <= n; i++) printf " %10s", commit[order[i]]
    printf "\n"
    for (j = 1; j <= k; j++) {
      shown = 0
      for (i = first; i <= n; i++) if ((order[i], keys[j]) in secs) shown = 1
      if (!shown) continue
      printf "%-56s", keys[j]
      for (i = first; i <= n; i++) {
        cell = ((order[i], keys[j]) in secs) ? secs[order[i], keys[j]] "s" : ""
        printf " %10s", cell
      }
      printf " %7ss\n", best[keys[j]]
    }
  }' "$file"
