# Hovel Mac companion and host bridge

This is the reference for how the Hovel web page in the Mac app talks to native
code, how the Mac becomes a registered Hovel host, and how it then runs its
projects' companion conversations. It covers the trust rules and the lifecycle.
For build and launch steps see [desktop.md](desktop.md). For the manual Mac
acceptance run see [hovel-native-validation.md](hovel-native-validation.md). For
the execution journal and the Codex process see
[desktop-execution.md](desktop-execution.md). The design is game-platform
`docs/launcher/companion-projects.ARCH.md` ("Mac: gamero"), and the payloads are
server-platform `event-contracts/src/hovel/companions`.

All paths below are under
`composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/` unless stated.

## The parts

| Part | File | Job |
| --- | --- | --- |
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Checks browser, frame and origin, hands version 2 to `JcefHostBridge` and version 1 to `LegacyCompanionQueries`. |
| `LegacyCompanionQueries` | [companion/LegacyCompanionQueries.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/LegacyCompanionQueries.kt) | Version 1, kept only for the companion's legacy protocol until task B4 removes it: the watch, the context and the three companion methods. |
| `DesktopServices` | [DesktopServices.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/DesktopServices.kt) | Builds the companion with the other desktop services, registers their v2 packs and closes them in reverse order. |
| `NativeOriginPolicy` | same file, line 14 | Compares the frame's origin, read from JCEF, with the configured app URL. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Handles the bridge methods, shows the approval dialog, owns the registration file, starts the host session at activation and again at every launch. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `HovelHostSession` | [hovel/HovelHostSession.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostSession.kt) | The memory lane: one memory job at a time, from the runner's heartbeat, and the consolidation timer. |
| `ConversationHost` | [conversations/ConversationHost.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationHost.kt) | Builds the runner for the registered account, with runtime instances of its own, and owns `project.*`. |
| `ConversationRunner` | [conversations/ConversationRunner.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationRunner.kt) | The host stream, the heartbeat, claims, the claim journal and one serial lane per conversation. |
| `CoordinatorEngine` | [conversations/CoordinatorEngine.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/CoordinatorEngine.kt) | Runs one claimed message through the companion brain: coordinator, threads, workflow, checkpoints and deltas. |
| `NativeRoutes` | [conversations/NativeRoutes.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/NativeRoutes.kt) | Every conversation route the Mac calls, spelled once. |

`DesktopServices.create` builds the services; [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt) attaches them to the WebView.

## When the companion exists

`DesktopServices.create` creates a `HovelCompanion` when `GAMERO_PRODUCT` (or
`-Dgamero.product`) is `hovel` and the OS is macOS. Codex (`GAMERO_CODEX_PATH`,
else `codex` on `PATH` or an installed location) is optional: without it the
Mac runs conversations on the runtimes it has, and no memory jobs.

