#!/usr/bin/env python3
"""Spike 2: sign in to Home Assistant from the Mac, then refresh and revoke.

Standard library only, so it runs on a Mac with the system python3. It never prints
or saves a token. The report says what worked, with token lengths in place of tokens.

    python3 signin_spike.py discover
    python3 signin_spike.py sign-in --url http://homeassistant.local:8123
    python3 signin_spike.py sign-in --url http://homeassistant.local:8123 \
        --client-id https://example.com/hovel-ha/ --redirect http://127.0.0.1/callback
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.server
import json
import secrets
import shutil
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

SERVICE_TYPE = "_home-assistant._tcp"
CALLBACK_PATH = "/callback"
SIGN_IN_TIMEOUT_SECONDS = 300


def pkce_pair() -> tuple[str, str]:
    """Make a PKCE verifier and its S256 challenge (RFC 7636)."""
    verifier = base64.urlsafe_b64encode(secrets.token_bytes(32)).rstrip(b"=").decode()
    digest = hashlib.sha256(verifier.encode()).digest()
    challenge = base64.urlsafe_b64encode(digest).rstrip(b"=").decode()
    return verifier, challenge


def authorize_url(base: str, client_id: str, redirect_uri: str, state: str, challenge: str | None) -> str:
    """Build the URL of Home Assistant's sign-in page."""
    query = {
        "response_type": "code",
        "client_id": client_id,
        "redirect_uri": redirect_uri,
        "state": state,
    }
    if challenge:
        query["code_challenge"] = challenge
        query["code_challenge_method"] = "S256"
    return f"{base.rstrip('/')}/auth/authorize?{urllib.parse.urlencode(query)}"


def http_json(method: str, url: str, form: dict[str, str] | None = None, token: str | None = None) -> tuple[int, object]:
    """Send one request and return the status and the parsed body (or the raw text)."""
    data = urllib.parse.urlencode(form).encode() if form is not None else None
    request = urllib.request.Request(url, data=data, method=method)
    if form is not None:
        request.add_header("Content-Type", "application/x-www-form-urlencoded")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=15) as response:
            status, body = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, body = error.code, error.read()
    text = body.decode(errors="replace")
    try:
        return status, json.loads(text) if text else None
    except json.JSONDecodeError:
        return status, text


def token_summary(body: object) -> dict[str, object]:
    """Describe a token response without its secrets."""
    if not isinstance(body, dict):
        return {"body": "not JSON"}
    return {
        "token_type": body.get("token_type"),
        "expires_in": body.get("expires_in"),
        "access_token_length": len(str(body.get("access_token", ""))),
        "has_refresh_token": "refresh_token" in body,
    }


@dataclass
class Callback:
    """What the browser brought back to the loopback server."""

    code: str | None = None
    state: str | None = None
    error: str | None = None
    done: threading.Event = field(default_factory=threading.Event)


