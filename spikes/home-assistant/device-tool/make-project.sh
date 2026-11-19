#!/bin/sh
# Make a scratch Hovel project folder whose Claude Code work turns load the hovel-devices spike tool.
# Usage: ./make-project.sh <folder>
set -eu

if [ $# -ne 1 ]; then
  echo "usage: $0 <project folder>" >&2
  exit 64
fi

here=$(cd "$(dirname "$0")" && pwd)
project=$1
mkdir -p "$project/.claude"
project=$(cd "$project" && pwd)
python=$(command -v python3)

cp "$here/devices.json" "$project/devices.json"
: > "$project/activity.jsonl"

# Project MCP server: Claude Code reads .mcp.json in the working folder.
cat > "$project/.mcp.json" <<EOF
{
  "mcpServers": {
    "hovel-devices": {
      "command": "$python",
      "args": ["$here/hovel_devices.py", "--devices", "$project/devices.json", "--activity", "$project/activity.jsonl"]
    }
  }
}
EOF

# Use = allowed up front. Use, ask first = not allowed, so Claude Code asks, and Hovel opens an approval.
cat > "$project/.claude/settings.json" <<EOF
{
  "enabledMcpjsonServers": ["hovel-devices"],
  "permissions": {
    "allow": ["mcp__hovel-devices__devices_list", "mcp__hovel-devices__device_use"]
  }
}
EOF

echo "Project ready: $project"
echo
echo "For the Codex run, add this to ~/.codex/config.toml, and take it out after the spike:"
echo
echo "[mcp_servers.hovel-devices]"
echo "command = \"$python\""
echo "args = [\"$here/hovel_devices.py\", \"--devices\", \"$project/devices.json\", \"--activity\", \"$project/activity.jsonl\"]"
