"""Print one "task,seconds" line per task in a Gradle --profile HTML report.

Usage: python3 tools/gradle-profile-steps.py <profile.html> [min_seconds]

tools/build-release.sh records these rows in its timings CSV so per-task time can be
compared across runs. Prints nothing when the report's layout is not recognised.
"""
import html
import re
import sys

DURATION = re.compile(r"^(?:(\d+)h)?(?:(\d+)m)?(?:([\d.]+)s)?$")


def seconds(text):
    match = DURATION.match(text.strip())
    if not match or not any(match.groups()):
        return None
    hours, minutes, secs = match.groups()
    return int(hours or 0) * 3600 + int(minutes or 0) * 60 + float(secs or 0)


def main():
    page = open(sys.argv[1], encoding="utf-8", errors="replace").read()
    floor = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
    # Task rows look like <tr><td>:composeApp:minifyReleaseWithR8</td><td class="numeric">1m2.345s</td>...
    for path, duration in re.findall(r"<td>(:[^<]+)</td>\s*<td class=\"numeric\">([^<]+)</td>", page):
        value = seconds(duration)
        if value is not None and value >= floor:
            print(f"{html.unescape(path)},{value:.1f}")


if __name__ == "__main__":
    try:
        main()
    except Exception:  # Timing is best effort; never fail the build over it.
        pass
