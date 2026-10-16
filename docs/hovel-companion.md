# Hovel Mac companion and host bridge

This is the reference for how the Hovel web page in the Mac app talks to native
code, and how the Mac becomes a registered Hovel host. It covers the trust rules
and the lifecycle. For build and launch steps see [desktop.md](desktop.md). For the
manual Mac acceptance run see [hovel-native-validation.md](hovel-native-validation.md).
For the task journal and the Codex process see
[desktop-execution.md](desktop-execution.md).

All paths below are under
`composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/` unless stated.

## The parts

| Part | File | Job |
| --- | --- | --- |
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Checks the browser and frame, then hands every query to `JcefHostBridge`. Knows nothing about Hovel. |
| `JcefHostBridge` | [bridge/JcefHostBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/bridge/JcefHostBridge.kt) | The desktop transport of the shared `host-bridge.v2` router (`commonMain/.../bridge/`). |
| `TrustedOrigin` | `commonMain/.../bridge/TrustedOrigin.kt` | Compares the frame's origin, read from JCEF, with the configured app URL. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Runs the `companion.*` bridge methods, shows the approval dialog, owns the registration file, starts the host session. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `HovelHostSession` | [hovel/HovelHostSession.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostSession.kt) | Heartbeat loop and a single task worker. |

The wiring lives in [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt).

## When the companion exists

`main.kt` creates a `HovelCompanion` only when all of these hold (lines 166-174):

- `GAMERO_PRODUCT` (or `-Dgamero.product`) is `hovel`.
- The OS is macOS.
- `GAMERO_CODEX_PATH` and `GAMERO_WORKSPACE` are both set. These two are read
  only from the environment; unlike `GAMERO_PRODUCT` they have no `-D` fallback.

Otherwise there is no companion, but a `NativeBridge` is still attached for
every product (main.kt line 227), so the page can reach the shared bridge. When
the product is `hovel` but there is no companion, the window shows a hint to set
the two variables (line 222). On other products it shows nothing. The
companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app URL>` (line 171),
so each configured app URL gets its own registration, journal and output.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first (lines 224-235). On app close, `DesktopResources.close`
closes the bridge, then the companion (line 65).

## Bridge trust rules

The page calls `window.gameroQuery({request, persistent, onSuccess, onFailure})`
through the `@formulagen/host-bridge` client (game-platform). The router names
are `gameroQuery` and `gameroQueryCancel`.
[tools/native-bridge-smoke.html](../tools/native-bridge-smoke.html) is a minimal
working client. The desktop speaks only `host-bridge.v2`; version 1, the old
companion protocol, was removed in task B4 (FOR-122).

Every query goes through `NativeBridge.onQuery`, then `JcefHostBridge.onQuery`
and the shared router. The checks run in this order, and the first failure wins:

1. **Same browser, valid frame.** A query from another browser, from an invalid
   frame, or after the bridge closed gets the CEF failure
   `403 Native request unavailable for this frame`.
2. **Main frame, trusted origin.** `TrustedOrigin` must match the frame URL's
   scheme, host and port against the app URL. Default ports are filled in, so
   `https://host` equals `https://host:443`. URLs with user info are rejected.
   The origin comes from JCEF's frame facts, never from the request body. A
   child frame or another origin gets the `forbidden` envelope, and its request
   is never parsed.
3. **A `host-bridge.v2` request.** At most 64 Ki characters
   (`BridgeProtocol.MAX_REQUEST_CHARS`), a JSON object with `"version": 2`, and
   on the right query kind: `watchContext` must be a persistent query and every
   other method must not be. Anything else, version 1 included, gets the
   `invalid` envelope.
4. **A live context.** The page first opens a persistent query with exactly
   `{"version":2,"method":"watchContext","args":{}}`. Native code answers
   `{"version":2,"contextId":"<uuid>"}`, and later successes on that query
   carry events. Every call must carry the current `contextId`, or it gets
   `expired`.

Every answer from step 2 on is a `host-bridge.v2` envelope:
`{"version":2,"ok":true,"result":…}` or
`{"version":2,"ok":false,"error":{"code","message"}}`.

A cancelled watch query (for example on reload or navigation) ends the context
it opened and cancels that context's running calls. A watch cancelled while
still pending has no context yet; its context is declined when the reply
arrives. A newer `watchContext` replaces the context; cancelling the older watch
afterwards leaves the newer context open.

Which cancels also clear a pending registration
(`HovelCompanion.invalidatePending`):

- A reload or navigation cancelling the open watch clears it.
- Cancelling an older watch after the newer one is answered does not.
- Cancelling a still-pending watch (the client's timeout), or the open watch
  before a newer watch is answered, also clears it. This fails closed: the page
  must call `companion.prepareConnection` again.

Closing the bridge also clears it. Errors inside a method never reach the page in
detail: the page sees `failed` (or `busy`, or `declined` when the person said
no).

`JcefTransportContractTest`, `JcefHostBridgeTest` and `JcefQueryKindTest` cover
the frame check, the query kinds, version 1 rejection and watch cancellation
without a live JCEF. `TrustedOriginTest` covers the origin rules.

## Bridge methods

The `companion.*` methods are Hovel's capability pack (`hovelBridgePacks` in
HovelCompanion.kt). `HovelCompanionCapability` passes the two connection
methods to `HovelCompanion.dispatch`.

| Method | Args | Returns |
| --- | --- | --- |
| `companion.capabilities` | `{}` | `connectComputer` true, `cloud` false, `sandbox` `read-only` |
| `companion.prepareConnection` | `ownerId` (UUID) | host `id`, `name`, and `tokenHash` |
| `companion.activateConnection` | `ownerId`, `hostId` | host `id`, `connected` true |

