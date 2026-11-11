# Companion folders: architecture, step 2

Status: slice 2a is built (2026-11-11); slices 2b and 2c are not started.
Covers step 2 of
`companion-folders.SPEC.md`: shared notes, the scratch area, and the
coordinator's view of the project. Step 1's first slice is built
(`companion-folders.ARCH.md`). No code for step 2 is written.

## What the code is like today

Read before designing. Each point names where it is.

- **The coordinator already decides in a loop.** `CoordinatorEngine.loop()`
  calls `decideRun()` until the run stops working. Each call asks for one
  decision among the allowed kinds (`DecisionKind`), as a JSON header, with
  one repair attempt (`decide`). A new kind of decision that changes nothing
  in the run fits this loop as it is.
- **A brain with no specialists takes another path.** `direct()` runs one
  streaming answer with an optional header and no loop.
- **What reaches a turn is built in a few places.** The coordinator's request
  in `conversationRequest` (where `projectFolder` and `spokenAnswer` already
  ride), and a specialist's input in `specialistTurn`, `delegatedTurn` and
  `forwardedTurn`.
- **A progress line needs no new contract.** The engine can publish
  `ConversationCommunication.Progress(purpose)` itself. It shows in the
  conversation, and the page's Talk shows it as its status.
- **A specialist's reply is plain text, streamed as it is written**
  (`ReplyStream(headed = false)`). Anything that must be cut out of a reply
  would have to be held back from the stream.
- **A reply already carries a structured report.** A coordinator's handoff
  report (`ConversationCommunication.Handoff`: what changed, the checks,
  what needs attention) and each delegated task are recorded today.
- **Records decode strictly** (`brainJson`, `ignoreUnknownKeys = false`). A
  new field on `ConversationRecord` would make a record unreadable to the
  build before it. New state goes in its own file.
- **Approvals are raced between the Mac's dialog and the account's other
  surfaces** (`RemoteApprovals.ask`), by kind. The contract knows four kinds:
  `permission`, `start-of-work`, `stage`, `resume-review`. The page labels
  each kind.
- **A start-of-work approval is kept in memory only** (`StartOfWork`, a set).
- **A working specialist is given folders by its provider's own mechanism.**
  Claude Code gets read-only folders through `--add-dir` with Edit and Write
  denied. Codex gets a sandbox whose only writable root is the working
  folder. Both are marked unverified (`ContextFolderSupport.UNVERIFIED`).
- **A handoff already passes files.** A working specialist's inputs name the
  artifact files in the private store (`handoff(…, files = true)`).
- **Memory cleanup erases through one hook**, `ConversationStore.eraseContext`.

Four findings change what the spec can promise.

1. **The scratch area is the one part that needs a provider's folder
   mechanism**, and both are unproven. Making a second folder writable means
   changing how each provider is started, then proving it with a live turn
   on each. Meanwhile a handoff already carries one specialist's output to
   the next as a file.
2. **A note cut out of a reply would fight the stream.** The reply is on the
   page as it is written. Removing a marked block afterwards would show it
   and then take it away.
3. **A reading approval the phone can answer needs a new approval kind.**
   Reusing `start-of-work` would show "Start of work" on the phone for a
   request to read. A Mac-only dialog needs no contract, but then a
   conversation held from the phone waits on a dialog nobody sees.
4. **The brief is so cheap that storing it costs more than building it.** On
   1,014 files it took 3 ms. A stored brief needs a place, a rebuild rule
   and a cleanup rule; a brief built for each message needs none.

## Data and interfaces

All in `composeApp/src/jvmMain/…/conversations/` unless said. Names are
proposals.

### The project brief

```kotlin
/** What a brief may hold. All are starting values. */
internal object BriefLimits {
    const val CHARS = 10_000
    const val README_CHARS = 1_500
    const val FOLDERS = 60
    const val FILES_PER_FOLDER = 12
}

/**
 * A short description of [folders]' working folder: its manifest, the start
 * of its README and a bounded listing. Null when there is no working
 * folder. Pure apart from reading the disk; nothing is stored.
 */
internal fun projectBrief(folders: ProjectFolders, guard: PathGuard): String?
```

