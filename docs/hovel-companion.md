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
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Checks browser, frame and origin, hands version 2 to `JcefHostBridge` and version 1 to `LegacyCompanionQueries`. |
| `LegacyCompanionQueries` | [companion/LegacyCompanionQueries.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/LegacyCompanionQueries.kt) | Version 1, kept only for the companion's legacy protocol until task B4 removes it: the watch, the context and the three companion methods. |
| `DesktopServices` | [DesktopServices.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/DesktopServices.kt) | Builds the companion with the other desktop services, registers their v2 packs and closes them in reverse order. |
| `NativeOriginPolicy` | same file, line 14 | Compares the frame's origin, read from JCEF, with the configured app URL. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Handles the bridge methods, shows the approval dialog, owns the registration file, starts the host session. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `HovelHostSession` | [hovel/HovelHostSession.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostSession.kt) | Heartbeat loop, a task worker and a memory-job worker (one item each at a time), and the consolidation timer. |

`DesktopServices.create` builds the services; [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt) attaches them to the WebView.

## When the companion exists

`DesktopServices.create` creates a `HovelCompanion` only when all of these hold:

- `GAMERO_PRODUCT` (or `-Dgamero.product`) is `hovel`.
- The OS is macOS.
- A Codex executable is found (`GAMERO_CODEX_PATH`, else `codex` on `PATH` or
  an installed location) and `GAMERO_WORKSPACE` is set. These are read only from
  the environment; unlike `GAMERO_PRODUCT` they have no `-D` fallback.

Otherwise the companion's v2 methods answer `unsupported` and version 1 answers
every query with `403`. When the product is `hovel` but there is no companion,
the window shows a hint to set the two variables. On other products it shows
nothing. The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app URL>`, so each
configured app URL gets its own registration, journal and output.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first. On app close, `DesktopResources.close` closes the bridge,
then `DesktopServices`, which closes the game, the companion, the pet,
providers and chat in that order.

## Bridge trust rules

The page calls `window.gameroQuery({request, persistent, onSuccess, onFailure})`.
The router names are `gameroQuery` and `gameroQueryCancel` (NativeBridge.kt line 95).
[tools/native-bridge-smoke.html](../tools/native-bridge-smoke.html) is a minimal
working client.

Every request goes through `NativeBridge.onQuery`, then (for version 1)
`LegacyCompanionQueries.onQuery`, and is refused unless all of the following hold:

1. **Same browser, valid frame, main frame, trusted origin.** The browser id must
   match, the frame must be valid and the main frame, and `NativeOriginPolicy`
   must match the frame URL's scheme, host and port against the app URL. Default
   ports are filled in, so `https://host` equals `https://host:443`. URLs with
   user info are rejected. The origin comes from JCEF's frame facts, never from
   the request body. Failure returns `403`.
2. **At most 4096 characters.** Longer requests get `403`.
3. **Protocol version 1.** The body must be a JSON object with `"version": 1`,
   or the request gets `400`. (Version 2 requests go to `JcefHostBridge`; see
   [host-bridge.md](host-bridge.md).)
4. **A live context.** The page first opens a persistent query with exactly
   `{"version":1,"method":"watchContext","args":{}}`. Native code answers with a
   fresh random `contextId` and remembers the calling frame id.
   Every later call must be non-persistent, carry exactly the keys `version`,
   `method`, `args` and `contextId`, use the current `contextId`, and come from
   the same frame id. Anything else gets `403 Native context expired`.
5. **A companion method.** Only `capabilities`, `prepareConnection` and
   `activateConnection` are dispatched. Chat, providers, pet and games are v2
   capabilities; their names, or any other, get `400 Unknown native method`.

A new `watchContext` call, a cancelled watch query (for example on reload or
navigation), or `close()` end the version 1 context: the context and frame ids
are cleared, every pending version 1 call is cancelled, and
`HovelCompanion.invalidatePending` voids any open approval. Cancelling a single
pending call cancels only that call and voids the approval; it never ends the
page's version 2 context, so an open chat view or game stays open. The page's
version 2 context ending (`JcefHostBridge`) calls `DesktopServices.invalidate`,
which ends the chat view, closes the game and voids the approval. Errors inside
a method never reach the page in detail: the page sees `400 Native action
failed; inspect the companion status`.

