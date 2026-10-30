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
| `NativeBridge` | [companion/NativeBridge.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/NativeBridge.kt) | JCEF message router. Checks the browser and frame, and hands every query to `JcefHostBridge`, which answers version 2 and refuses anything else. |
| `DesktopServices` | [DesktopServices.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/DesktopServices.kt) | Builds the companion with the other desktop services, registers their v2 packs and closes them in reverse order. |
| `TrustedOrigin` | [bridge/TrustedOrigin.kt](../composeApp/src/commonMain/kotlin/org/formulagen/wrapper/bridge/TrustedOrigin.kt) (commonMain) | Compares the frame's origin, read from JCEF, with the configured app URL's. Any path on that origin is trusted, so the page keeps the bridge between `/hovel` and `/companions/...`. |
| `HovelCompanion` | [hovel/HovelCompanion.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelCompanion.kt) | Handles the bridge methods, shows the approval dialog, owns the registration file, starts the host session at activation and again at every launch. |
| `MacHostCredentials` | [companion/MacHostCredentials.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/companion/MacHostCredentials.kt) | Stores and reads the host bearer token in the macOS Keychain. |
| `HovelHttpTransport` | [hovel/HovelHostAdapter.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/HovelHostAdapter.kt) | The only HTTP client that sends the host token to the server. |
| `MemoryJobLane` | [hovel/MemoryJobLane.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/MemoryJobLane.kt) | The Codex memory lane fed by the runner's heartbeat: one memory job at a time, and the consolidation timer. |
| `ConversationHost` | [conversations/ConversationHost.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationHost.kt) | Builds the runner for the registered account, with runtime instances of its own, and owns `project.*`. |
| `ConversationRunner` | [conversations/ConversationRunner.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationRunner.kt) | The host stream and its watchdog, the heartbeat, claims, the claim journal and one serial lane per conversation. Also defines `MessageEngine`. |
| `CoordinatorEngine` | [conversations/CoordinatorEngine.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/CoordinatorEngine.kt) | Runs one claimed message through the companion brain: coordinator, threads, workflow, and the outbox of checkpoints. |
| `ProviderTurns` | [conversations/ProviderTurns.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ProviderTurns.kt) | The engine's provider turns: readiness, model resolution, talk permits, work leases and Stop. |
| `ConversationPrompts` | [conversations/ConversationPrompts.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/conversations/ConversationPrompts.kt) | `CONVERSATION_PROTOCOL` and the coordinator's and specialists' inputs. |
| `NativeRoute` | [hovel/NativeRoutes.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/hovel/NativeRoutes.kt) | Every route a registered Mac calls: one table of method, path template and builder, which is also what the transport allows. |

`DesktopServices.create` builds the services; [main.kt](../composeApp/src/jvmMain/kotlin/org/formulagen/wrapper/main.kt) attaches them to the WebView.

## When the companion exists

Launch it for Hovel like this (see [desktop.md](desktop.md) for the browser
runtime):

```sh
# game-platform: serve the app over loopback HTTP on 8133 (GAMERO_CATALOG_PORT changes it).
# JCEF rejects the dev server's basic-ssl certificate, so use this launcher, not the HTTPS dev server.
(cd <game-platform>/apps/game-bridge-host && node --import tsx tools/companionLauncher.ts)

# gamero, from the repository root (Gradle on JDK 21):
GAMERO_PRODUCT=hovel \
GAMERO_WEB_URL=http://127.0.0.1:8133/hovel \
GAMERO_BROWSER_JAVA_HOME="<JBR with JCEF>/Contents/Home" \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:run
```

`DesktopServices.create` creates a `HovelCompanion` when `GAMERO_PRODUCT` (or
`-Dgamero.product`) is `hovel` and the OS is macOS. Codex (`GAMERO_CODEX_PATH`,
else `codex` on `PATH` or an installed location) is optional: without it the
Mac runs conversations on the runtimes it has, and no memory jobs. Codex's path
is read only from the environment.

