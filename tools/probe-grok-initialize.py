#!/usr/bin/env python3
"""Inspect ACP initialization in a disposable Grok home; never authenticate or prompt.

Usage: python3 tools/probe-grok-initialize.py /absolute/path/to/grok
Output is a small allowlisted evidence record, not an eligibility verdict.
"""
import json
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import tempfile
import time


def probe(executable: Path) -> dict:
    """Run only version and initialize, with no real login or inherited provider settings."""
    if not executable.is_absolute() or not executable.is_file():
        raise ValueError("Pass an absolute path to the installed Grok executable")
    with tempfile.TemporaryDirectory(prefix="hovel-grok-probe-") as folder:
        env = {"PATH": "/usr/bin:/bin", "HOME": folder, "GROK_HOME": folder, "GROK_DISABLE_AUTOUPDATER": "1", "BROWSER": "/usr/bin/false"}
        for brand in ("CURSOR", "CLAUDE"):
            for feature in ("SKILLS", "RULES", "AGENTS", "MCPS", "HOOKS", "SESSIONS"):
                env[f"GROK_{brand}_{feature}_ENABLED"] = "0"
        env["GROK_CODEX_SESSIONS_ENABLED"] = "0"
        version = subprocess.run([str(executable), "--version"], cwd=folder, env=env,
                                 capture_output=True, text=True, check=True, timeout=10).stdout.strip()[:128]
        process = subprocess.Popen([str(executable), "--no-auto-update", "agent", "--no-leader", "stdio"],
                                   cwd=folder, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=subprocess.PIPE, start_new_session=True)
        request = {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {
            "protocolVersion": 1, "clientCapabilities": {
                "fs": {"readTextFile": False, "writeTextFile": False}, "terminal": False},
            "clientInfo": {"name": "hovel-interface-probe", "version": "0.1.0"}}}
        output = b""
        try:
            process.stdin.write((json.dumps(request) + "\n").encode())
            process.stdin.flush()
            with selectors.DefaultSelector() as selector:
                selector.register(process.stdout, selectors.EVENT_READ, "stdout")
                selector.register(process.stderr, selectors.EVENT_READ, "stderr")
                deadline = time.monotonic() + 10
                while time.monotonic() < deadline:
                    for key, _ in selector.select(0.2):
                        chunk = os.read(key.fileobj.fileno(), 8192)
                        if not chunk:
                            selector.unregister(key.fileobj)
                            continue
                        if key.data != "stdout":
                            continue
                        output += chunk
                        if len(output) > 262144:
                            raise ValueError("ACP initialization exceeded the probe limit")
                        while b"\n" in output:
                            line, output = output.split(b"\n", 1)
                            message = json.loads(line)
                            if message.get("id") == 1:
                                result = message.get("result")
                                if not isinstance(result, dict):
                                    raise ValueError("ACP initialization did not succeed")
                                capabilities = result.get("agentCapabilities", {})
                                return {"version": version, "protocolVersion": result.get("protocolVersion"),
                                        "authMethodIds": [item["id"][:128] for item in result.get("authMethods", [])[:8]],
                                        "loadSession": capabilities.get("loadSession", False),
                                        "sessionCapabilities": list(capabilities.get("sessionCapabilities", {}))[:16],
                                        "authenticationAttempted": False, "promptSent": False}
                    if process.poll() is not None:
                        break
            raise TimeoutError("No ACP initialize result within the probe budget")
        finally:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=3)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            except ProcessLookupError:
                process.wait()


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    print(json.dumps(probe(Path(sys.argv[1])), indent=2))
