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
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Checks the browser and frame, then hands every query to `JcefHostBridge`. Knows nothing about Hovel. |
| `JcefHostBridge` | [bridge/JcefHostBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/bridge/JcefHostBridge.kt) | The desktop transport of the shared `host-bridge.v2` router (`commonMain/.../bridge/`). |
| `DesktopServices` | [DesktopServices.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/DesktopServices.kt) | Builds the companion with the other desktop services, registers their v2 packs, releases what a page owned and closes them in reverse order. |
| `TrustedOrigin` | `commonMain/.../bridge/TrustedOrigin.kt` | Compares the frame's origin, read from JCEF, with the configured app URL. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Runs the `companion.*` bridge methods, shows the approval dialog, owns the registration file, starts the host session at activation and again at every launch. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `HovelHostSession` | [hovel/HovelHostSession.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostSession.kt) | The Codex lanes fed by the runner's heartbeat: one Hovel task (with `GAMERO_WORKSPACE`, until step E) and one memory job at a time, and the consolidation timer. |
| `ConversationHost` | [conversations/ConversationHost.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationHost.kt) | Builds the runner for the registered account, with runtime instances of its own, and owns `project.*`. |
| `ConversationRunner` | [conversations/ConversationRunner.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationRunner.kt) | The host stream and its watchdog, the heartbeat, claims, the claim journal and one serial lane per conversation. Also defines `MessageEngine`. |
| `CoordinatorEngine` | [conversations/CoordinatorEngine.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/CoordinatorEngine.kt) | Runs one claimed message through the companion brain: coordinator, threads, workflow, and the outbox of checkpoints. |
| `ProviderTurns` | [conversations/ProviderTurns.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ProviderTurns.kt) | The engine's provider turns: readiness, model resolution, talk permits, work leases and Stop. |
| `ConversationPrompts` | [conversations/ConversationPrompts.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationPrompts.kt) | `CONVERSATION_PROTOCOL` and the coordinator's and specialists' inputs. |
| `NativeRoute` | [hovel/NativeRoutes.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/NativeRoutes.kt) | Every route a registered Mac calls: one table of method, path template and builder, which is also what the transport allows. |
| `DesktopBrain` | [brain/DesktopBrain.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/brain/DesktopBrain.kt) | Frozen: serves the page's `brain.*` methods only and is removed in step E. See below. |

`DesktopServices.create` builds the services; [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt) attaches them to the WebView.

## When the companion exists

`DesktopServices.create` creates a `HovelCompanion` when `GAMERO_PRODUCT` (or
`-Dgamero.product`) is `hovel` and the OS is macOS. Codex (`GAMERO_CODEX_PATH`,
else `codex` on `PATH` or an installed location) is optional: without it the
Mac runs conversations on the runtimes it has, and no memory jobs. With Codex
and `GAMERO_WORKSPACE` set, the Mac also runs web Hovel's read-only Codex tasks
in that workspace (the task lane), which stays until step E removes the web
task UI. Both are read only from the environment.

