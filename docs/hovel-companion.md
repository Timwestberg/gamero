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
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Decides which page requests reach native code. Knows nothing about Hovel. |
| `NativeOriginPolicy` | same file, line 14 | Compares the frame's origin, read from JCEF, with the configured app URL. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Handles the bridge methods, shows the approval dialog, owns the registration file, starts the host session. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `HovelHostSession` | [hovel/HovelHostSession.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostSession.kt) | Heartbeat loop and a single task worker. |

The wiring lives in [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt).

## When the companion exists

`main.kt` creates a `HovelCompanion` only when all of these hold (lines 163-172):

- `GAMERO_PRODUCT` (or `-Dgamero.product`) is `hovel`.
- The OS is macOS.
- `GAMERO_CODEX_PATH` and `GAMERO_WORKSPACE` are both set.

Otherwise the page shows a hint to set the two variables and no bridge is
attached. The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app URL>` (line 168),
so each configured app URL gets its own registration, journal and output.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first (lines 221-227). On app close, `DesktopResources.close`
closes the bridge, then the companion (line 62).

## Bridge trust rules

The page calls `window.gameroQuery({request, persistent, onSuccess, onFailure})`.
The router names are `gameroQuery` and `gameroQueryCancel` (NativeBridge.kt line 95).
[tools/native-bridge-smoke.html](../tools/native-bridge-smoke.html) is a minimal
working client.

Every request goes through `onQuery` (NativeBridge.kt lines 48-85) and is refused
unless all of the following hold:

1. **Same browser, valid frame, main frame, trusted origin.** The browser id must
   match, the frame must be valid and the main frame, and `NativeOriginPolicy`
   must match the frame URL's scheme, host and port against the app URL. Default
   ports are filled in, so `https://host` equals `https://host:443`. URLs with
   user info are rejected. The origin comes from JCEF's frame facts, never from
   the request body. Failure returns `403`.
2. **At most 4096 characters.** Longer requests get `403` (line 50).
3. **Protocol version 1.** The body must be a JSON object with `"version": 1`,
   or the request gets `400` (lines 54-58).
4. **A live context.** The page first opens a persistent query with exactly
   `{"version":1,"method":"watchContext","args":{}}`. Native code answers with a
   fresh random `contextId` and remembers the calling frame id (lines 59-67).
   Every later call must be non-persistent, carry exactly the keys `version`,
   `method`, `args` and `contextId`, use the current `contextId`, and come from
   the same frame id. Anything else gets `403 Native context expired`
   (lines 68-72).

A new `watchContext` call, a cancelled watch query (for example on reload or
navigation), or `close()` all invalidate the context. Invalidation clears the
context and frame ids, cancels every pending call, and calls
`HovelCompanion.invalidatePending` (lines 39-46, 86-93). Cancelling a single
pending call also calls `invalidatePending`. Errors inside a method never reach
the page in detail: the page sees `400 Native action failed; inspect the
companion status` (line 79).

Only `NativeOriginPolicy` has a unit test
(`nativeOriginPolicyRejectsChildOpaqueAndLookalikeFrames` in
[HovelAdapterTest.kt](../composeApp/src/jvmTest/kotlin/org/formulagen/wrapper/hovel/HovelAdapterTest.kt)).
The size cap, context and frame checks in `onQuery` need a live JCEF and are
checked by the smoke page on a Mac.

## Bridge methods

`HovelCompanion.dispatch` (HovelCompanion.kt lines 44-108) handles three methods.
Any other name fails.

| Method | Args | Returns |
| --- | --- | --- |
| `capabilities` | `{}` | `version` 1, `connectComputer` true, `cloud` false, `sandbox` `read-only` |
| `prepareConnection` | `ownerId` (UUID) | host `id`, `name`, and `tokenHash` |
| `activateConnection` | `ownerId`, `hostId` | host `id`, `connected` true |

Only one non-`capabilities` call runs at a time. A second call while one is
pending fails with "Another native operation is pending" (line 50). Each call
records the companion's generation when it starts and fails if
`invalidatePending` or `close` bumps it before the call finishes.

## Connecting a Mac

1. **Approval.** `prepareConnection` sets `approval`, and `main.kt` shows a dialog
   naming the account, the app URL and the workspace, and saying a host
   credential goes into Keychain and Codex runs read-only (main.kt lines 213-217).
   Cancel, a reload or close fails the call.
2. **Registration.** `prepareRegistration` makes the data directory `0700`, takes
   a file lock on `registration.lock`, and reads or creates `registration.json`
   (HovelCompanion.kt lines 110-142).
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
   (lines 81-100). A second activation reuses the running session.

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
state (`ready`, `auth-required` or `unavailable`) and updates the status line. A
failed heartbeat shows "Server unavailable; tasks remain on this computer".

If no task is running, it picks the first returned task in `queued`, `claimed`
or `running`, skipping `queued` tasks unless Codex is ready. The task must be
pinned to this host and use `local` mode (HovelHostAdapter.kt lines 80-86). It
runs on a single worker thread through `ExecutionCoordinator`, so one task runs
at a time and launch rules from [desktop-execution.md](desktop-execution.md)
apply. `ExecutionJournal` also holds a `host.lock` so two companions cannot share
a journal.

`close()` stops both threads, closes the journal so no stale worker can cross the
launch boundary, and waits up to 3 and 5 seconds for them to end.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Wrong frame, wrong origin, closed bridge, or request over 4096 characters. |
| `403 Native context expired` | Missing or old `contextId`, different frame, or a reload happened. |
| `400 Native action failed; inspect the companion status` | The method threw. The status line in the window says more. |
| "This native profile belongs to another account" | `registration.json` has a different `ownerId`. |
| "Task needs reconciliation on this computer" | The coordinator stopped a task. See the journal. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the origin policy, and host pinning. Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