Otherwise the companion's v2 methods answer `unsupported`. On a product other
than Hovel the window shows nothing.
The companion's data directory is
`~/Library/Application Support/Gamero/hovel-<sha256 of the app origin>`
(`hovelStateFolder` in `HovelStateFolder.kt`), so each site gets its own
registration, journals and output, whichever page it opens (`/hovel` or the old
`/hovel.html`). A directory from before, named by the full app URL, moves there
once on launch.

A new `NativeBridge` is attached each time the WebView is created, and the old
one is closed first. On app close, `DesktopResources.close` closes the bridge,
then `DesktopServices`, which closes the game, the companion, the pet and
providers in that order.

## Bridge trust rules

The page calls `window.gameroQuery({request, persistent, onSuccess, onFailure})`.
The router names are `gameroQuery` and `gameroQueryCancel`.
[tools/native-bridge-smoke.html](../tools/native-bridge-smoke.html) is a minimal
working client.

Every request goes through `NativeBridge.onQuery`, then `JcefHostBridge.onQuery`
(see [host-bridge.md](host-bridge.md)), and is refused unless all of the
following hold:

1. **Same browser, valid frame, main frame, trusted origin.** The browser id must
   match, the frame must be valid and the main frame, and `TrustedOrigin` must
   match the frame URL's scheme, host and port against the app URL. Default
   ports are filled in, so `https://host` equals `https://host:443`. URLs with
   user info are rejected. The origin comes from JCEF's frame facts, never from
   the request body. An untrusted frame's version 2 request gets the
   `forbidden` envelope; anything else from it gets `403`.