The brief is text, not a structure: it is read by a model and by nothing
else. It joins the folder note in `projectFolderNote`'s place in the turn.

### The path check

```kotlin
/**
 * Says what may be read for the coordinator. The one place that decides.
 * [roots] are the project's working folder, its read-only folders and the
 * conversation's visible folder.
 */
internal class PathGuard(roots: List<Path>) {
    /** [asked] as a real path inside a root and not skipped, or why not. */
    fun check(asked: String): Checked

    sealed interface Checked {
        data class Allowed(val path: Path, val shown: String) : Checked
        data class Refused(val shown: String, val reason: Refusal) : Checked
    }
    enum class Refusal { OUTSIDE, SKIPPED, MISSING, NOT_TEXT }
}

/** Never read and never named: fixed, the same in every project. */
internal object SkipList {
    val FOLDERS = setOf(".git", "node_modules", "build", "dist", ".next", ".gradle", "DerivedData", "target", ".venv")
    val FILES = listOf(".env", ".env.*", "*.pem", "*.key", "*.p12", "id_rsa*", "id_ed25519*")
}
```

`shown` is the path as the coordinator and the person see it: relative to
its root, never absolute.

### Reading

```kotlin
/** A new decision. It changes nothing in the run; the engine answers it. */
@Serializable @SerialName("read")
data class Read(
    val files: List<String> = emptyList(),
    val list: List<String> = emptyList(),
    val search: String? = null,
) : CoordinatorDecision

/** One message's allowance. All are starting values. */
internal object ReadLimits {
    const val ROUNDS = 2
    const val CHARS = 32_000
    const val FILES_PER_ROUND = 12
    const val SEARCH_MATCHES = 40
}

/** What a round of reading gave, as the next request carries it. */
internal data class ReadResult(val text: String, val read: List<String>, val refused: List<String>)

/** Reads for one message, within its allowance. Holds the count; one per `MessageRun`. */
internal class ProjectReader(private val guard: PathGuard) {
    val spent: Boolean
    fun answer(request: CoordinatorDecision.Read): ReadResult
}
```

The header shape the coordinator is taught:
`{"kind":"read","files":["README.md"],"list":["src"],"search":"sign in"}`.

### Notes

```kotlin
/** One line of a conversation's shared log. */
@Serializable
internal data class Note(
    val at: String,           // ISO-8601 instant
    val by: String,           // the role's name, or "Hovel" for what the engine wrote
    val kind: NoteKind,
    val text: String,         // at most NoteLimits.CHARS
)

@Serializable
internal enum class NoteKind {
    @SerialName("task") TASK,         // the coordinator delegated this
    @SerialName("output") OUTPUT,     // a specialist finished, with its output's name and start
    @SerialName("report") REPORT,     // the coordinator's handoff report
    @SerialName("note") NOTE,         // an agent's own note
}

internal object NoteLimits {
    const val CHARS = 2_000
    const val TURN_CHARS = 8_000
    const val KEPT = 400              // oldest dropped first
}

/** `<conversation>/notes.json` in the private store, beside the record. */
internal class NotesStore(conversations: Path) {
    fun add(conversationId: String, note: Note)
    fun recent(conversationId: String, chars: Int = NoteLimits.TURN_CHARS): List<Note>
    fun eraseAll()
}
```

`CompanionFolders.sync` gains the notes, and writes `notes.md` beside
`index.md`.

### Remembered approvals

```kotlin
/** What an approval was given for. Any field that differs asks again. */
@Serializable
internal data class Approved(
    val conversationId: String,
    val projectId: String,
    val folder: String,
    val runtimeId: RuntimeId,
    val modelId: String,
    val purpose: ApprovedFor,
    val at: String,
)

@Serializable
internal enum class ApprovedFor { @SerialName("work") WORK, @SerialName("read") READ }

/** `<account>/approvals.json`. Replaces the in-memory set in `StartOfWork`. */
internal class ApprovalMemory(root: Path, accountId: String) {
    fun has(approved: Approved): Boolean
    fun remember(approved: Approved)
    fun withdraw(approved: Approved)
    fun all(): List<Approved>
}
```

### Scratch