def start_callback_server(port: int) -> tuple[http.server.HTTPServer, Callback]:
    """Listen on 127.0.0.1 for the redirect back from Home Assistant."""
    received = Callback()

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802 (http.server naming)
            parts = urllib.parse.urlsplit(self.path)
            if parts.path != CALLBACK_PATH:
                self.send_response(404)
                self.end_headers()
                return
            query = urllib.parse.parse_qs(parts.query)
            received.code = (query.get("code") or [None])[0]
            received.state = (query.get("state") or [None])[0]
            received.error = (query.get("error") or [None])[0]
            self.send_response(200)
            self.send_header("Content-Type", "text/plain; charset=utf-8")
            self.end_headers()
            self.wfile.write(b"Hovel spike: sign-in received. You can close this tab.\n")
            received.done.set()

        def log_message(self, *_args: object) -> None:
            return

    server = http.server.HTTPServer(("127.0.0.1", port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, received


@dataclass
class Report:
    """The spike's findings, safe to commit: no tokens, no passwords."""

    url: str
    mode: str
    client_id: str
    redirect_uri: str
    steps: dict[str, object] = field(default_factory=dict)

    def record(self, step: str, value: object) -> None:
        self.steps[step] = value
        print(f"- {step}: {json.dumps(value)}", flush=True)


def sign_in(args: argparse.Namespace, open_url: Callable[[str], object] = webbrowser.open) -> Report:
    """Run the whole flow: metadata, sign-in, token, API read, refresh, revoke."""
    base = args.url.rstrip("/")
    server, received = start_callback_server(args.port)
    port = server.server_address[1]
    hosted = args.client_id is not None
    client_id = args.client_id or f"http://127.0.0.1:{port}/"
    redirect_uri = args.redirect or f"http://127.0.0.1:{port}{CALLBACK_PATH}"
    if hosted and urllib.parse.urlsplit(redirect_uri).port is None:
        # A hosted client_id registers the loopback URI without a port; PKCE lets Home Assistant accept ours.
        redirect_uri = redirect_uri.replace("http://127.0.0.1/", f"http://127.0.0.1:{port}/", 1)
    report = Report(base, "hosted client_id page" if hosted else "loopback client_id", client_id, redirect_uri)
    try:
        status, metadata = http_json("GET", f"{base}/.well-known/oauth-authorization-server")
        pkce = (
            not args.no_pkce
            and status == 200
            and isinstance(metadata, dict)
            and "S256" in metadata.get("code_challenge_methods_supported", [])
        )
        report.record("server metadata", {"status": status, "pkce_s256": pkce})

        verifier, challenge = pkce_pair() if pkce else (None, None)
        state = secrets.token_urlsafe(16)
        url = authorize_url(base, client_id, redirect_uri, state, challenge)
        print(f"Open this page and sign in:\n  {url}", flush=True)
        open_url(url)
        if not received.done.wait(args.timeout):
            report.record("sign-in", {"ok": False, "why": "no redirect before the timeout"})
            return report
        state_ok = received.state == state
        report.record("sign-in", {"ok": received.code is not None and state_ok, "state_matches": state_ok, "error": received.error})
        if not received.code or not state_ok:
            return report

        form = {"grant_type": "authorization_code", "code": received.code, "client_id": client_id}
        if verifier:
            form["code_verifier"] = verifier
        status, tokens = http_json("POST", f"{base}/auth/token", form)
        report.record("token exchange", {"status": status, **token_summary(tokens)})
        if status != 200 or not isinstance(tokens, dict):
            return report
        access, refresh = tokens["access_token"], tokens.get("refresh_token")

        status, _ = http_json("GET", f"{base}/api/", token=access)
        _, config = http_json("GET", f"{base}/api/config", token=access)
        _, states = http_json("GET", f"{base}/api/states", token=access)
        report.record("api read", {
            "status": status,
            "version": config.get("version") if isinstance(config, dict) else None,
            "entities": len(states) if isinstance(states, list) else None,
        })

        if not refresh:
            return report
        status, renewed = http_json("POST", f"{base}/auth/token", {"grant_type": "refresh_token", "refresh_token": refresh, "client_id": client_id})
        report.record("refresh", {"status": status, **token_summary(renewed)})
        if isinstance(renewed, dict) and "access_token" in renewed:
            status, _ = http_json("GET", f"{base}/api/", token=renewed["access_token"])
            report.record("api read with refreshed token", {"status": status})

        if args.keep_signed_in:
            report.record("revoke", {"skipped": "--keep-signed-in"})
            return report
        status, _ = http_json("POST", f"{base}/auth/revoke", {"token": refresh})
        report.record("revoke", {"status": status})
        status, _ = http_json("POST", f"{base}/auth/token", {"grant_type": "refresh_token", "refresh_token": refresh, "client_id": client_id})
        report.record("refresh after revoke", {"status": status, "refused": status != 200})
        return report
    finally:
        server.shutdown()
        server.server_close()


def discover(seconds: float) -> list[dict[str, str]]:
    """Browse for Home Assistant on the LAN with macOS dns-sd, then read each instance's TXT record."""
    if shutil.which("dns-sd") is None:
        print("dns-sd is not on this computer (it ships with macOS).", file=sys.stderr)
        return []
    names = _dns_sd_lines(["dns-sd", "-B", SERVICE_TYPE, "local."], seconds)
    instances = []
    for name in sorted({_browse_name(line) for line in names} - {None}):
        txt = " ".join(_dns_sd_lines(["dns-sd", "-L", name, SERVICE_TYPE, "local."], seconds))
        fields = {key: _txt_value(txt, key) for key in ("location_name", "uuid", "version", "internal_url")}
        instances.append({"instance": name, **{k: v for k, v in fields.items() if v}})
    return instances


def _dns_sd_lines(command: list[str], seconds: float) -> list[str]:
    process = subprocess.Popen(command, stdout=subprocess.PIPE, text=True)
    time.sleep(seconds)
    process.terminate()
    output, _ = process.communicate()
    return output.splitlines()


def _browse_name(line: str) -> str | None:
    # "12:00:00.000  Add  3  14 local.  _home-assistant._tcp.  Pinecrest"
    if " Add " not in line or SERVICE_TYPE not in line:
        return None
    return line.split(f"{SERVICE_TYPE}.", 1)[1].strip() or None


def _txt_value(txt: str, key: str) -> str | None:
    marker = f"{key}="
    if marker not in txt:
        return None
    return txt.split(marker, 1)[1].split(" ", 1)[0].strip("\"")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    find = commands.add_parser("discover", help="list Home Assistant instances on the LAN (macOS)")
    find.add_argument("--seconds", type=float, default=3.0)
    flow = commands.add_parser("sign-in", help="sign in, read the API, refresh and revoke")
    flow.add_argument("--url", required=True, help="the instance, e.g. http://homeassistant.local:8123")
    flow.add_argument("--client-id", help="a hosted client_id page; default is a loopback client_id")
    flow.add_argument("--redirect", help="the redirect URI; default is the loopback callback")
    flow.add_argument("--port", type=int, default=0, help="loopback port; 0 picks a free one")
    flow.add_argument("--no-pkce", action="store_true", help="sign in without PKCE, as an older client")
    flow.add_argument("--keep-signed-in", action="store_true", help="skip the revoke step")
    flow.add_argument("--timeout", type=float, default=SIGN_IN_TIMEOUT_SECONDS)
    flow.add_argument("--results", type=Path, default=Path(__file__).parent / "results")
    args = parser.parse_args(argv)

    if args.command == "discover":
        print(json.dumps(discover(args.seconds), indent=2))
        return 0

    report = sign_in(args)
    args.results.mkdir(parents=True, exist_ok=True)
    out = args.results / f"sign-in-{time.strftime('%Y%m%d-%H%M%S')}.json"
    out.write_text(json.dumps(report.__dict__, indent=2) + "\n")
    print(f"Report: {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