Otherwise the companion's v2 methods answer `unsupported` and version 1 answers
every query with `403`. On a product other than Hovel the window shows nothing.
The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app URL>`, so each
configured app URL gets its own registration, memory journal and output.

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

`HovelCompanion.dispatch` (HovelCompanion.kt lines 68-129) handles three methods.
Any other name fails.

| Method | Args | Returns |
| --- | --- | --- |
| `capabilities` | `{}` | `version` 1, `connectComputer` true, `cloud` false, `sandbox` `read-only` |
| `prepareConnection` | `ownerId` (UUID) | host `id`, `name`, and `tokenHash` |
| `activateConnection` | `ownerId`, `hostId` | host `id`, `connected` true |

Only one non-`capabilities` call runs at a time. A second call while one is
pending fails as busy (line 74). Each call
records the companion's generation when it starts and fails if
`invalidatePending` or `close` bumps it before the call finishes.

`invalidatePending` also clears the approved registration
(HovelCompanion.kt line 212). So after any reload, `activateConnection` fails
with "Native approval is required" until the page calls `prepareConnection`
again.

## Connecting a Mac

1. **Approval.** `prepareConnection` sets `approval`, and `main.kt` shows a dialog
   naming the account and the app URL, and saying a host credential goes into
   Keychain and this Mac then runs the account's conversations whenever Hovel is
   open, with the player's own provider sign-ins, working only in folders chosen
   on this Mac. Cancel, a reload or close fails the call.
2. **Registration.** `prepareRegistration` makes the data directory `0700`, takes
   a file lock on `registration.lock`, and reads or creates `registration.json`
   (HovelCompanion.kt lines 176-208).
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
   approved registration. It reads the token from Keychain and starts the host
   session: the conversation runner (below) and, with Codex, the memory lane, a
   memory-job journal (`memory-journal-<hostId>`) and a Codex provider writing
   to `memory-output-<hostId>` on the player's own login. Memory jobs run in an
   empty private folder, `memory-workspace-<hostId>`, never a project folder, so
   a project's `AGENTS.md` cannot change their output. A second activation
   reuses the running session.
5. **Every launch after that.** `HovelCompanion.resume` reads `registration.json`
   and the Keychain token off the UI thread and starts the same session, with no
   page involved. Before the one-time registration it does nothing.

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
  `/native/<ownerId>/<hostId>/<route>` with headers `X-Hovel-Host-Token` and
  `X-Hovel-Contract` (this build's companion contract, `HovelContract.CURRENT`).
- HTTPS only, or HTTP on `localhost`, `127.0.0.1` or `[::1]`. No user info,
  query or fragment.
- Routes allowed: the conversation routes in `NativeRoutes` with their method
  (`POST heartbeat`, `GET stream`, `POST conversations/claim`,
  `POST conversations/:id/messages`, `POST messages/:id/checkpoints|deltas|progress`,
  `PUT projects/:id/folders`, `POST approvals/:id/answer`, and
  `GET approvals/:id`), plus `memory/claim`, `memory/progress` and
  `memory/consolidate`. The `brain/*` routes are allowed only when
  `brainEnabled` is true, which defaults to false and is not set by
  `HovelCompanion`. The task routes are gone.
- Redirects are never followed. Connect timeout 10 seconds, request timeout
  15 seconds, and the whole exchange including the body is bounded by
  `timeoutMillis` (default 15 seconds).
- Response bodies over 16 MiB are cut off and fail.
- HTTP `409` throws `HovelRejectedException`, meaning the server refused and
  committed nothing. Other non-2xx codes throw `HovelStatusException` with the
  status. The JSON `data` field is returned.
- `openStream` holds `GET stream` open as server-sent events, with no request
  timeout; closing it ends the connection.

## Conversations

`ConversationRunner` makes the Mac the worker for its projects' conversations.
It replaced the task lane; the memory lane and consolidation stay.

### Connection

- **Stream:** it holds `GET stream` (`hostStreamEvent`s, as raw events or in the
  server's SSE envelope; heartbeats, comments and unknown event types are
  skipped), and reconnects with backoff from 1 to 30 seconds.
- **Heartbeat:** every 10 seconds, `computerHeartbeat` (the provider directory
  with the readiness and models of Codex, Claude Code and Grok Build, and each
  ready runtime's usage as an `allowanceSnapshot`) plus the memory features
  (`memory-context`, `memory-extraction`). `HostDirectory` refreshes the directory
  every minute and usage every 5 minutes. The answer goes to the memory lane. A
  successful heartbeat retries claims and outcomes that waited for the server, and
  publishes project folders.

### Claiming and running

- `work.available` wakes that conversation's lane, which claims messages until
  none is left. One message runs at a time per conversation. At most 4
  conversations run at once (`MAX_RUNNING`); a lane waits for a slot before it
  claims, so a waiting message stays queued on the server.
- `stop.requested` and `message.cancelled` stop the running message, which
  settles `stopped`. `approval.answered` closes the Mac's dialog when the phone
  answered first. `project.changed` publishes that project's folders.
- `mac-update-required` leaves the message queued and sets the status line to
  "Update Hovel on this Mac to run every message in its conversations".

### Crash safety

Each claim is journaled in `ConversationStore` before the claim request, with the
rules `ExecutionJournal` keeps for memory jobs. The message id is only known once
the server answers, so the journal is keyed by claim id:

| Journaled phase | After a restart |
| --- | --- |
| `claiming`, `claimed` | Claim again with the same claim id; the server answers the same claim. |
| `launching` | Settle `reconciliation-required`; never run again. |
| `settling` | Deliver the decided outcome until the server takes it. |

A refusal (409) means the server took the claim back, and nothing is settled.

### The engine

`CoordinatorEngine` runs one claimed message through the companion brain, with
the run state machine in `brain/BrainTransitions.kt`. The session is the
conversation: no page opens, ends or reopens it.

- **No specialists:** the coordinator is a plain talk turn that streams.
- **A main message:** the coordinator replies with a JSON header line, and for an
  answer a free-text body (`CONVERSATION_PROTOCOL`). The body streams as deltas
  once the header validates; every other decision is its header alone and never
  streams. One repair attempt follows an invalid reply.
- **Threads:** an attempt is a thread. A delegated task is announced by a
  coordinator message, which its thread hangs under. Retries and workflow returns
  stay in the task's thread, and its specialist keeps one provider conversation,
  keyed by thread. A specialist's reply streams into its thread; its output is
  stored as an artifact (`artifact.stored`, the text for a text output).
- **Thread replies (`THREAD_REPLY`):** the coordinator forwards the reply to the
  thread's specialist (`forward`, with an optional note), answers in the thread,
  or delegates a new task in a new thread. A coordinator note says which.
- **Checkpoints:** `thread.upserted`, `message.appended`, `run.changed`,
  `artifact.stored` and `approval.opened`/`closed`, each retried until stored.
  Deltas are best effort, cut to 2,048 characters and numbered per reply.
- **Settling:** a completed run, or one waiting for the person, answers
  `answered`; Stop answers `stopped`; a paused run answers `failed`, and the
  person's retry of the same message reviews the run natively and resumes it.
- **Talk turns** run one at a time per Codex talk runtime, which holds one turn;
  working specialists run in parallel, each on its thread's lease, which
  `WorkRuntime.release(lease)` ends without touching other leases.

Locally, `ConversationStore` keeps `<providers>/<accountId>/conversations/<id>/`:
the coordinator's and each thread's provider conversations, the workflow run, the
artifacts and the claim journal. The runner holds the account's
`.runner.lock`, so one Hovel runs an account's conversations. Grok's binding
pruning keeps every handle these records hold.

### Project folders

- `ProjectFolderStore` maps `(projectId, folderId)` to a path, per account, in
  `<providers>/<accountId>/project-folders.json`. Paths never leave the Mac.
- `project.chooseFolder`, `forgetFolder` and `folderStatus` use the native picker
  and answer display names (`ProjectFolderNames.display`), roles, `exists` and
  `readableBy`, never a path. Each change is published with
  `PUT projects/:id/folders` against the project revision this Mac last saw (from
  a claim or its own save); until one is known, or after a conflict, the folders
  wait. A 403 or 404 marks the project as another computer's.
- A work turn's `WorkScope` carries `ProjectFolders(working, context)`. A runtime
  is given context folders only when its `ContextFolderSupport` is `VERIFIED`:
  Claude Code (`--add-dir`, with Edit and Write denied under each folder) and Codex
  (the `workspace-write` sandbox writing only the working folder) are `UNVERIFIED`
  until their live probes (`ContextFolderProbeTest`) pass; Grok has no mechanism.

### Approvals

`RemoteApprovals` is the work runtimes' approval broker in conversations. Every
permission request, start of work, stage approval and resume review gets an id,
opens on the Mac as a native dialog and on the server as `approval.opened`, with
`presentApproval`'s folder-relative lines (`./src/app.ts`, `[docs]/plan.md`) or
`mac-only` when it names anything else or cannot be shareable text. The first
answer wins: a Mac answer is posted, and the stored approval says which answer
won; a phone answer (`approval.answered`, read back with `GET approvals/:id`)
closes the dialog as `answered-elsewhere`. Nobody answering withdraws it. A late
or repeated answer changes nothing, and each approval keeps a receipt. The server
expiry allows three dialog timeouts, since dialogs show one at a time and the Mac
withdraws an approval itself when its dialog ends unanswered; a Mac answer the
server refused for anything but another answer still stands. Folder
trust stays a Mac-only dialog.

### `host.contract`

`DesktopHostContract` answers the page's contract with `HovelContract.CURRENT`
and keeps the page's for its context, so the host can leave out runtimes newer
than the page renders.

## Memory jobs

`HovelHostSession` takes the heartbeat answer the runner hands it. Its
`memoryJobs` are its open jobs, oldest first. If no memory job is running, it
picks the first one in `queued`, `claimed` or `running` (`queued` only when
Codex is ready) whose `kind` is `extract` and host-bridge `runtimeId` is
`codex`. A job naming another kind or runtime is left for a companion that runs
it. A job missing either field is treated as unreadable (below), so a renamed
field asks for an update instead of being skipped in silence.

A memory job runs the server-built `input` exactly as given: no character
section and no recalled memory. It runs on the job's `model` and `effort`,
which the player chose in Hovel's memory settings (defaults: `runtime-default`
model, `low` effort); see [desktop-execution.md](desktop-execution.md) for the
Codex flags. It goes through `ExecutionCoordinator` on `memory/claim` and
`memory/progress` with `jobId` (`HovelMemoryJobRemote`). A successful result
over 65,536 characters is reported `failed` with its length, never cut short
into broken JSON.

A failed or uncertain job needs nothing on this computer. The server closes it
and plans the day again, up to 3 attempts, then waits for the player's retry in
Hovel. A job the server replaced or took back answers 409 and is never offered
again. A job this version cannot read (a missing field, such as `runtimeId`
under an older name, a model or effort it does not know, or an input longer than
it can run) sets the status line to "Connected · Update Hovel on this computer
to run its memory jobs", and the job stays queued until the companion is updated.

The session calls `memory/consolidate` 1 minute after start, every 5 minutes
after that, and right after each delivered memory job result, on its own
thread. Only the extraction computer chosen in Hovel's memory settings may
consolidate; any other computer gets 403. Every failure waits for the next call.

### Closing

`close()` stops the runner, the stream, the heartbeat and the lanes, then the
memory lane and its journal, and waits at most 5 seconds. Quitting is not the
person's Stop: a running message's run pauses for review and the message settles
`reconciliation-required` (or does at the next launch, from the journal), so the
person can retry it.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Wrong frame, wrong origin, closed bridge, or request over 4096 characters. |
| `403 Native context expired` | Missing or old `contextId`, different frame, or a reload happened. |
| `400 Invalid native protocol` | The request is not a JSON object with `"version": 1`. |
| `400 Unknown native method` | A version 1 call for anything but the three companion methods. Chat, providers, pet and games are host-bridge v2 only. |
| `400 Native action failed; inspect the companion status` | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, or an unknown method name, other than a cancellation (HovelCompanion.kt line 125). Failures before that point (bad `capabilities` args, a closed companion, "Another native operation is pending") leave the status line unchanged and reach the page only as the generic 400. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId` (HovelCompanion.kt line 190). Users see only the two generic messages above; check `registration.json` in the data directory. |
| Status line "Server unavailable; messages wait on the server" | A heartbeat failed. The runner keeps trying every 10 seconds; queued messages wait on the server. |
| Status line "Update Hovel on this Mac to run every message in its conversations" | A claim answered `mac-update-required`: a message needs a newer contract. It stays queued until this Mac updates or it expires. |
| A message settled `reconciliation-required` | Its turn may have started before a crash or an error the runner could not classify. It never runs again unless the person retries it. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the origin policy, and memory jobs: their request,
routes, output limit and refusals. `HovelHostSessionTest` covers the memory lane
fed by a heartbeat, one job at a time, unreadable and other providers' jobs and
consolidation. `HovelProviderPromptTest` checks the provider text against the
server fixture.

The `conversations` tests run against a fake hovel-service (`FakeServer`):
`ConversationRunnerTest` (parallel conversations, the cap, crash and journal
recovery, stop and cancel, expiry, `mac-update-required`, offline outcomes,
reconnects), `CoordinatorEngineTest` (direct streaming, delegation and threads,
workflow returns, `THREAD_REPLY`, Stop, retry review, context folders),
`RemoteApprovalsTest` (races and late answers), `PresentApprovalTest`,
`ProjectFoldersTest`, `ContextFoldersTest` and the wire tests.
`CompanionContractFixtureTest` reads and writes back every payload in
`CompanionContractFixtures.kt`, which `tools/sync-server-fixtures.py` builds
from the server's samples with `tools/companion-fixtures.mts` and checks against
its zod schemas (it needs the server's `event-contracts` dependencies
installed). `ContextFolderProbeTest` holds the live context-folder probes; they
run only with `HOVEL_CONTEXT_FOLDERS_CLAUDE_PROBE` or
`HOVEL_CONTEXT_FOLDERS_CODEX_PROBE` set.
Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
