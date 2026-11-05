#!/bin/sh
# Print the NAT address that macOS gave the spike VM, from its DHCP leases.
set -eu

here=$(cd "$(dirname "$0")" && pwd)
mac_file="$here/work/state/mac-address"
[ -f "$mac_file" ] || { echo "No MAC address yet. Start the VM with ./run.sh first." >&2; exit 66; }

python3 - "$(cat "$mac_file")" <<'EOF'
import re
import sys

# /var/db/dhcpd_leases writes each octet without leading zeros, e.g. 1,6a:0:3:...
wanted = ":".join(part.lstrip("0") or "0" for part in sys.argv[1].lower().split(":"))
try:
    leases = open("/var/db/dhcpd_leases").read()
except FileNotFoundError:
    sys.exit("No DHCP leases yet. Wait for the VM to boot, then try again.")
for block in re.findall(r"\{(.*?)\}", leases, re.S):
    fields = dict(re.findall(r"(\w+)=(\S+)", block))
    if fields.get("hw_address", "").split(",")[-1] == wanted:
        print(fields.get("ip_address"))
        sys.exit(0)
sys.exit(f"No lease for {wanted} yet. Wait for the VM to boot, then try again.")
EOF