`NativeOriginPolicy` has a unit test
(`nativeOriginPolicyRejectsChildOpaqueAndLookalikeFrames` in
[HovelAdapterTest.kt](../composeApp/src/jvmTest/kotlin/org/formulagen/wrapper/hovel/HovelAdapterTest.kt)).
`LegacyCompanionQueriesTest` covers the context, frame and method checks and
cancellation without JCEF. The browser and frame checks in `NativeBridge` and
the size cap need a live JCEF, and the smoke page does not exercise them:
[native-bridge-smoke.html](../tools/native-bridge-smoke.html) only opens
`watchContext`, calls `capabilities` and offers a reload.

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

`invalidatePending` also clears the approved registration
(HovelCompanion.kt line 146). So after any reload, `activateConnection` fails
with "Native approval is required" until the page calls `prepareConnection`
again.

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
   `HovelHostSession` with a transport, a per-host task journal
   (`journal-<hostId>`) and a Codex provider writing to `output-<hostId>`,
   plus a memory-job journal (`memory-journal-<hostId>`) and a second Codex
   provider writing to `memory-output-<hostId>`. Both providers use the same
   login. Memory jobs run in an empty private folder,
   `memory-workspace-<hostId>`, never the player's workspace, so a project's
   `AGENTS.md` cannot change their output. A second activation reuses the
   running session.

### Keychain

`MacHostCredentials` uses service `org.formulagen.gamero.host` with the host id
as the account. It writes through `/usr/bin/security -i` on stdin, so the token
is never in a process argument list. It validates the host id as a UUID and the
token as 64 hex characters, waits at most 60 seconds, and reads the value back to
confirm it was stored (MacHostCredentials.kt lines 15-41). macOS owns any access
prompt.

## Host transport

`HovelHttpTransport` (HovelHostAdapter.kt):

- Base URL is `<app origin>/api/hovel/v1/hovel`. Requests go to
  `/native/<ownerId>/<hostId>/<route>` with header `X-Hovel-Host-Token`.
- HTTPS only, or HTTP on `localhost`, `127.0.0.1` or `[::1]`. No user info,
  query or fragment.
- Routes allowed: `heartbeat`, `claim`, `progress`, `memory/claim`,
  `memory/progress` and `memory/consolidate`. The `brain/*` routes are
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
`features: ["provider-input", "memory-context", "memory-extraction"]`
(`HOVEL_HOST_FEATURES` in HovelHostAdapter.kt), then updates the status line.
The server sends follow-up tasks only to a computer that declares
`provider-input`, recalled memory only with `memory-context`, and memory jobs
only with `memory-extraction`. A failed heartbeat shows "Server unavailable;
tasks remain on this computer".

The session runs two lanes, each with its own journal and a single worker
thread: one user task and one memory job may run at the same time. A malformed
task never holds up a memory job, nor the reverse. A memory job is checked
against the journal's rules before it is offered, so one this version cannot
run is shown on the status line rather than retried quietly.

### Tasks

If no task is running, it picks the first returned task in `queued`, `claimed`
or `running`, skipping `queued` tasks unless Codex is ready. The task must be
pinned to this host and use `local` mode (`hovelExecutionRequest` in
HovelHostAdapter.kt). A follow-up (a task whose request has `followUp`) runs the
task's `providerInput`, the conversation text the server built from the earlier
exchanges and the new prompt, in place of `request.prompt`. A task with
`followUp` but no `providerInput`, or the reverse, is refused before anything is
journaled. Prompts may be up to 131,072 characters, the server's
`providerInput` limit; character context stays at 16,384.

A task may carry `recalledMemory`: generated notes the server chose when it
accepted the task, up to 12,288 characters. Codex gets it as its own section
between the character context and the task, never as user-authored text:

