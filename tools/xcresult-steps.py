"""Print one "phase,seconds,tasks" line per Xcode build phase in a result bundle's build log.

Usage: xcrun xcresulttool get log --path <bundle.xcresult> --type build --compact \
         | python3 tools/xcresult-steps.py [min_seconds]

xcodebuild prints its -showBuildTimingSummary for `build` but not for `archive`, so
tools/build-release.sh reads the same totals from the archive's result bundle: task
durations summed per build rule (CompileC, SwiftCompile, PhaseScriptExecution, ...),
over tasks that run in parallel. Prints nothing when the log's layout is not recognised.
"""
import json
import sys
from collections import defaultdict


def main():
    floor = float(sys.argv[1]) if len(sys.argv) > 1 else 0.0
    log = json.load(sys.stdin)
    seconds = defaultdict(float)
    tasks = defaultdict(int)
    for task in log.get("subsections", []):
        # commandDetails is the task's command line; its first word is the build rule.
        command = (task.get("commandInvocationDetails") or {}).get("commandDetails") or ""
        rule = command.split(maxsplit=1)[0] if command.strip() else ""
        if rule and isinstance(task.get("duration"), (int, float)):
            seconds[rule] += task["duration"]
            tasks[rule] += 1
    for rule in sorted(seconds, key=seconds.get, reverse=True):
        if seconds[rule] >= floor:
            print(f"{rule},{seconds[rule]:.1f},{tasks[rule]}")


if __name__ == "__main__":
    try:
        main()
    except Exception:  # Timing is best effort; never fail the build over it.
        pass
