# Spike 2: sign in to Home Assistant from the Mac

Part of [the Home Assistant link spec](https://github.com/Timwestberg/game-platform/blob/claude/feat/home-assistant-spec/docs/home-assistant/home-assistant.SPEC.md) (phase 0). No product code changes.

The spike answers four questions before phase 1 builds the Mac link:

1. Which `client_id` and redirect does Home Assistant accept from the Mac app?
2. Do refresh and revoke work as the auth API says?
3. Does a non-administrator Home Assistant user limit what Hovel can control?
4. Does the instance support PKCE, and which version is it?

## What Home Assistant's source already says

Read in `home-assistant/core` at dev `e5d5a1d` (2026-11-05): `homeassistant/components/auth/indieauth.py`, `login_flow.py`, `homeassistant/components/onboarding/views.py` and `homeassistant/auth/permissions/system_policies.py`.

- **A loopback client works with no hosted page.** A `client_id` host can be a domain or a local IP, and `127.0.0.1` counts. A redirect is accepted when it has the same scheme and host:port as the `client_id`. So `client_id=http://127.0.0.1:<port>/` with `redirect_uri=http://127.0.0.1:<port>/callback` needs no web page and no custom URL scheme.
- **A hosted client needs internet on the Home Assistant side.** For any other redirect, Home Assistant fetches the `client_id` page (5 s timeout, first 10 kB). It reads `<link rel="redirect_uri">` tags, or an OAuth Client ID Metadata Document (JSON, `https`, the same `client_id` inside).
- **PKCE lets a hosted client use any loopback port.** With `code_challenge_method=S256`, a registered `http://127.0.0.1/callback` also accepts `http://127.0.0.1:<any port>/callback` (RFC 8252). The instance advertises this in `/.well-known/oauth-authorization-server` as `code_challenge_methods_supported: ["S256"]`.
- **A non-administrator user does not limit entities.** `USER_POLICY` is `{entities: True}`, the same as the administrator. Only the read-only group limits a user, and only to reading. So Home Assistant users cannot scope a companion. The `hovel-devices` tool must do it (safety rule 4). This answers the spec's open question.
- **The managed install can make the owner account natively.** `POST /api/onboarding/users` (no auth, only before the owner exists) makes the owner and returns an `auth_code` for the `client_id` it gets. Phase 5 can then ask for the account in a native dialog and skip the browser. The trade-off: Hovel handles the owner's password one time.

We do not know which release first shipped PKCE and the metadata document, or whether the sign-in page passes `code_challenge` through to the login flow. The live run checks both.

## Run it on the Mac

The script uses only the standard library and never prints or saves a token.

The instance is the spike 1 VM (Tim's choice, 2026-11-05). Run spike 1 first and leave the VM running.

1. Get the VM's address with `../vm/find-ip.sh`. Open `http://<address>:8123` and finish Home Assistant's first-run setup. That makes the owner account.
2. Try discovery. With NAT we expect it to find nothing; record what it prints.

    ```sh
    python3 signin_spike.py discover
    ```

3. Sign in with a loopback `client_id` (mode A) as the owner.

    ```sh
    python3 signin_spike.py sign-in --url http://<address>:8123
    ```

4. Do step 3 again as a non-administrator user (Settings → People → add a person, administrator off).
5. Do step 3 again with `--no-pkce`, to see what an older client gets.
6. Optional, mode B: host this page at an `https` URL that Home Assistant can reach, then pass that URL. The VM reaches the internet through the NAT.

    ```html
    <link rel="redirect_uri" href="http://127.0.0.1/callback">
    ```

    ```sh
    python3 signin_spike.py sign-in --url http://<address>:8123 \
      --client-id https://<your host>/hovel-ha/ --redirect http://127.0.0.1/callback
    ```

Each run writes `results/sign-in-<time>.json`. Commit the reports with the filled table.

## Checks in the cloud

`python3 -m unittest -v` runs five checks against a fake Home Assistant that copies the redirect rules above. They pass on Python 3.13. They prove the script's flow, not the real instance.

## Results

| Run | Version | Mode | PKCE | Sign-in | Refresh | Refresh refused after revoke | Notes |
| --- | --- | --- | --- | --- | --- | --- | --- |
| | | A, administrator | | | | | |
| | | A, non-administrator | | | | | |
| | | A, `--no-pkce` | | | | | |
| | | B, hosted | | | | | |

## What it decides

Phase 1 needs one `client_id` choice:

- **A, loopback.** It works with no internet. The `client_id` changes with the port, and Home Assistant's token list shows it as `http://127.0.0.1:<port>/`.
- **B, a hosted page with PKCE.** It has a stable name. It needs Home Assistant to reach the page, and a release with PKCE.
- **C, a custom scheme.** It needs B's hosted page and a URL scheme in the jpackage app. Use it only if A and B fail.

Write the recommendation here after the runs.
