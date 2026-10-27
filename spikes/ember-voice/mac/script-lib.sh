# Shared helpers for setup.sh and run.sh.
# Bash 3.2 compatible: no associative arrays, no GNU-only flags.
# Callers set ROOT and `set -eu` before sourcing this file.

die() {
  printf 'error: %s\n' "$1" >&2
  exit 1
}

sha256_file() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | awk '{print $1}'
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | awk '{print $1}'
  else
    die "need sha256sum or shasum to verify downloads"
  fi
}

# Last matching key in a harness.conf file. Values keep spaces and '='.
conf_get() {
  key=$1
  file=$2
  awk -v k="$key" '
    {
      sub(/\r$/, "")
      if ($0 ~ /^[ \t]*#/ || $0 ~ /^[ \t]*$/) {
        next
      }
      eq = index($0, "=")
      if (eq == 0) {
        next
      }
      key = substr($0, 1, eq - 1)
      gsub(/^[ \t]+|[ \t]+$/, "", key)
      if (key == k) {
        found = substr($0, eq + 1)
      }
    }
    END {
      if (found != "") {
        print found
      }
    }
  ' "$file"
}

java_major() {
  ver=$(java -version 2>&1 | head -n 1)
  num=$(printf '%s\n' "$ver" | sed -n 's/.*"\([0-9][0-9]*\)\..*/\1/p')
  if [ -z "$num" ]; then
    num=$(printf '%s\n' "$ver" | sed -n 's/.*"\([0-9][0-9]*\)".*/\1/p')
  fi
  if [ "$num" = "1" ]; then
    num=$(printf '%s\n' "$ver" | sed -n 's/.*"1\.\([0-9][0-9]*\).*/\1/p')
  fi
  printf '%s\n' "$num"
}

require_java() {
  if ! command -v java >/dev/null 2>&1; then
    die "Java 17 or newer is required. On a Mac: brew install openjdk@21, then export PATH=\"\$(brew --prefix openjdk@21)/bin:\$PATH\""
  fi
  major=$(java_major)
  if [ -z "$major" ]; then
    die "could not read the Java version from: $(java -version 2>&1 | head -n 1)"
  fi
  if [ "$major" -lt 17 ]; then
    die "Java 17 or newer is required (found major version $major). On a Mac: brew install openjdk@21"
  fi
}

platform_id() {
  os=$(uname -s)
  case "$os" in
    Darwin) printf 'osx\n' ;;
    Linux) printf 'linux\n' ;;
    *) die "unsupported OS $os (need macOS or Linux)" ;;
  esac
}

arch_id() {
  machine=$(uname -m)
  case "$machine" in
    arm64|aarch64) printf 'aarch64\n' ;;
    x86_64|amd64) printf 'x64\n' ;;
    *) die "unsupported CPU $machine (need arm64 or x86_64)" ;;
  esac
}