```kotlin
/** Whether a work runtime can be given a second folder to write in. As `ContextFolderSupport`. */
internal enum class ScratchSupport { VERIFIED, UNVERIFIED, NONE }

// WorkScope gains one field. Null for a runtime that is not VERIFIED.
val scratch: Path? = null

internal object ScratchLimits {
    const val KEPT_DAYS = 7
}
```

The folder is `<conversation's visible folder>/scratch/`, made by
`VisibleFolders` when a working turn first needs it.

### Contract changes (slice 2b only)

In `server-platform/event-contracts`:

- `APPROVAL_KINDS` gains `read-files`, with the options `continue` and
  `not-now`.
- The host bridge gains `project.approvals` (list) and
  `project.withdrawApproval`, for the project's settings.

## Design

### The brief

Built for each message, inside the claim, by walking at most 60 folders and
skipping the skip list. Nothing is stored and nothing is rebuilt: the walk
is bounded, so its cost does not grow with the project. It rides on the
turn, never in the persona, as `projectFolder` does today.

### Reading

`read` joins the allowed decisions of a main-conversation turn. In
`decideRun`, a `Read` decision is handled before the run machine sees it:

1. If this conversation has no reading approval, ask. A refusal is told to
   the coordinator in its next request, and `read` leaves the allowed kinds.
2. `ProjectReader.answer` checks each path with `PathGuard`, reads what is
   allowed, and counts it against the message's allowance.
3. The engine publishes a progress line: "Read README.md and 2 more."
4. The loop runs `decideRun` again. The request carries the result as
   `projectFiles`, marked as data.
5. When the allowance is spent, `read` leaves the allowed kinds, and the
   request says so. The coordinator then answers or delegates.

`direct()` gets the same handling in a small loop of its own, since a brain
with no specialists is the simplest case of "what does my README say?".

In Talk there is no special spoken line. The page's thinking phrase already
fills the silence 300 ms after the person stops talking, and the progress
line becomes Talk's status.

### Notes

Hovel writes most of the log itself, from what it already records. No model
has to follow a protocol for these:

- when the coordinator delegates: the specialist and the task;
- when a specialist finishes: its output's name and first lines;
- when the coordinator reports a handoff: what changed and what needs
  attention.

An agent adds its own note by ending its reply with a section headed
`## Notes for the team`. Hovel copies that section into the log. The section
stays in the reply, where the person can read it too, so nothing is cut
from the stream.

Every turn of the conversation is given the most recent notes, as data, in
the builders listed above.

### Scratch

A working specialist's turn is given the conversation's scratch folder as a
second place it may write, and its task says so. Each provider needs its
own mechanism, and neither is proven:

- **Claude Code:** `--add-dir <scratch>`, with none of the deny rules that
  make a context folder read-only.
- **Codex:** the `workspace-write` sandbox with two writable roots, the
  working folder and the scratch folder. Today that sandbox is set only when
  a project has context folders; otherwise the person's own sandbox setting
  stands. Giving scratch to Codex therefore sets the sandbox for every
  working turn, which is a change to how Codex runs.

Each is proven by a live turn before it is given: one short turn on the
person's own subscription, in throwaway folders, that must write a file in
scratch, read it back, and be refused or asked when it writes outside both
folders. It follows `ContextFolderProbeTest`. A runtime that passes is
marked `VERIFIED` and gets scratch; one that does not is left as it is.

Cleanup is Hovel's: scratch is emptied when the run that used it completes,
and a sweep at connect deletes what is older than seven days.

### Remembered approvals

`StartOfWork` keeps its interface and reads and writes `ApprovalMemory`
instead of a set. That is the whole change for start of work.

### Pieces and where they go