Without a configured companion these methods answer `unsupported`.

Only one connection call runs at a time. A second call while one is pending
gets `busy`. Each call records the companion's generation when it starts and
fails if `invalidatePending` or `close` bumps it before the call finishes.

`invalidatePending` also clears the approved registration. So after any reload,
`companion.activateConnection` fails until the page calls
`companion.prepareConnection` again.

## Connecting a Mac

1. **Approval.** `prepareConnection` sets `approval`, and `main.kt` shows a dialog
   naming the account, the app URL and the workspace, and saying a host
   credential goes into Keychain and Codex runs read-only (main.kt lines 216-220).
   Cancel, a reload or close fails the call.
2. **Registration.** `prepareRegistration` makes the data directory `0700`, takes
   a file lock on `registration.lock`, and reads or creates `registration.json`
   (HovelCompanion.kt lines 117-149).
   - If the file exists it must be at most 4096 bytes and belong to the same
     `ownerId`. A different account is rejected: one native profile serves one
     account. The Keychain entry must still be readable.
   - Otherwise a new host UUID and a 32-byte random token (64 hex characters) are
     made. The token goes to Keychain first. Then the file (id, owner, name
     "My Mac", no token) is written to a `0600` temp file, synced, atomically
     moved into place, and the directory is synced.
3. **Web registration.** `prepareConnection` returns the host id, name and the
   SHA-256 of the token. The page sends these to the server. The raw token never
   crosses the bridge.
4. **Activation.** `activateConnection` must name the same owner and host as the
   approved registration. It reads the token from Keychain and starts a
   `HovelHostSession` with a transport, a per-host journal
   (`journal-<hostId>`) and a Codex provider writing to `output-<hostId>`
   (HovelCompanion.kt lines 84-104). A second activation reuses the running
   session.

### Keychain

`MacHostCredentials` uses service `org.formulagen.gamero.host` with the host id
as the account. It writes through `/usr/bin/security -i` on stdin, so the token
is never in a process argument list. It validates the host id as a UUID and the
token as 64 hex characters, waits at most 60 seconds, and reads the value back to
confirm it was stored (MacHostCredentials.kt lines 15-41). macOS owns any access
prompt.

## Host transport

`HovelHttpTransport` (HovelHostAdapter.kt lines 18-43):

- Base URL is `<app origin>/api/hovel/v1/hovel`. Requests go to
  `/native/<ownerId>/<hostId>/<route>` with header `X-Hovel-Host-Token`.
- HTTPS only, or HTTP on `localhost`, `127.0.0.1` or `[::1]`. No user info,
  query or fragment.
- Routes allowed: `heartbeat`, `claim`, `progress`. The `brain/*` routes are
  allowed only when `brainEnabled` is true, which defaults to false and is not
  set by `HovelCompanion`.
- Redirects are never followed. Connect timeout 10 seconds, request timeout
  15 seconds, and the whole exchange including the body is bounded by
  `timeoutMillis` (default 15 seconds).
- Response bodies over 16 MiB are cut off and fail.
- HTTP `409` throws `HovelRejectedException`, meaning the server refused and
  committed nothing. Other non-2xx codes fail. The JSON `data` field is returned.

## Host session

`HovelHostSession` (HovelHostSession.kt) runs a heartbeat immediately and then
every 10 seconds after the previous one ends. Each heartbeat reports the Codex
state (`ready`, `auth-required` or `unavailable`) and declares
`features: ["provider-input"]` (`HOVEL_HOST_FEATURES` in HovelHostAdapter.kt),
then updates the status line. The server sends follow-up tasks only to a
computer that declares `provider-input`. A failed heartbeat shows "Server unavailable; tasks remain on this computer".

If no task is running, it picks the first returned task in `queued`, `claimed`
or `running`, skipping `queued` tasks unless Codex is ready. The task must be
pinned to this host and use `local` mode (`hovelExecutionRequest` in
HovelHostAdapter.kt). A follow-up (a task whose request has `followUp`) runs the
task's `providerInput`, the conversation text the server built from the earlier
exchanges and the new prompt, in place of `request.prompt`. A task with
`followUp` but no `providerInput`, or the reverse, is refused before anything is
journaled. Prompts may be up to 131,072 characters, the server's
`providerInput` limit; character context stays at 16,384.

The task runs on a single worker thread through `ExecutionCoordinator`, so one task runs
at a time and launch rules from [desktop-execution.md](desktop-execution.md)
apply. `ExecutionJournal` also holds a `host.lock` so two companions cannot share
a journal.

`close()` stops both threads, closes the journal so no stale worker can cross the
launch boundary, and waits up to 3 and 5 seconds for them to end.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Another browser, an invalid frame, or a closed bridge. |
| `forbidden` | A child frame or another origin. |
| `invalid` | Not a `host-bridge.v2` request (version 1 included), over 64 Ki characters, or on the wrong query kind. |
| `expired` | Missing or old `contextId`, or a reload happened. |
| `failed` | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, other than a cancellation (HovelCompanion.kt line 111). Failures before that point (a closed companion, or a second call while one is pending) leave the status line unchanged and reach the page only as `failed` or `busy`. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId` (HovelCompanion.kt line 131). Users see only `failed` and the "Connection failed" status line above; check `registration.json` in the data directory. |
| Status line "Server unavailable; tasks remain on this computer" | A heartbeat failed. The session keeps retrying every 10 seconds. |
| Status line "Task needs reconciliation on this computer; no automatic host change" | Any exception from `ExecutionCoordinator.accept`, including network errors and a server 409, not only a stopped task (HovelHostSession.kt line 49). See the journal before retrying. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, and host pinning. `TrustedOriginTest` covers the
origin policy. Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
