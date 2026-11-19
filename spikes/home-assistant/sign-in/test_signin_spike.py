"""Checks the spike's flow against a fake Home Assistant that follows the real redirect rules.

The fake copies the checks in Home Assistant's `homeassistant/components/auth/indieauth.py`
(dev at e5d5a1d, 2026-11-05): a redirect URI is accepted when it has the client_id's scheme
and host:port, or when the client_id page registers it; a loopback port change is accepted
only with PKCE S256. Run: python3 -m unittest -v
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import http.server
import json
import secrets
import threading
import unittest
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import signin_spike


class FakeHomeAssistant:
    """The auth, token, revoke and REST endpoints that the spike calls."""

    def __init__(self, *, metadata: bool = True, registered: dict[str, list[str]] | None = None) -> None:
        self.metadata = metadata
        self.registered = registered or {}
        self.codes: dict[str, tuple[str, str | None]] = {}
        self.refresh_tokens: dict[str, str] = {}
        self.access_tokens: set[str] = set()
        fake = self

        class Handler(http.server.BaseHTTPRequestHandler):
            def log_message(self, *_args: object) -> None:
                return

            def reply(self, status: int, body: object = None, location: str | None = None) -> None:
                self.send_response(status)
                if location:
                    self.send_header("Location", location)
                payload = b"" if body is None else json.dumps(body).encode()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:  # noqa: N802
                parts = urllib.parse.urlsplit(self.path)
                query = {k: v[0] for k, v in urllib.parse.parse_qs(parts.query).items()}
                if parts.path == "/.well-known/oauth-authorization-server":
                    return self.reply(200, {"code_challenge_methods_supported": ["S256"]}) if fake.metadata else self.reply(404)
                if parts.path == "/auth/authorize":
                    challenge = query.get("code_challenge") if query.get("code_challenge_method") == "S256" else None
                    if not fake.redirect_ok(query["client_id"], query["redirect_uri"], pkce=challenge is not None):
                        return self.reply(400, {"message": "Invalid redirect URI"})
                    code = secrets.token_hex(8)
                    fake.codes[code] = (query["client_id"], challenge)
                    target = f"{query['redirect_uri']}?{urllib.parse.urlencode({'code': code, 'state': query['state']})}"
                    return self.reply(302, location=target)
                token = self.headers.get("Authorization", "").removeprefix("Bearer ")
                if token not in fake.access_tokens:
                    return self.reply(401, {"message": "Unauthorized"})
                if parts.path == "/api/":
                    return self.reply(200, {"message": "API running."})
                if parts.path == "/api/config":
                    return self.reply(200, {"version": "2026.11.0"})
                if parts.path == "/api/states":
                    return self.reply(200, [{"entity_id": "light.living_room_lamp"}, {"entity_id": "lock.front_door"}])
                return self.reply(404)

            def do_POST(self) -> None:  # noqa: N802
                length = int(self.headers.get("Content-Length", "0"))
                form = {k: v[0] for k, v in urllib.parse.parse_qs(self.rfile.read(length).decode()).items()}
                if self.path == "/auth/token":
                    return self.reply(*fake.token(form))
                if self.path == "/auth/revoke":
                    fake.refresh_tokens.pop(form.get("token", ""), None)
                    return self.reply(200)
                return self.reply(404)

        self.server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_address[1]}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def redirect_ok(self, client_id: str, redirect_uri: str, *, pkce: bool) -> bool:
        client, redirect = urllib.parse.urlsplit(client_id), urllib.parse.urlsplit(redirect_uri)
        if (client.scheme, client.netloc) == (redirect.scheme, redirect.netloc):
            return True
        registered = self.registered.get(client_id, [])
        if redirect_uri in registered:
            return True
        portless = redirect._replace(netloc=redirect.hostname or "").geturl()
        return pkce and redirect.hostname == "127.0.0.1" and portless in registered

    def token(self, form: dict[str, str]) -> tuple[int, object]:
        if form.get("grant_type") == "authorization_code":
            client_id, challenge = self.codes.pop(form.get("code", ""), (None, None))
            if client_id is None or client_id != form.get("client_id"):
                return 400, {"error": "invalid_request"}
            if challenge is not None:
                digest = hashlib.sha256(form.get("code_verifier", "").encode()).digest()
                if base64.urlsafe_b64encode(digest).rstrip(b"=").decode() != challenge:
                    return 400, {"error": "invalid_grant"}
            refresh = secrets.token_hex(16)
            self.refresh_tokens[refresh] = client_id
            return 200, {**self.new_access(), "refresh_token": refresh}
        if form.get("grant_type") == "refresh_token":
            if self.refresh_tokens.get(form.get("refresh_token", "")) != form.get("client_id"):
                return 400, {"error": "invalid_grant"}
            return 200, self.new_access()
        return 400, {"error": "unsupported_grant_type"}

    def new_access(self) -> dict[str, object]:
        access = secrets.token_hex(16)
        self.access_tokens.add(access)
        return {"access_token": access, "expires_in": 1800, "token_type": "Bearer"}

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def follow(url: str) -> None:
    """Stand in for the browser: follow the redirect back to the spike's loopback server."""
    try:
        urllib.request.urlopen(url, timeout=5).read()
    except urllib.error.HTTPError:
        pass


