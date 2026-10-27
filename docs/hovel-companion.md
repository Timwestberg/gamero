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
| `EmberPetBridge` | [hovel/EmberPetBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/EmberPetBridge.kt) | Loopback WebSocket that drives the Ember desktop pet. Started and stopped with the companion. |

The wiring lives in [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt).

## When the companion exists

`main.kt` creates a `HovelCompanion` only when all of these hold (lines 167-178):

- `GAMERO_PRODUCT` (or `-Dgamero.product`) is `hovel`.
- The OS is macOS.
- `GAMERO_CODEX_PATH` and `GAMERO_WORKSPACE` are both set. These two are read
  only from the environment; unlike `GAMERO_PRODUCT` they have no `-D` fallback.

Otherwise no bridge is attached. When the product is `hovel` but there is no
companion, the window shows a hint to set the two variables (main.kt line 229).
On other products it shows nothing. The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app URL>` (line 175),
so each configured app URL gets its own registration, journal and output.
That same creation also starts the Ember pet bridge, below.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first (lines 231-239). On app close, `DesktopResources.close`
closes the bridge, then the companion (line 66).

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
The size cap, the protocol version check and the context and frame checks in
`onQuery` are untested. They need a live JCEF, and the smoke page does not
exercise them: [native-bridge-smoke.html](../tools/native-bridge-smoke.html)
only opens `watchContext`, calls `capabilities` and offers a reload.

## Bridge methods

`HovelCompanion.dispatch` (HovelCompanion.kt lines 57-119) handles three methods.
Any other name fails.

| Method | Args | Returns |
| --- | --- | --- |
| `capabilities` | `{}` | `version` 1, `connectComputer` true, `cloud` false, `sandbox` `read-only` |
| `prepareConnection` | `ownerId` (UUID) | host `id`, `name`, and `tokenHash` |
| `activateConnection` | `ownerId`, `hostId` | host `id`, `connected` true |

Only one non-`capabilities` call runs at a time. A second call while one is
pending fails with "Another call to this capability is running" (line 63). Each call
records the companion's generation when it starts and fails if
`invalidatePending` or `close` bumps it before the call finishes.

`invalidatePending` also clears the approved registration
(HovelCompanion.kt line 165). So after any reload, `activateConnection` fails
with "Native approval is required" until the page calls `prepareConnection`
again.

## Connecting a Mac

1. **Approval.** `prepareConnection` sets `approval`, and `main.kt` shows a dialog
   naming the account, the app URL and the workspace, and saying a host
   credential goes into Keychain and Codex runs read-only (main.kt lines 221-223).
   Cancel, a reload or close fails the call.
2. **Registration.** `prepareRegistration` makes the data directory `0700`, takes
   a file lock on `registration.lock`, and reads or creates `registration.json`
   (HovelCompanion.kt lines 131-163).
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
   (lines 94-116). A second activation reuses the running session.

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

`close()` tells the Ember bridge the companion is offline, stops both threads, closes the journal so no stale worker can cross the
launch boundary, and waits up to 3 and 5 seconds for them to end.

## Ember desktop pet

The companion can drive the [Ember desktop pet](https://github.com/Timwestberg/game-platform/pull/192)
(a WebSocket client that reads `EMBER_PET_WS`). The bridge runs only while the
Hovel companion does: `HovelCompanion` starts it and `close()` stops it. It is
not created for other products, or when `GAMERO_CODEX_PATH` / `GAMERO_WORKSPACE`
are unset.

The server binds `127.0.0.1` on a free port. Each launch generates a random
token. A client is accepted only when the request path is `/` and the query is
exactly `token=<that token>`. Any other path, parameter, or token is rejected
before the WebSocket is established. Connections that are not from loopback are
rejected as well.

While the bridge is up it publishes
`~/Library/Application Support/Hovel/ember-pet.json` (file mode `0600`, directory
`0700`):

```json
{"url":"ws://127.0.0.1:<port>/?token=<token>","pid":<pid>,"version":1}
```

`close()` deletes that file only when the pid and token are still this launch,
so a newer companion is not wiped. A crash can leave a stale file; the next
launch overwrites it.

The file and the socket carry the bridge token and nothing else. The host
Keychain credential, Codex login, and `OPENAI_*` / `CODEX_API_KEY` values are
never written there and never sent as frames. Codex itself still strips those
variables before it starts, as described in
[desktop-execution.md](desktop-execution.md).

Set `GAMERO_EMBER_PET` to the pet executable to show **Open Ember pet**. The
button starts that program with `EMBER_PET_WS` set to the discovery URL. The
URL is an environment value, not a process argument. The child environment
also has `OPENAI_*` and `CODEX_API_KEY` removed. You can instead launch the pet
yourself and point `EMBER_PET_WS` at the `url` in the discovery file. Do not
paste that file into logs or chat: the token is a secret for this launch.

### What the pet is shown

The bridge remembers the latest heartbeat (Codex login plus the server's task
list) and any local outcome that is newer than that heartbeat. A heartbeat that
started before the outcome cannot downgrade it. When several tasks qualify, the
first in server order inside the winning group is the one that is spoken.

| Companion signal | Pet command |
| --- | --- |
| `queued`, `claimed`, `running`, or `cloud-accepted` | `{"type":"state","state":"running"}` |
| `succeeded` | `state` `ready`, then `say` of the result |
| `failed` or `reconciliation-required` | `state` `blocked`, then a short `say` of the error |
| Codex `auth-required`, and nothing above applies | `state` `needs_input` |
| Codex `unavailable`, or the companion is offline | `state` `blocked` |
| Codex `ready` (or login not known yet) and no task | `state` `none` |

An active task wins over login and over older successes. A failure wins over a
success. A success wins over login, so the pet can say the result before it
returns to idle. Every new connection is sent the current `state` first, and
the current `say` after it when the view still has one. The same view is not
repeated to clients that are already connected.

`say` text is at most 240 characters. A first paragraph that already fits is
used as the summary; otherwise the text is cut on a line or word boundary and
ends with `…`. The uncut result is `full`, omitted when nothing was cut.
`taskId` is omitted when there is no task. A blank success says "Codex
finished." A failure with no error text says "Codex task failed." A
reconciliation with no error text says "This task needs reconciliation on this
computer."

### Protocol v1

Text frames, JSON objects. This is the same v1 the pet speaks.

Bridge to pet:

```json
{"type":"state","state":"none"}
{"type":"say","text":"Ports are closed.","taskId":"<uuid>","full":"Ports are closed.\n\n..."}
```

`state` is one of `none`, `running`, `needs_input`, `ready`, `blocked`.

Pet to bridge:

```json
{"type":"hello"}
{"type":"message","id":"<uuid>","text":"..."}
```

`hello` and any other unknown `type` are ignored. A `message` is answered with:

```json
{"type":"message_ack","id":"<same uuid>","status":"unsupported","reason":"Sending from Ember is not wired yet"}
```

Malformed JSON is logged as a fixed sentence and does not close the socket. The
log line does not include the payload. The `id` must be a canonical UUID.

`EmberMessageSink` is the only place a future change should handle that text.
The sink shipped today does not create a Hovel task and does not perform owner
auth; the owner has not chosen that path yet.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Wrong frame, wrong origin, closed bridge, or request over 4096 characters. |
| `403 Native context expired` | Missing or old `contextId`, different frame, or a reload happened. |
| `400 Invalid native protocol` | The request is not a JSON object with `"version": 1` (NativeBridge.kt line 56). |
| `400 Native action failed; inspect the companion status` | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, or an unknown method name, other than a cancellation (HovelCompanion.kt line 125). Failures before that point (bad `capabilities` args, a closed companion, "Another call to this capability is running") leave the status line unchanged and reach the page only as the generic 400. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId` (HovelCompanion.kt line 145). Users see only the two generic messages above; check `registration.json` in the data directory. |
| Status line "Server unavailable; tasks remain on this computer" | A heartbeat failed. The session keeps retrying every 10 seconds. |
| Status line "Task needs reconciliation on this computer; no automatic host change" | Any exception from `ExecutionCoordinator.accept`, including network errors and a server 409, not only a stopped task (HovelHostSession.kt line 64). See the journal before retrying. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the origin policy, and host pinning.
`EmberPetTest` covers the pet state mapping, say truncation, the launch token,
the `message_ack` path, the discovery file, and a fixture Codex run reported to
the bridge. Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