2. **Version 2.** The body must be a host-bridge v2 message. Anything else,
   the companion's old version 1 protocol included, gets `400 Invalid native
   protocol` and reaches no capability.
3. **A live context.** The page first opens a persistent `watchContext` query;
   every call carries that context, and a navigation or reload ends it.

The page's version 2 context ending calls `DesktopServices.invalidate`, which
closes the game and voids the connection approval
(`HovelCompanion.invalidatePending`). Errors inside a method never reach the
page in detail: it gets the `failed` envelope.

`TrustedOriginTest` covers the origin rules, and `JcefHostBridgeTest` the
frame check, the refusal of anything that is not version 2, and a page moving
between `/hovel` and `/companions/...`. The browser check in `NativeBridge`
needs a live JCEF.

## Bridge methods

`HovelCompanionCapability` serves `companion.*` through `HovelCompanion.dispatch`:

| Method | Args | Returns |
| --- | --- | --- |
| `companion.capabilities` | `{}` | `connectComputer` true |
| `companion.prepareConnection` | `ownerId` (UUID) | host `id`, `name`, and `tokenHash` |
| `companion.activateConnection` | `ownerId`, `hostId` | host `id`, `connected` true |

Only one `prepareConnection` or `activateConnection` runs at a time; a second
while one is pending answers `busy`. Each call records the companion's
generation when it starts and fails if `invalidatePending` or `close` bumps it
before the call finishes.

`invalidatePending` also clears the approved registration. So after any reload,
`activateConnection` fails with "Native approval is required" until the page
calls `prepareConnection` again.

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
- Routes allowed: exactly the `NativeRoute` table, each with its method and
  record ids in place of its parameters: `POST heartbeat`, `GET stream`,
  `POST conversations/claim`, `POST messages/:id/checkpoints|deltas|progress`,
  `POST conversations/:id/messages`, `GET projects/:id`,
  `PUT projects/:id/folders`, `GET approvals/:id`,
  `GET artifacts/:id/text?cursor=`, `GET devices`, `POST devices/lookup`,
  `POST devices/pair`, `POST devices/:id/unpair` (Hovel → Pair a phone…, see
  Approvals), and `POST memory/claim|progress|consolidate`. The Mac never
  answers an approval on a route of its own (see Approvals).
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
The memory lane rides its heartbeat.

### Connection

- **Stream:** it holds `GET stream` (`hostStreamEvent`s, as raw events or in the
  server's SSE envelope; heartbeats, comments and unknown event types are
  skipped), and reconnects with backoff from 1 to 30 seconds. A watchdog drops a
  stream that has sent no line (the server's own heartbeats included) for 75
  seconds, so a silently dead connection reconnects.
- **Heartbeat:** every 10 seconds, hovel-service's heartbeat body plus
  `computerHeartbeat`:
  - `providerState`: from the directory (`ProviderDirectory.providerState`):
    `ready` when a runtime is ready, else `auth-required` when one is signed
    out, else `unavailable`; conversation routing reads `directory`;
  - `features`: `memory-extraction` with the memory lane
    (`MemoryJobLane.FEATURES`), and none without it;
  - `directory`: the readiness and models of Codex, Claude Code and Grok Build;
  - `allowances`: each ready runtime's usage as an `allowanceSnapshot`.

  `HostDirectory` refreshes the directory every minute and usage every 5
  minutes. The answer (`{ host }`, plus `memoryJobs` for the memory-extraction
  computer) goes to the memory lane. Whether the heartbeat
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
- The Mac was not listening (its host stream was down): on every reconnect of
  the host stream it reads each approval still open here (`catchUp`), so an
  answer it missed closes its dialog then.
- Nobody answers, or the work stops: the approval is withdrawn. When that
  withdrawal is refused because a phone's answer reached the server first, the
  Mac reads the stored answer and applies the phone's answer instead of the
  dialog's default.
- A close the Mac could only queue while offline, and that the server later
  refuses because a phone answered first: the Mac already went on with its own
  answer (or with none), so it appends a system note to the conversation or
  thread saying the phone's answer is what Hovel shows and this Mac went on with
  its own. The note names options with the Mac's own buttons
  (`MacApprovalLabels`, "Start working" rather than "Continue") and takes what
  ran from the queued close itself, so it survives a restart.

A late or repeated answer changes nothing, and each approval keeps a receipt.
The server expiry allows three dialog timeouts, since dialogs show one at a time
and the Mac withdraws an approval itself when its dialog ends unanswered. Folder
trust stays a Mac-only dialog.

**Pair a phone…** A phone answers this Mac's approvals only once this Mac
confirms it. The person opens Hovel → Pair a phone… (the window never opens on
its own) and types the code the phone shows in its own alert. The Mac looks the
code up (`POST devices/lookup`) and asks "Pair <name> (<platform>, registered
<n> min ago)?" until that code runs out; only on that second click does it pair
(`POST devices/pair`, naming that phone, so the code pairs it or none). A
confirmation that comes after the code ran out is said so and is not counted as
a wrong code.
The window lists the phones this Mac confirms (`GET devices`), with Unpair for
each (`POST devices/:id/unpair`). All of them go on this Mac's own host token
(`PhonePairing`, `PhonePairingWindow`); no page reaches them. The server counts
wrong codes across all the account's Macs, and tells the owner of every
pairing.

### Voice

Mac voice runs in project conversations through `conversationVoice.*`
(`DesktopConversationVoice`). `conversation/voice/TalkLoop.kt` holds the
hands-free state machine, fillers, barge-in, the reply spoken sentence by
sentence through `SentenceCursor`, dictation and the latency marks, and its host
(`TalkHost`) is the conversation. A `…Locked` member
of the loop needs its host's lock; `click`, `escape` and `toggleFillers` take
it themselves.

- **One conversation at a time.** `VoiceConversation` is bound to one
  conversation id, with its own share of the microphone. A page's action moves
  the voice to the conversation it names, unless Talk or dictation is on in
  another; the answer then names the conversation the voice serves, and the
  page shows its own controls as waiting.
- **A heard turn** is decided by the talk loop (empty text and noise under
  240 ms send nothing; a reply in progress ignores it) and posted, off the
  lock, as the owner's message through `POST conversations/:id/messages`
  (`messageSend`, at most 16,000 characters, no inputs). An unreachable server
  is tried twice more with the same id; a 403 or 404 says "Talk works in
  conversations whose project runs on this Mac.". After the post the runner
  claims the conversation's next message at once (`ConversationRunner.wake`),
  with the same claim, journal and fencing as `work.available`. A stored turn
  not claimed within 30 seconds (this Mac is busy) lets Talk listen again, and
  Talk says "What you said is waiting in the conversation. It runs when this
  Mac is free." From then on it is the person's message in the conversation:
  ending Talk, moving the voice or quitting never gives it up, and it runs when
  claimed. Stop on the page still cancels it.
- **The reply in progress** is the conversation's claimed main message, from
  its claim to its end (`ReplyListeners`, asked once the claim lands; a thread
  reply's claim is not one). Its coordinator replies are spoken as they stream
  (`ClaimOutput.delta` and `ClaimOutput.wrote`, before either reaches the
  server) when it is this Talk session's heard turn, Talk is on, or the
  conversation's `speak` is on; never while the page dictates. A non-streamed coordinator message (a
  question, a delegation's announcement) is spoken whole, and each new reply
  cuts off what is left of the previous one. Threads are never spoken. A
  claimed message of a conversation the voice does not serve is read aloud
  only when that conversation's `speak` is on and nothing is in progress, and
  the voice then serves it; the first reply keeps the speaker.
- **Barge-in** (speech in full duplex, a press, or Escape) always flushes the
  speaker. It stops the message, as Stop does, only while that message is this
  Talk session's own heard turn (one claimed after the 30 second wait
  included) and no thread has started (`threadStarted`). Once the coordinator
  has delegated, the agents' run continues: the reply being said goes quiet and
  a later reply in the run is still spoken. The person's Stop on the page still
  stops everything. A barge-in during a run never abandons a newer heard turn
  waiting behind it.
- **While agents work** on a run Talk waits for, Talk says "Agents are working
  on this. Talk listens again when they finish; Stop interrupts them." until
  the run ends: the microphone stays closed in half duplex, and in full duplex
  what is heard is not sent.
- **Ending Talk**, a microphone failure included, flushes the speaker,
  abandons a heard turn not claimed yet (not one Talk said is waiting), and
  says nothing more of the run. It never stops a claimed message, and it ends
  the microphone session only when one was on, so a dictation holding the
  microphone goes on. A capture that starts after Talk ended (the row's icon,
  while the page's capture was starting) lets the microphone go.
- **The page's actions** keep `ember-hearing.v1`'s words, with these meanings
  in a conversation: `handsFree` arms Talk (or interrupts a reply when it is
  on); `press` interrupts a reply and never arms (the page's Escape); `release`
  quiets the speaker and mutes the rest of the run, leaving Talk and dictation
  on (Speak replies off); `off` ends Talk, and with Talk off only quiets;
  `mute` and `unmute` hold the microphone. Only `handsFree` makes the page
  Talk's owner.
- **An abandoned heard turn never runs.** Its id is kept by
  `DesktopConversationVoice` and checked before any routing, its remaining post
  attempts are skipped, and `ConversationRunner.stopOnClaim` journals a stop on
  claim in `ConversationStore` (`stop-on-claim/<message id>.json`). Whenever it
  is claimed, after the voice moved, the page went away or Hovel restarted, it
  settles `stopped` with no provider turn, and the mark is cleared once that
  outcome is journaled. At quit or sign-out the voice gives up its heard turn
  not claimed yet while the account link is still up (`connected(null)`), so it
  is journaled too; one given up with no link is logged and held in memory
  only. A mark older than a queued message lives (24 hours,
  `CompanionLimits.QUEUED_MESSAGE_MS`) is dropped at startup, unless its
  message has a claim journaled.
  The project runs on one Mac, so this Mac's journal is the whole rule; no
  server route is involved.
- **The microphone** is one `SharedHearing`: each conversation holds its own
  share, and only one captures at a time. Another, or a
  capture that cannot start, is told "The microphone is in use. Try again in a
  moment.".
- **This Mac's projects only.** `project.folderStatus` asks the account
  (`GET projects/:id`) whether the project runs on this Mac and fails for any
  other, so a page offers voice, and folders, only on the project's own Mac.
  The answer is recorded as `ProjectFolderPublisher` records a read: this
  Mac's project is claimed, so its folders can be chosen again, and another
  computer's is marked foreign. A project the account confirmed answers at
  once and is checked again in the background; when the account cannot be
  reached, what this Mac last learned stands.
- **Escape.** The page owns Escape for conversations: it sends `press` only
  when it has no other use for the key and the key is not ending a
  composition. No page Escape reaches conversation voice natively: the
  conversation's voice row hears Escape only while it has focus, where it
  interrupts or ends that conversation's Talk.
- **The voice row** shows the served conversation in Hovel too (its page has no
  voice choice or filler toggle). Its icon ends a session the page started and
  never starts one the page owns. Its first arm, which starts the microphone,
  runs off the window's thread.
- **Settings** are one `TalkSettings` for every conversation:
  the filler choice (remembered in `~/.gamero/voice-fillers`), the duplex mode
  (the hearing route's, which falls back to half duplex when the graph fails),
  and the timings. A choice made anywhere holds in the next conversation and
  after the page reloads. Every arm, the row icon's included, warms the voice
  engine (`ChatSpeech.warm`) and, when fillers are on, the fillers; serving a
  conversation warms nothing. A new spoken stream re-reads the voice choice.
- **The page going away** lets go of the microphone and stops speaking; the
  conversation's messages keep running.

One `DesktopSpeech` speaks for every conversation. Its engine loads only on its
voice worker thread (`hovel-voice`).

Latency, measured hermetically (`ConversationVoiceLatencyTest`, run with
`HOVEL_VOICE_LATENCY=1`: an instant provider and a fake server). Endpoint to
the first sentence queued at the speaker is about 35 to 40 ms in a
conversation, and the test fails a median of 150 ms or more. Most of it is the
claim journal's four durable writes before the provider turn (claiming,
claimed, launching, running), each a file and folder sync. A real server adds
three round trips (send, claim, running) before the provider's own first
token. The log lines are `latency endpoint_to_posted_ms` and
`latency endpoint_to_claimed_ms`, beside the talk loop's stage latencies.

### `host.contract`

`DesktopHostContract` answers the page's contract with `HovelContract.CURRENT`
(3) and keeps the page's for its context. A page below contract 3
(`HovelContract.COMPANIONS_ONLY`, the contract's
`HOVEL_CONTRACT_FEATURES.companionsOnly`) is from before step E: the handshake
answers it `failed` with the update message (`BridgeUpdateRequiredException`),
and so does every `companion.*`, `project.*` and `providers.*` call in that
context (`requireCurrentPage`, through `desktopProductPacks`' `pageCheck`). A
context that never traded contracts is not refused. `providers.list` leaves out any runtime
newer than the page's contract (`HovelContract.runtimesFor`, from
`HovelContract.RUNTIMES`, which mirrors the server's `HOVEL_CONTRACT_RUNTIMES`).
Every runtime arrived in the first contract, so nothing is left out yet.

`companion.capabilities` answers only `{ "connectComputer": true }`.

### Coordinator rules

Fixes to how a coordinator runs go in `CoordinatorEngine`. Its rules live in
`brain/`:

- `brain/BrainTransitions.kt`: the run state machine.
- `brain/RoleReadiness.kt`: role readiness (`roleIssues`) and stage selection
  (`stagesFor`, `asksBeforeStarting`).
- `brain/BrainPrompts.kt`: the protocol lines the coordinator sends
  (`COORDINATOR_EVIDENCE_RULES`, `COORDINATOR_WORKFLOW_RULES`,
  `COORDINATOR_DECISION_SHAPES`) and the handoff of run inputs
  (`RunRecord.handoffInputs`). `SharedCoordinatorRulesTest` checks that
  `CONVERSATION_PROTOCOL` carries every rule line.

## Memory jobs

`MemoryJobLane` takes the heartbeat answer the runner hands it. Its
`memoryJobs` are this computer's open jobs,
oldest first. If no memory job is running, it
picks the first one in `queued`, `claimed` or `running` (`queued` only when
Codex is ready) whose `kind` is `extract` and host-bridge `runtimeId` is
`codex`. A job naming another kind or runtime is left for a computer that runs
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

`close()` stops the runner, the stream, the heartbeat and the conversation
lanes, then the memory lane and its journal, and waits at most 5 seconds. Quitting is not the
person's Stop: a running message's run pauses for review and the message settles
`reconciliation-required` (or does at the next launch, from the journal), so the
person can retry it.

## Failure messages to know

| Seen | Meaning |
| --- | --- |
| `403 Native request unavailable for this frame` | Wrong frame, wrong origin or closed bridge, for a request that is not version 2. |
| `400 Invalid native protocol` | The request is not a host-bridge v2 message, such as the companion's old version 1. |
| `failed` envelope | A bridge method threw. The page never sees the reason. |
| Status line "Connection failed. Check the native configuration, Keychain approval and server." | A `prepareConnection` or `activateConnection` failure, or an unknown method name, other than a cancellation. Failures before that point (a closed companion, another call pending) leave the status line unchanged and reach the page only as an envelope. This status replaces the specific error, so the causes below never reach the window. |
| (not shown) "This native profile belongs to another account" | Thrown when `registration.json` has a different `ownerId`. Users see only the two generic messages above; check `registration.json` in the data directory. |
| Status line "Server unavailable; messages wait on the server" | A heartbeat failed. The runner keeps trying every 10 seconds; queued messages wait on the server. |
| Status line "Update Hovel on this Mac: the server needs a newer version" | The heartbeat answered 409 `update-required`: the server stored the beat, so this Mac reads as online and the page asks the person to update, but sends no memory jobs. The runner keeps beating, and conversation routes keep working while the server serves them. |
| Status line "Update Hovel on this Mac to run every message in its conversations" | A claim answered `mac-update-required`: a message needs a newer contract. It stays queued until this Mac updates or it expires. |
| A message settled `reconciliation-required` | Its turn may have started before a crash or an error the runner could not classify. It never runs again unless the person retries it. |

## Tests

```sh
./gradlew :composeApp:jvmTest --console=plain
```

`HovelAdapterTest` covers registration reuse and that the token is not in
`registration.json`, invalidation during a blocked activation, the transport
deadline on a stalled body, the origin policy, and memory jobs: their request,
routes, output limit and refusals. `MemoryJobLaneTest` covers the lane fed by a
heartbeat, one job at a time, unreadable and other providers' jobs, the
declared feature and consolidation.

The `conversations` tests run against a fake hovel-service (`FakeServer`):
`ConversationRunnerTest` (parallel conversations, the cap, crash and journal
recovery, stop and cancel, a Stop while a claim is on its way, a claim landing
after quitting, final and update-required refusals, a refusal mid-run, expiry,
`mac-update-required`, offline outcomes, a failed heartbeat still retrying,
reconnects and the idle watchdog, lane eviction), `CoordinatorEngineTest`
(direct streaming, delegation and threads, workflow returns, `THREAD_REPLY` and
the newer thread revision, Stop, quitting, retry review, the outbox, whole input
text, crash recovery of threads, context folders), `VoiceConversationTest`
(a heard turn posted, claimed at once and spoken sentence by sentence, the
16,000-character bound, the mic held while thinking, barge-in before and after
delegation, ending Talk or a failed microphone never stopping a claimed reply,
an abandoned turn across a move, the page going away and a restart, `speak`
and turning it off, one reply cutting off the last, threads never spoken,
refusals, retries and a resend after a lost answer, the claim wait, dictation,
one microphone and one conversation at a time, the voice row, the filler
choice across conversations and reloads), `TalkLoopTest` (the loop with a fake
host), `ConversationVoiceLatencyTest` (with `HOVEL_VOICE_LATENCY=1`),
`RemoteApprovalsTest` (races,
refused closes and late answers, catch-up on reconnect, a refused withdrawal taking the
phone's answer, and the conflict note across a restart in the Mac's own words),
`PhonePairingTest` (lookup before pairing, one pairing per code, the attempt
limit, unpairing), `PresentApprovalTest`, `ProjectFoldersTest`,
`ContextFoldersTest`, `ConversationStoreTest` and the wire tests.
`SharedCoordinatorRulesTest` pins the coordinator's protocol lines and the
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
