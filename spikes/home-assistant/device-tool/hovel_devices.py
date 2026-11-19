#!/usr/bin/env python3
"""Spike 3: the `hovel-devices` MCP tool, with fake devices and the three access levels.

A stdio MCP server (newline-delimited JSON-RPC 2.0), standard library only. It holds
one companion's devices from a JSON file and changes only their fake state. It writes
one activity line for every call, so the spike can compare it with Hovel's activity rows.

Tools:
- devices_list:   the companion's devices, with state and access level.
- device_use:     act on a device at "use". The work turn allows this tool up front.
- device_request: act on a device at "use-ask-first". The work turn does not allow it,
                  so the runtime asks Hovel, and Hovel opens an approval.

    python3 hovel_devices.py --devices devices.json --activity activity.jsonl
"""

from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path
from typing import Any, TextIO

SERVER_NAME = "hovel-devices"
DEFAULT_PROTOCOL = "2025-06-18"
ACCESS_LEVELS = ("watch", "use-ask-first", "use")
ACTIONS = ("turn-on", "turn-off", "toggle", "set-brightness")
# Safety rule 6: these never accept a companion action, at any level.
WATCH_ONLY_DOMAINS = {"lock", "alarm_control_panel", "camera"}
WATCH_ONLY_COVER_CLASSES = {"garage", "gate"}
# Which actions each domain takes. Sensors take none.
DOMAIN_ACTIONS = {
    "light": set(ACTIONS),
    "switch": {"turn-on", "turn-off", "toggle"},
    "fan": {"turn-on", "turn-off", "toggle"},
}
TOOL_ACCESS = {"device_use": "use", "device_request": "use-ask-first"}

ACT_SCHEMA = {
    "type": "object",
    "properties": {
        "entity_id": {"type": "string", "description": "The device, from devices_list."},
        "action": {"type": "string", "enum": list(ACTIONS)},
        "brightness": {"type": "integer", "minimum": 0, "maximum": 100, "description": "Percent, for set-brightness."},
    },
    "required": ["entity_id", "action"],
    "additionalProperties": False,
}
TOOLS = [
    {
        "name": "devices_list",
        "description": "List the devices you look after, with their state and your access level.",
        "inputSchema": {"type": "object", "properties": {}, "additionalProperties": False},
    },
    {
        "name": "device_use",
        "description": "Act on a device whose access is 'use'. It acts at once.",
        "inputSchema": ACT_SCHEMA,
    },
    {
        "name": "device_request",
        "description": "Act on a device whose access is 'use-ask-first'. The person approves each call.",
        "inputSchema": ACT_SCHEMA,
    },
]


class ToolError(Exception):
    """A refusal the model reads as the tool's result."""


