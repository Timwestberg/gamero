"""Drives hovel_devices.py over stdio as an MCP client would. Run: python3 -m unittest -v"""

from __future__ import annotations

import json
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).parent


class HovelDevicesTest(unittest.TestCase):
    def setUp(self) -> None:
        self.dir = Path(tempfile.mkdtemp())
        shutil.copy(HERE / "devices.json", self.dir / "devices.json")
        self.activity = self.dir / "activity.jsonl"
        self.server = subprocess.Popen(
            [sys.executable, str(HERE / "hovel_devices.py"), "--devices", str(self.dir / "devices.json"), "--activity", str(self.activity)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True,
        )
        self.next_id = 0
        init = self.request("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "test", "version": "0"}})
        self.assertEqual(init["result"]["serverInfo"]["name"], "hovel-devices")
        self.notify("notifications/initialized")

    def tearDown(self) -> None:
        self.server.stdin.close()
        self.server.wait(timeout=5)
        self.server.stdout.close()
        shutil.rmtree(self.dir)

    def send(self, message: dict) -> None:
        self.server.stdin.write(json.dumps(message) + "\n")
        self.server.stdin.flush()

    def request(self, method: str, params: dict | None = None) -> dict:
        self.next_id += 1
        self.send({"jsonrpc": "2.0", "id": self.next_id, "method": method, "params": params or {}})
        reply = json.loads(self.server.stdout.readline())
        self.assertEqual(reply["id"], self.next_id)
        return reply

    def notify(self, method: str) -> None:
        self.send({"jsonrpc": "2.0", "method": method})

    def call(self, tool: str, **arguments: object) -> tuple[bool, str]:
        result = self.request("tools/call", {"name": tool, "arguments": arguments})["result"]
        return result["isError"], result["content"][0]["text"]

    def test_lists_the_three_tools(self) -> None:
        names = [tool["name"] for tool in self.request("tools/list")["result"]["tools"]]
        self.assertEqual(names, ["devices_list", "device_use", "device_request"])

    def test_listing_reports_watch_only_devices_as_watch(self) -> None:
        failed, text = self.call("devices_list")
        self.assertFalse(failed)
        access = {d["entity_id"]: d["access"] for d in json.loads(text)}
        self.assertEqual(access["light.floor_lamp"], "use")
        self.assertEqual(access["light.living_room_lamp"], "use-ask-first")
        self.assertEqual(access["lock.front_door"], "watch")
        self.assertEqual(access["cover.garage_door"], "watch")

    def test_use_acts_on_a_device_at_use(self) -> None:
        failed, text = self.call("device_use", entity_id="light.floor_lamp", action="set-brightness", brightness=70)
        self.assertFalse(failed)
        self.assertEqual(json.loads(text), {"entity_id": "light.floor_lamp", "name": "Floor lamp", "state": "on", "brightness": 70})

    def test_use_refuses_an_ask_first_device_and_names_the_right_tool(self) -> None:
        failed, text = self.call("device_use", entity_id="light.living_room_lamp", action="turn-on")
        self.assertTrue(failed)
        self.assertIn("device_request", text)

    def test_request_acts_on_an_ask_first_device(self) -> None:
        failed, text = self.call("device_request", entity_id="light.living_room_lamp", action="turn-on")
        self.assertFalse(failed)
        self.assertEqual(json.loads(text)["state"], "on")

    def test_watch_only_devices_refuse_at_any_level(self) -> None:
        for entity in ("lock.front_door", "cover.garage_door"):
            for tool in ("device_use", "device_request"):
                failed, text = self.call(tool, entity_id=entity, action="toggle")
                self.assertTrue(failed, f"{tool} {entity}")
                self.assertIn("watch only", text)

    def test_a_watched_sensor_and_an_unknown_device_refuse(self) -> None:
        self.assertTrue(self.call("device_use", entity_id="binary_sensor.front_door", action="turn-on")[0])
        self.assertTrue(self.call("device_use", entity_id="light.kitchen", action="turn-on")[0])

    def test_every_call_writes_one_activity_line(self) -> None:
        self.call("devices_list")
        self.call("device_use", entity_id="light.floor_lamp", action="toggle")
        self.call("device_use", entity_id="lock.front_door", action="toggle")
        lines = [json.loads(line) for line in self.activity.read_text().splitlines()]
        self.assertEqual([line["tool"] for line in lines], ["devices_list", "device_use", "device_use"])
        self.assertEqual(lines[1]["result"], "ok")
        self.assertTrue(lines[2]["result"].startswith("refused"))

    def test_unknown_methods_answer_method_not_found(self) -> None:
        self.assertEqual(self.request("resources/list")["error"]["code"], -32601)


if __name__ == "__main__":
    unittest.main()