```text
Platform-owned character context (user-authored):
<characterMemory>

<recalledMemory, when present>

User task:
<providerInput, else prompt>
```

This is byte for byte the server's `hovelProviderPrompt`.
`HovelProviderPromptTest` checks every case of the server's fixture
(`event-contracts/src/hovel/fixtures/provider-prompt.json`), copied into
`HovelProviderPromptFixture.kt` by
`tools/sync-server-fixtures.py ../server-platform`, the same script that copies
the host-bridge fixtures. Run it after the fixture changes; `--check` fails
when a copy is stale.

### Memory jobs

A heartbeat from a computer declaring `memory-extraction` also returns
`memoryJobs`: its open jobs, oldest first. If no memory job is running, the
session picks the first one in `queued`, `claimed` or `running` (`queued` only
when Codex is ready) whose `kind` is `extract` and host-bridge `runtimeId` is
`codex`. A job naming another kind or runtime is left for a companion that runs
it. A job missing either field is treated as unreadable (below), so a renamed
field asks for an update instead of being skipped in silence.

A memory job runs the server-built `input` exactly as given: no character
section and no recalled memory. It runs on the job's `model` and `effort`,
which the player chose in Hovel's memory settings (defaults: `runtime-default`
model, `low` effort); see [desktop-execution.md](desktop-execution.md) for the
Codex flags. It goes through `ExecutionCoordinator` like a task, on
`memory/claim` and `memory/progress` with `jobId` (`HovelMemoryJobRemote`). A
successful result over 65,536 characters is reported `failed` with its length,
never cut short into broken JSON.

A failed or uncertain job needs nothing on this computer. The server closes it
and plans the day again, up to 3 attempts, then waits for the player's retry in
Hovel. A job the server replaced or took back answers 409 and is never offered
again. Memory job failures leave the status line unchanged, except a job this
version cannot read (a missing field, such as `runtimeId` under an older name,
a model or effort it does not know, or an input longer than it can run). The status line then says "Connected · Update
Hovel on this computer to run its memory jobs", and the job stays queued until
the companion is updated.

### Consolidation

The session calls `memory/consolidate` 1 minute after start, every 5 minutes
after that, and right after each delivered memory job result. The calls run on
their own thread, so a slow one never delays a heartbeat. Only the extraction
computer chosen in Hovel's memory settings may consolidate; any other computer
gets 403. Every failure is ignored and waits for the next call.

### Closing

`ExecutionJournal` holds a `host.lock` so two companions cannot share a
journal. `close()` stops the heartbeat and consolidation threads and both
workers, closes both journals so no stale worker can cross the launch boundary,
and waits at most 5 seconds in all for them to end.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Wrong frame, wrong origin, closed bridge, or request over 4096 characters. |
| `403 Native context expired` | Missing or old `contextId`, different frame, or a reload happened. |
| `400 Invalid native protocol` | The request is not a JSON object with `"version": 1`. |
| `400 Unknown native method` | A version 1 call for anything but the three companion methods. Chat, providers, pet and games are host-bridge v2 only. |
| `400 Native action failed; inspect the companion status` | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, or an unknown method name, other than a cancellation (HovelCompanion.kt line 105). Failures before that point (bad `capabilities` args, a closed companion, "Another native operation is pending") leave the status line unchanged and reach the page only as the generic 400. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId` (HovelCompanion.kt line 124). Users see only the two generic messages above; check `registration.json` in the data directory. |
| Status line "Server unavailable; tasks remain on this computer" | A heartbeat failed. The session keeps retrying every 10 seconds. |
| Status line "Task needs reconciliation on this computer; no automatic host change" | Any exception from `ExecutionCoordinator.accept` for a task, including network errors and a server 409, not only a stopped task. See the journal before retrying. Memory jobs never set it. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the origin policy, host pinning, the declared
features, recalled memory, and memory jobs: their request, routes, output
limit and refusals. `HovelHostSessionTest` covers a memory job beside a running
task, one item per lane, malformed items, other providers and consolidation.
`HovelProviderPromptTest` checks the provider text against the server fixture.
Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