Otherwise there is no companion and its `companion.*` methods answer
`unsupported`, but a `NativeBridge` is still attached for every product, so the
page can reach the shared bridge. On a product other than Hovel the window
shows nothing. The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app origin>`
(`hovelStateFolder` in `HovelStateFolder.kt`), so each site gets its own
registration, journals and output, whichever page it opens (`/hovel` or the old
`/hovel.html`). A directory from before, named by the full app URL, moves there
once on launch.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first. On app close, `DesktopResources.close` closes the bridge,
then `DesktopServices`, which closes the game, the companion, the pet,
providers and chat in that order.

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

Which cancels release what the page owned (`DesktopServices.invalidate`, which
ends the chat view, closes the game and clears a pending registration through
`HovelCompanion.invalidatePending`):

- A reload or navigation cancelling the open watch releases it.
- A newer `watchContext` replacing the open context releases it.
- Cancelling an older watch after the newer one is answered does not.
- Cancelling a still-pending watch (the client's timeout), or the open watch
  before a newer watch is answered, also releases it. This fails closed: the
  page must call `companion.prepareConnection` again.

Closing the bridge also releases it. Errors inside a method never reach the page in
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
   naming the account and the app URL, and saying a host credential goes into
   Keychain and this Mac then runs the account's conversations whenever Hovel is
   open, with the player's own provider sign-ins, working only in folders chosen
   on this Mac. Cancel, a reload or close fails the call.
2. **Registration.** `prepareRegistration` makes the data directory `0700`, takes
   a file lock on `registration.lock`, and reads or creates `registration.json`
   (HovelCompanion.kt lines 191-223).
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
   to `memory-output-<hostId>` on the player's own login. With
   `GAMERO_WORKSPACE` too, the task lane: a task journal (`journal-<hostId>`)
   and a Codex provider writing to `output-<hostId>`. Memory jobs run in an
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
- Routes allowed: exactly the `NativeRoute` table, each with its method and
  record ids in place of its parameters: `POST heartbeat`, `GET stream`,
  `POST conversations/claim`, `POST messages/:id/checkpoints|deltas|progress`,
  `GET projects/:id`, `PUT projects/:id/folders`, `GET approvals/:id`,
  `GET artifacts/:id/text?cursor=`, the task lane's `POST claim` and
  `POST progress`, and `POST memory/claim|progress|consolidate`. The Mac never
  answers an approval on a route of its own (see Approvals). The `brain/*`
  routes are allowed only when `brainEnabled` is true, which defaults to false
  and is not set by `HovelCompanion`.
- Redirects are never followed. Connect timeout 10 seconds, request timeout
  15 seconds, and the whole exchange including the body is bounded by
  `timeoutMillis` (default 15 seconds).
- Response bodies over 16 MiB are cut off and fail.
- HTTP `409` throws `HovelRejectedException`, meaning the server refused and
  committed nothing; its `code` is the body's, such as `update-required`. Other
  non-2xx codes throw `HovelStatusException` with the status; a 400, 403 or 404
  is `final`. The JSON `data` field is returned.
- `openStream` holds `GET stream` open as server-sent events, with no request
  timeout; closing it ends the connection.

## Conversations

`ConversationRunner` makes the Mac the worker for its projects' conversations.
The task lane and the memory lane ride its heartbeat.

### Connection

- **Stream:** it holds `GET stream` (`hostStreamEvent`s, as raw events or in the
  server's SSE envelope; heartbeats, comments and unknown event types are
  skipped), and reconnects with backoff from 1 to 30 seconds. A watchdog drops a
  stream that has sent no line (the server's own heartbeats included) for 75
  seconds, so a silently dead connection reconnects.
- **Heartbeat:** every 10 seconds, hovel-service's heartbeat body plus
  `computerHeartbeat`:
  - `providerState`: the task lane's own Codex readiness (`ready`,
    `auth-required`, `unavailable`), and `unavailable` without a task lane.
    Only hovel-service's task routing reads it, so web Hovel never queues a
    task to a Mac that cannot run it; conversation routing reads `directory`;
  - `features`: `provider-input` with the task lane, and `memory-context` and
    `memory-extraction` with the memory lane (`hovelHostFeatures`);
  - `directory`: the readiness and models of Codex, Claude Code and Grok Build;
  - `allowances`: each ready runtime's usage as an `allowanceSnapshot`.

  `HostDirectory` refreshes the directory every minute and usage every 5
  minutes. The answer goes to the task and memory lanes. Whether the heartbeat
  gets through or not, claims and outcomes that waited for the server are tried
  again and project folders are published; nothing in a round stops the
  schedule.

### Claiming and running

- `work.available` wakes that conversation's lane, which claims messages until
  none is left. One message runs at a time per conversation. At most 4
  conversations run at once (`MAX_RUNNING`); a lane waits for a slot before it
  claims, so a waiting message stays queued on the server.
- `stop.requested` and `message.cancelled` stop the running message, which
  settles `stopped`. A Stop that arrives while a claim is on its way applies
  once it lands: the message settles `stopped` before any provider turn. The
  server keeps stop requests and announces them again when the stream
  connects; until the message settles it also refuses each new step (a claim
  replay, a checkpoint, a delta, `running`) with 409 `stop-requested`. Any such
  refusal stops the message as the person's Stop does, and it settles
  `stopped`. A replayed claim journaled `claimed` settles `stopped` from the
  journal; one journaled only `claiming` never learned its message and is
  dropped. `approval.answered` closes the Mac's dialog when the phone answered
  first. `project.changed` reads the project and publishes its folders when the
  account's differ.
- `mac-update-required`, a 409 `update-required` and a claim this build cannot
  read leave the message queued and set the status line to "Update Hovel on
  this Mac to run every message in its conversations".
- A lane with nothing queued and nothing journaled is let go.

### Crash safety

Each claim is journaled in `ConversationStore` before the claim request, with the
rules `ExecutionJournal` keeps for memory jobs. The message id is only known once
the server answers, so the journal is keyed by claim id:

| Journaled phase | After a restart |
| --- | --- |
| `claiming`, `claimed` | Claim again with the same claim id; the server answers the same claim. |
| `launching` | Settle `reconciliation-required`; never run again. |
| `settling` | Deliver the decided outcome until the server takes it. |

The journal is read once when the store opens and kept as an index; an
unreadable entry is skipped and logged. A refusal (409) anywhere after the claim
lands settles the message (`stopped` after a Stop, else
`reconciliation-required`), so it never stays claimed. A 400, 403 or 404 on a
claim or its progress is final: the journal entry is dropped and logged once.
A claim that lands after Hovel began to quit stays `claimed` for the next
launch, with no provider turn.

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
  `artifact.stored` and `approval.opened`/`closed`. Each one is saved in the
  conversation record's outbox before it is sent, in the order the server needs
  (main-conversation entries, then threads, then thread entries, the run and a
  notice), so a thread is always upserted before its entries. The outbox is
  flushed at the start of a run, after each change and before the message
  settles; what the server could not take waits for the next flush, and one it
  refused is dropped. Deltas are best effort, cut to 2,048 characters and
  numbered per reply.
- **Inputs:** a text input's whole text is read with `GET artifacts/:id/text`,
  page by page, up to the brain's input bound. When the read fails, the turn
  says it works from the preview; a file attached on another device is named,
  and its bytes stay there.
- **Thread replies** build on the newer revision of this Mac's thread and the
  claim's.
- **Settling:** a completed run, or one waiting for the person, answers
  `answered`; Stop answers `stopped`; a paused run answers `failed`, and the
  person's retry of the same message reviews the run natively and resumes it.
- **Talk turns** run one at a time per Codex talk runtime, which holds one turn;
  working specialists run in parallel, each on its thread's lease, which
  `WorkRuntime.release(lease)` ends without touching other leases.
- **Crashes and quitting:** a run left working pauses for review when the
  conversation's next message runs, and a thread outside a run left `running`
  is interrupted; both are published. Quitting pauses the running run and
  publishes it (`run.changed`) before the message settles.

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
  a claim, a read or its own save). When none is known, the Mac reads
  `GET projects/:id`, so a project never claimed here still publishes; a conflict
  reads it again. A claim or read whose folders differ from this Mac's marks the
  project unpublished. A project on another computer, or a 403 or 404, marks it
  as another computer's.
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
answer wins:

- The person answers on the Mac: the answer is recorded only as the
  `approval.closed` (`answered`) checkpoint, fenced by the claim. When that is
  refused because another answer won, the Mac reads `GET approvals/:id` and the
  stored answer applies. When the winner cannot be read, the person's answer
  stands, never the dialog's default. When the server cannot be reached, the
  person's answer stands and its close waits in the outbox.
- Another surface answers first: `approval.answered` is read back with
  `GET approvals/:id` and closes the dialog as `answered-elsewhere`. If that read
  fails, the dialog stays open for the person.
- Nobody answers, or the work stops: the approval is withdrawn.

A late or repeated answer changes nothing, and each approval keeps a receipt.
The server expiry allows three dialog timeouts, since dialogs show one at a time
and the Mac withdraws an approval itself when its dialog ends unanswered. Folder
trust stays a Mac-only dialog.

### `host.contract`

`DesktopHostContract` answers the page's contract with `HovelContract.CURRENT`
and keeps the page's for its context. Every runtime arrived in the first
contract, so nothing is filtered by it yet.

`companion.capabilities` still answers `sandbox: "read-only"`, the page
contract's literal. It describes the task lane's Codex sandbox, not
conversation work, whose working specialists write in the project's working
folder after the start-of-work approval. It is revisited with the host-bridge
cleanup in step E, since changing it needs a contract change with version skew
for older pages.

### DesktopBrain is frozen

`DesktopBrain` serves the page's `brain.*` methods only and is removed in step
E. Fixes to how a coordinator runs go in `CoordinatorEngine`. The rules both
must apply alike are shared, so they cannot drift:

- `brain/BrainTransitions.kt`: the run state machine.
- `brain/RoleReadiness.kt`: role readiness (`roleIssues`) and stage selection
  (`stagesFor`, `asksBeforeStarting`).
- `brain/BrainPrompts.kt`: the protocol lines both coordinators send
  (`COORDINATOR_EVIDENCE_RULES`, `COORDINATOR_WORKFLOW_RULES`,
  `COORDINATOR_DECISION_SHAPES`) and the handoff of run inputs
  (`RunRecord.handoffInputs`). The brain's own protocol text is pinned by
  `SharedCoordinatorRulesTest`.

## Tasks

`HovelHostSession` takes the heartbeat answer the runner hands it. With the task
lane, its `tasks` are offered one at a time, as before: the first in `queued`,
`claimed` or `running` (`queued` only when Codex is ready), pinned to this host
in `local` mode (`hovelExecutionRequest`), through `ExecutionCoordinator` on
`claim` and `progress` (`HovelTaskRemote`). A follow-up runs the server's
`providerInput`, and recalled memory goes in its own section, byte for byte the
server's `hovelProviderPrompt` (`HovelProviderPromptTest`). Any failure accepting
a task sets "Task needs reconciliation on this computer; no automatic host
change". Step E removes this lane with the web task UI.

## Memory jobs

From the same heartbeat answer, `memoryJobs` are this computer's open jobs,
oldest first. If no memory job is running, it
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
task and memory lanes and their journals, and waits at most 5 seconds. Quitting is not the
person's Stop: a running message's run pauses for review and the message settles
`reconciliation-required` (or does at the next launch, from the journal), so the
person can retry it.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Another browser, an invalid frame, or a closed bridge. |
| `forbidden` | A child frame or another origin. |
| `invalid` | Not a `host-bridge.v2` request (version 1 included), over 64 Ki characters, or on the wrong query kind. |
| `expired` | Missing or old `contextId`, or a reload happened. |
| `failed` | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, other than a cancellation (HovelCompanion.kt line 129). Failures before that point (a closed companion, or a second call while one is pending) leave the status line unchanged and reach the page only as `failed` or `busy`. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId` (HovelCompanion.kt line 205). Users see only `failed` and the "Connection failed" status line above; check `registration.json` in the data directory. |
| Status line "Server unavailable; messages wait on the server" | A heartbeat failed. The runner keeps trying every 10 seconds; queued messages wait on the server. |
| Status line "Task needs reconciliation on this computer; no automatic host change" | Accepting a Hovel task failed, including network errors and a server 409. See the task journal before retrying. |
| Status line "Update Hovel on this Mac to run every message in its conversations" | A claim answered `mac-update-required`: a message needs a newer contract. It stays queued until this Mac updates or it expires. |
| A message settled `reconciliation-required` | Its turn may have started before a crash or an error the runner could not classify. It never runs again unless the person retries it. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the heartbeat's features, tasks and memory jobs:
their request, routes, output limit and refusals. `TrustedOriginTest` covers
the origin policy.
`HovelHostSessionTest` covers both lanes fed by a heartbeat, one item per lane,
unreadable and other providers' items, the task provider state and
consolidation. `HovelProviderPromptTest` checks the provider text against the
server fixture.

The `conversations` tests run against a fake hovel-service (`FakeServer`):
`ConversationRunnerTest` (parallel conversations, the cap, crash and journal
recovery, stop and cancel, a Stop while a claim is on its way, a claim landing
after quitting, final and update-required refusals, a refusal mid-run, expiry,
`mac-update-required`, offline outcomes, a failed heartbeat still retrying,
reconnects and the idle watchdog, lane eviction), `CoordinatorEngineTest`
(direct streaming, delegation and threads, workflow returns, `THREAD_REPLY` and
the newer thread revision, Stop, quitting, retry review, the outbox, whole input
text, crash recovery of threads, context folders), `RemoteApprovalsTest` (races,
refused closes and late answers), `PresentApprovalTest`, `ProjectFoldersTest`,
`ContextFoldersTest`, `ConversationStoreTest` and the wire tests.
`SharedCoordinatorRulesTest` pins the frozen brain's protocol and the shared
readiness and stage rules.
`CompanionContractFixtureTest` reads and writes back every payload in
`CompanionContractFixtures.kt`, which `tools/sync-server-fixtures.py` builds
from the server's samples with `tools/companion-fixtures.mts` and checks against
its zod schemas (it needs the server's `event-contracts` dependencies
installed), and checks `NativeRoute` against the server's
`HOVEL_COMPANION_ROUTES.native`. `ServerContractRunTest` runs the runner, the
engine and the real transport against a local server that answers only that
route table, with those payloads, and checks every body the Mac sends has the
shape of an accepted payload. `ContextFolderProbeTest` holds the live context-folder probes; they
run only with `HOVEL_CONTEXT_FOLDERS_CLAUDE_PROBE` or
`HOVEL_CONTEXT_FOLDERS_CODEX_PROBE` set.
Live JCEF,
Keychain and Codex need a Mac; follow
[hovel-native-validation.md](hovel-native-validation.md).