class Devices:
    """One companion's fake devices and the activity log."""

    def __init__(self, devices_file: Path, activity_file: Path) -> None:
        data = json.loads(devices_file.read_text())
        self.companion: str = data["companion"]
        self.devices: dict[str, dict[str, Any]] = {d["entity_id"]: d for d in data["devices"]}
        for device in self.devices.values():
            if device["access"] not in ACCESS_LEVELS:
                raise ValueError(f"{device['entity_id']}: access must be one of {ACCESS_LEVELS}")
        self.activity_file = activity_file

    @staticmethod
    def watch_only(device: dict[str, Any]) -> bool:
        domain = device["entity_id"].split(".", 1)[0]
        return domain in WATCH_ONLY_DOMAINS or (domain == "cover" and device.get("device_class") in WATCH_ONLY_COVER_CLASSES)

    def listing(self) -> list[dict[str, Any]]:
        return [
            {
                "entity_id": d["entity_id"],
                "name": d["name"],
                "area": d.get("area"),
                "state": d["state"],
                **({"brightness": d["brightness"]} if "brightness" in d else {}),
                "access": "watch" if self.watch_only(d) else d["access"],
            }
            for d in self.devices.values()
        ]

    def act(self, tool: str, entity_id: str, action: str, brightness: int | None) -> dict[str, Any]:
        device = self.devices.get(entity_id)
        if device is None:
            raise ToolError(f"{entity_id} is not one of {self.companion}'s devices.")
        if self.watch_only(device):
            raise ToolError(f"{device['name']} is watch only. Companions can't use it.")
        needed = TOOL_ACCESS[tool]
        if device["access"] != needed:
            other = {"use": "device_use", "use-ask-first": "device_request"}.get(device["access"])
            hint = f" Use {other}." if other else ""
            raise ToolError(f"{device['name']} is at '{device['access']}', not '{needed}'.{hint}")
        domain = entity_id.split(".", 1)[0]
        if action not in DOMAIN_ACTIONS.get(domain, set()):
            raise ToolError(f"{device['name']} does not take '{action}'.")
        if action == "set-brightness":
            if brightness is None:
                raise ToolError("set-brightness needs brightness (0 to 100).")
            device["brightness"] = brightness
            device["state"] = "on" if brightness > 0 else "off"
        elif action == "toggle":
            device["state"] = "off" if device["state"] == "on" else "on"
        else:
            device["state"] = "on" if action == "turn-on" else "off"
        return {"entity_id": entity_id, "name": device["name"], "state": device["state"], **({"brightness": device["brightness"]} if "brightness" in device else {})}

    def log(self, entry: dict[str, Any]) -> None:
        with self.activity_file.open("a") as out:
            out.write(json.dumps({"time": time.strftime("%Y-%m-%dT%H:%M:%S%z"), "companion": self.companion, **entry}) + "\n")


def handle(devices: Devices, message: dict[str, Any]) -> dict[str, Any] | None:
    """Answer one JSON-RPC message. Notifications get no answer."""
    method, msg_id, params = message.get("method"), message.get("id"), message.get("params") or {}
    if msg_id is None:
        return None
    if method == "initialize":
        result = {
            "protocolVersion": params.get("protocolVersion", DEFAULT_PROTOCOL),
            "capabilities": {"tools": {}},
            "serverInfo": {"name": SERVER_NAME, "version": "0.0.0-spike"},
        }
    elif method == "ping":
        result = {}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        result = call_tool(devices, params.get("name", ""), params.get("arguments") or {})
    else:
        return {"jsonrpc": "2.0", "id": msg_id, "error": {"code": -32601, "message": f"Unknown method {method}"}}
    return {"jsonrpc": "2.0", "id": msg_id, "result": result}


def call_tool(devices: Devices, name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    entry: dict[str, Any] = {"tool": name, "arguments": arguments}
    try:
        if name == "devices_list":
            body: Any = devices.listing()
        elif name in TOOL_ACCESS:
            body = devices.act(name, str(arguments.get("entity_id", "")), str(arguments.get("action", "")), arguments.get("brightness"))
        else:
            raise ToolError(f"Unknown tool {name}.")
        entry["result"] = "ok"
        return {"content": [{"type": "text", "text": json.dumps(body)}], "isError": False}
    except ToolError as refusal:
        entry["result"] = f"refused: {refusal}"
        return {"content": [{"type": "text", "text": str(refusal)}], "isError": True}
    finally:
        devices.log(entry)


def serve(devices: Devices, stdin: TextIO = sys.stdin, stdout: TextIO = sys.stdout) -> None:
    for line in stdin:
        if not line.strip():
            continue
        try:
            message = json.loads(line)
        except json.JSONDecodeError:
            reply: dict[str, Any] | None = {"jsonrpc": "2.0", "id": None, "error": {"code": -32700, "message": "Parse error"}}
        else:
            reply = handle(devices, message)
        if reply is not None:
            stdout.write(json.dumps(reply) + "\n")
            stdout.flush()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    here = Path(__file__).parent
    parser.add_argument("--devices", type=Path, default=here / "devices.json")
    parser.add_argument("--activity", type=Path, default=here / "activity.jsonl")
    args = parser.parse_args()
    serve(Devices(args.devices, args.activity))
    return 0


if __name__ == "__main__":
    sys.exit(main())