| Piece | New or changed |
| --- | --- |
| `ProjectBrief.kt`: `projectBrief`, `BriefLimits` | New |
| `PathGuard.kt`: `PathGuard`, `SkipList` | New |
| `ProjectReader.kt`: `ProjectReader`, `ReadLimits` | New |
| `ConversationNotes.kt`: `Note`, `NotesStore`, the section parser | New |
| `ApprovalMemory.kt` | New |
| `CoordinatorDecision.Read`, `DecisionKind.READ`, its header shape | Changed, in `brain/` |
| `CoordinatorEngine`: the brief, the read branch, the note calls | Changed |
| `ConversationPrompts`: the brief, `projectFiles`, `teamNotes`, the notes rule | Changed |
| `VisibleFolders`: `notes.md` | Changed |
| `StartOfWork`: backed by `ApprovalMemory` | Changed |
| `WorkScope.scratch`, `ScratchSupport`, each work runtime's launch | Changed, slice 2c |
| `ScratchProbeTest` | New, slice 2c |
| `ConversationStore.eraseContext`: also erases notes | Changed |

### Failure handling

- A brief that cannot be built is left out of the turn. The folder note
  still goes.
- A path that is refused is named in the result with its reason. The
  coordinator is never told a file's text it was not given.
- A note that cannot be stored is dropped and logged. A conversation never
  stops for a note.

### Alternatives considered

- **A marked block in the reply, removed before it is stored.** Rejected:
  finding 2.
- **Notes only written by Hovel, with no section for agents.** Simpler, and a
  fair first cut. Kept out only because the section costs one parser and
  one sentence of instruction.
- **Storing the brief and rebuilding it on change.** Rejected: finding 4.
- **Reusing `start-of-work` for reading.** Rejected: finding 3.
- **Reading as a tool call, not a decision.** Rejected in the spec: the
  coordinator keeps no tools on any provider.

### What changed while building slice 2a

- **The notes are `TeamNote`, `TeamNoteKind` and `TeamNotes`**, in
  `TeamNotes.kt`. `NoteKind` was already taken by the coordinator's own
  message notes.
- **No `NotesStore` class.** The notes are three small functions on
  `ConversationStore` (`notes`, `addNote`, and a line in `eraseContext`),
  which already owns the conversation's folder and its lock.
- **`SkipList` is built now, in `ProjectBrief.kt`**, because the brief must
  not name a file of secrets. `PathGuard` will use it in slice 2b.
  `projectBrief` takes the folders alone.
- **`Approved` has no time field.** Nothing reads one until the settings
  list exists.
- **A specialist is not shown its own task as a note.** A test found it:
  the task is recorded before the turn, so the turn read it back.
- **Notes reach every specialist turn in one place**, `specialist()`, not in
  the three turn builders. The builders are unchanged.
- **The visible `notes.md` is removed when the notes are erased**, at the
  conversation's next message. It is Hovel's copy, not the person's file.

## Slices

**Slice 2a, gamero only.** The project brief; the notes log, its place in
every turn and `notes.md`; approvals remembered across restarts for start
of work; a log line that times the coordinator's first word. No contract
change.

**Slice 2b, needs server-platform first.** Reading: the path check, the
`read` decision, the allowance and the progress line; the `read-files`
approval; the list of approvals and the withdrawal in the project's
settings; the line in the settings that says what the brief sends.

**Slice 2c, needs the provider checks first.** Scratch: the live check on
Claude Code and on Codex, then the folder, the launch change for each
provider that passed, the task's sentence about it, and the two cleanup
rules.

## Deferred / out of scope

- **Notes written by the coordinator.** Its tasks and reports already reach
  the log. A `note` field on its header is an easy later addition.
- **Search beyond plain text.** `search` is a bounded scan for a phrase. Code
  search belongs to a working specialist.
- **Scratch for a provider that fails its check.** It gets none until the
  mechanism is fixed and proven.

## Decided

All on 2026-11-11.

- **The scratch area is built now, not deferred**, and only after a live
  check proves each provider. It is its own slice.
- **Hovel writes the log; agents add to it with a `## Notes for the team`
  section that stays in their reply.**
- **The brief is built for each message and never stored.**
- **Reading is asked through a new approval kind, `read-files`**, which the
  phone can answer.
- **No special spoken line in Talk.** The thinking phrase covers the silence
  and Talk's status shows what is being read.
- **Step 2 ships as three slices**: 2a in the Mac app, 2b after a server
  change, 2c after the provider checks.

The first of these went against the draft's recommendation, which was to
defer scratch. The finding stands: scratch is the one part that changes how
a provider is started.