def run(fake: FakeHomeAssistant, **overrides: object) -> signin_spike.Report:
    args = argparse.Namespace(url=fake.url, client_id=None, redirect=None, port=0, no_pkce=False,
                              keep_signed_in=False, timeout=5.0, results=Path("unused"))
    for key, value in overrides.items():
        setattr(args, key, value)
    return signin_spike.sign_in(args, open_url=follow)


class SignInSpikeTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fake: FakeHomeAssistant | None = None

    def tearDown(self) -> None:
        if self.fake:
            self.fake.close()

    def test_loopback_client_id_with_pkce_signs_in_refreshes_and_revokes(self) -> None:
        self.fake = FakeHomeAssistant()
        report = run(self.fake)
        self.assertTrue(report.client_id.startswith("http://127.0.0.1:"))
        self.assertTrue(report.steps["server metadata"]["pkce_s256"])
        self.assertTrue(report.steps["sign-in"]["ok"])
        self.assertEqual(report.steps["token exchange"]["status"], 200)
        self.assertEqual(report.steps["api read"]["entities"], 2)
        self.assertEqual(report.steps["api read with refreshed token"]["status"], 200)
        self.assertEqual(report.steps["revoke"]["status"], 200)
        self.assertTrue(report.steps["refresh after revoke"]["refused"])

    def test_an_instance_without_pkce_metadata_still_signs_in(self) -> None:
        self.fake = FakeHomeAssistant(metadata=False)
        report = run(self.fake)
        self.assertFalse(report.steps["server metadata"]["pkce_s256"])
        self.assertEqual(report.steps["token exchange"]["status"], 200)

    def test_a_hosted_client_id_accepts_our_loopback_port_only_with_pkce(self) -> None:
        hosted = "https://example.com/hovel-ha/"
        self.fake = FakeHomeAssistant(registered={hosted: ["http://127.0.0.1/callback"]})
        report = run(self.fake, client_id=hosted, redirect="http://127.0.0.1/callback")
        self.assertEqual(report.steps["token exchange"]["status"], 200)
        refused = run(self.fake, client_id=hosted, redirect="http://127.0.0.1/callback", no_pkce=True, timeout=1.0)
        self.assertFalse(refused.steps["sign-in"]["ok"])

    def test_the_report_holds_no_token(self) -> None:
        self.fake = FakeHomeAssistant()
        report = run(self.fake, keep_signed_in=True)
        text = json.dumps(report.__dict__)
        for token in [*self.fake.access_tokens, *self.fake.refresh_tokens]:
            self.assertNotIn(token, text)

    def test_pkce_challenge_is_the_s256_of_the_verifier(self) -> None:
        verifier, challenge = signin_spike.pkce_pair()
        digest = hashlib.sha256(verifier.encode()).digest()
        self.assertEqual(base64.urlsafe_b64encode(digest).rstrip(b"=").decode(), challenge)
        self.assertGreaterEqual(len(verifier), 43)


if __name__ == "__main__":
    unittest.main()
