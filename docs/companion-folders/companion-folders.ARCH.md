# Companion folders: architecture, step 1

Status: slice 1a is built (2026-11-10); slice 1b is not started. Covers
step 1 of
`companion-folders.SPEC.md`, the visible folder of outputs. Step 2 (notes,
scratch, the coordinator's view of the project) gets its own pass once this
one is agreed. No code is written.

## What the code is like today

Read before designing. Each point names where it is.

- **An output is an artifact in a private store.**
  `ConversationStore.artifact()` writes the bytes to
  `<root>/<account>/conversations/<conversation>/artifacts/<id>`, durably,
  owner-only (`writeDurably`, `privateFolder` in `storage/`). The file has no
  name or extension. The record keeps an `ArtifactRef` (id, title, media
  type, preview) on the thread that made it.
- **Two places make an output**, both in `CoordinatorEngine`: a workflow
  step's attempt and a thread's reply. Both store `text/markdown`. A third
  call stores the person's attached inputs.
- **Who made it is already recorded.** `ConversationRecord.outputOrigins` maps
  an output to its thread and provider (`OutputOrigin`), and the thread holds
  the role with its model (`ThreadState.role.binding`).
- **`project.openOutput` already exists** on the host bridge. It opens the
  provider's own session for an output. "Show in Finder" is a sibling of it.
- **The engine takes its side stores as constructor arguments**
  (`ThreadEditStore`, `ProjectFolderStore`), built in `ConversationHost`.
  A folder mirror fits the same seam.
- **Per-account files follow one pattern**: `project-folders.json` beside the
  conversations, replaced atomically, validated on every read.

Four findings change what the spec can promise.

1. **An output's title is the role or the step, not a document title.**
   `store.artifact(conversationId, step?.name ?: role.name, …)`. Files named
   by title alone would be `TPM.md`, `TPM 2.md`, `TPM 3.md`.
2. **The Mac learns names only when it claims a message.** A claim carries the
   companion's name and the conversation's title (`ClaimResult`). The
   conversation record on the Mac stores neither. So a rename reaches the
   folder at the conversation's next message, and a conversation that
   already exists is written out at its next message, not when the new
   version first runs.
3. **The Mac is never told that a conversation or a companion was deleted.**
   The host's event stream (`HostStreamEvent`) has no such event, and the
   Mac's API (`ConversationApi`) cannot list what still exists. Moving a
   folder to the Trash needs one new route on the server.
4. **A conversation has no start date on the Mac** (`Conversation` carries
   `lastActivityAt` only). The folder's date is the day its first output is
   written.

One more, about the rig and not the product: the app Tim uses daily is a
development build. "A development build uses its own root" would hide his
real folders, so the rule becomes an explicit override (below).

## Data and interfaces

All new, in `composeApp/src/jvmMain/…/conversations/` beside
`ProjectFolderStore`. Names are proposals.

```kotlin
/**
 * Who a visible folder belongs to, in `.hovel.json` inside it. Hovel finds
 * and trashes a folder by this file, never by its name.
 */
@Serializable
internal data class FolderMarker(
    val version: Int = 1,
    val accountId: String,
    val companionId: String,
    /** Null on a companion's folder; set on a conversation's. */
    val conversationId: String? = null,
)

/**
 * What Hovel has written to the visible folders, per account, at
 * `<root>/<accountId>/companion-folders.json`. The private record stays the
 * truth about outputs; this is only the mirror's memory.
 */
@Serializable
internal data class FolderLedger(
    val version: Int = 1,
    val companions: Map<String, CompanionFolder> = emptyMap(),        // by companion id
    val conversations: Map<String, ConversationFolder> = emptyMap(),  // by conversation id
)

/**
 * [name] is the folder under the root as it was last seen. [from] is the
 * companion's name that Hovel made it from, so a rename in Hovel can be told
 * from a rename by hand. A folder the person renamed is [pinned]: it keeps
 * their name.
 */
@Serializable
internal data class CompanionFolder(val name: String, val from: String, val pinned: Boolean = false)

@Serializable
internal data class ConversationFolder(
    val companionId: String,
    val name: String,
    /** The conversation's title that [name] was made from. */
    val from: String,
    val pinned: Boolean = false,
    /** The day its first output was made, `yyyy-MM-dd`. Fixed from then on. */
    val startedOn: String,
    /** In the order written. An entry stays when the person deletes its file. */
    val outputs: List<MirroredOutput> = emptyList(),
)

/** One line of `index.md`. */
@Serializable
internal data class MirroredOutput(
    val artifactId: String,
    val file: String,
    val madeBy: String,       // the specialist's name
    val runtimeId: RuntimeId,
    val modelId: String,
    val at: String,           // ISO-8601 instant
)

/** What a claim says the folders are called now. Not stored. */
internal data class FolderNames(
    val accountId: String,
    val companionId: String,
    val companion: String,
    val conversationId: String,
    val conversation: String,
)

/** An output the record holds, with who made it. Not stored. */
internal data class RecordedOutput(
    val artifact: ArtifactRef,
    val madeBy: String,
    val runtimeId: RuntimeId,
    val modelId: String,
    val at: Instant,
)

/** The mirror as the engine sees it. */
internal interface CompanionFolders {
    /**
     * Brings one conversation's visible folder up to date: names it as
     * [names] says, writes every one of [outputs] not yet written, and
     * rewrites `index.md`. Never throws; what failed is tried again at the
     * next call.
     */
    fun sync(names: FolderNames, outputs: List<RecordedOutput>, bytes: (ArtifactRef) -> ByteArray)

    companion object { val NONE = object : CompanionFolders { /* does nothing */ } }
}
```

Constants (`CompanionFolderRules`): the marker's file name `.hovel.json`, the
name limit of 80 characters, the characters replaced in a name, the media
type to extension table (`text/markdown` and `text/plain` to `.md`).

Nothing crosses a trust boundary except the two files Hovel reads back, the
ledger and a marker. Both are decoded strictly and a file that does not
decode is left alone and reported, as `ProjectFolderStore` does.

No wire format changes in the first slice.

## Design

### One operation

The mirror has one entry point, `sync`, and it is idempotent. It covers four
spec behaviours with one piece of code:

| Spec behaviour | How `sync` covers it |
| --- | --- |
| A new output becomes a file | It is in `outputs` and not in the ledger, so it is written |
| A conversation that already exists is written out | The first sync finds every output missing from the ledger |
| A rename follows | `names` differs from the ledger, so the folder is renamed first |
| A failed write is retried | The output is still not in the ledger, so the next sync writes it |

The engine calls it twice per message: when the message is claimed, and
after each output is stored. Both calls pass every output of the
conversation's record.

### Pieces

- **`CompanionFolderRules`** (pure functions, no I/O): a safe folder name, a
  name that is free among its neighbours, an output's file name, and the
  text of `index.md` from a `ConversationFolder`. This is where the tests
  concentrate.
- **`FolderLedgerStore`**: reads and replaces `companion-folders.json`. One
  per account.
- **`VisibleFolders`** (implements `CompanionFolders`): the only code that
  touches the visible tree. It finds a folder by its marker, creates and
  renames folders, writes files, and updates the ledger last.
- **`CoordinatorEngine`**: gains a `CompanionFolders` argument and the two
  calls. It builds `FolderNames` from the claim and `RecordedOutput`s from
  the record's threads and `outputOrigins`.
- **`ConversationHost`**: builds `VisibleFolders` with the root and passes it
  in, as it does `ThreadEditStore`.

### How a sync runs

1. Find the companion's folder: the folder under the root whose marker names
   this account and companion. If none, create it under the companion's name
   and write the marker.
2. Settle its name. If the folder's name is not the ledger's `name`, the
   person renamed it by hand: their name stays, and Hovel stops renaming
   that folder. Otherwise, if the companion's name is not the ledger's
   `from`, it was renamed in Hovel: rename the folder.
3. The same two steps for the conversation's folder, under `conversations/`.
4. For each output not in the ledger: write the file, then add its entry.
   An entry whose file is gone is not rewritten. That is the person's delete.
5. Rewrite `index.md` from the ledger.
6. Replace the ledger file.

A crash between 4 and 6 leaves a file with no ledger entry. The next sync
would write it again under the next free name. To prevent that, step 4
checks for a file with the same bytes before writing.

### Where the root is

`~/Hovel`. `HOVEL_COMPANION_FOLDERS` overrides it; the test rigs and
`run-mac.sh` variants for a test account set it. This replaces the spec's
"a development build uses its own root".

One root holds every account on the Mac. Two accounts that each have an
"Ember" get `Ember` and `Ember 2`; the markers keep them apart.

### File names

An output's file is `<nn> <name>.md`: a two-digit number in the order
written, then the first Markdown heading of the output, or the artifact's
title when it has no heading. `01 Shopping list spec.md`,
`02 Architecture.md`, `03 TPM.md`. The number keeps Finder in the order of
the conversation and makes every name unique without ` 2` suffixes.

### Failure handling

- `sync` catches everything and logs it. A conversation never stops because
  a visible file could not be written. It stops at the first output it
  cannot write, so the numbers stay in the order the outputs were made.
- A ledger that does not decode is not overwritten: the mirror stops for
  that account and says why in the log.
- A marker that names another account or companion is never touched.

### What is reused and what is new

Reused: `writeDurably` and `privateFolder` (files in the visible tree are
owner-only too), `ArtifactRef`, `OutputOrigin`, the strict-decode pattern of
`ProjectFolderStore`, the engine's constructor seam.

New: the three small files above. They are small; most of the logic is the
pure rules, which need no disk to test.

### Alternatives considered

- **Write the visible file inside `ConversationStore.artifact()`.** Rejected:
  the store does not know names, and it would tie the private record's
  durability to a folder the person can delete.
- **Keep what was written in the folder's marker, with no private ledger.**
  Rejected: when the person deletes a folder, Hovel would forget what it had
  written and write everything again.
- **Find a folder by its name.** Rejected: a person who renames a folder
  would get a second one, and the Trash step would act on a name.
- **A background job that mirrors on a timer.** Rejected: the claim already
  gives the names and the moment; a timer adds a second writer.

### What changed while building

- **No `behind` flag.** Nothing reads it until the page shows a notice, which
  is slice 1b. A failed output is retried because it is not in the ledger.
- **A `pinned` flag** on both folder entries, for a folder renamed by hand.
- **The pieces are two files, not three.** `CompanionFolders.kt` holds the
  shapes, the interface and the pure rules; `VisibleFolders.kt` holds the
  ledger and the writer. The ledger is a few private functions and did not
  earn a class.
- **A link in `index.md` escapes `|`, `(`, `)`, `#`, `?` and `%`**, found by a
  test: a `|` in a file name broke the table.
- **A conversation older than the folders is dated by its first output's own
  date**, not the day it was written out.

### What changed while building slice 1b

- **One route, `POST /folders/gone`.** The Mac sends `foldersKept` (the
  conversation and companion ids it keeps a folder for, at most 500 of
  each per call) and the server answers `foldersGone`, those of them the
  account no longer has. Only a delete answers there: an archived
  conversation, and one whose project moved to another computer, is still
  the account's.
- **The Mac takes only what it asked about.** An id in the answer that was
  not in the question changes nothing.
- **A server older than the route changes nothing.** It answers 404, which
  the Mac reads as "nothing is gone" and does not log. The next connect
  asks again. So the staged app is safe against a server that has not been
  released yet.
- **A folder the Trash refuses stays in the ledger**, so the next connect
  tries again. A folder the person already removed is only forgotten.
- **`behind` is kept in memory, not in the ledger.** It is true after a sync
  that failed and false after one that did not. After a restart it is
  unknown until the conversation's next message, and the page then shows no
  notice. A ledger field would only say what the next sync finds out anyway.
- **Two bridge methods, not a field.** `project.outputsFolder` answers
  `none`, `ready` or `behind`; `project.showOutputs` opens the folder. No
  path crosses the bridge.
- **`VisibleFolders` takes `reveal` and `trash` as functions**, so a test
  watches Finder and the Trash without a desktop.

### A limit to know: the Trash trusts the account's answer

The Mac moves a folder to the Trash because the account says the
conversation is not there. An account pointed at the wrong database (a
fresh one, in development) would say that of every conversation. The
folders would then all go to the Trash, where the person can put them
back. The Trash, and not a delete, is what makes this acceptable. A record
of deletes on the server would be the stronger design, and is deferred.

## Slices

The spec's five tasks for step 1 fall into two slices, because of finding 3.

**Slice 1a, gamero only.** Folder identity and names, writing outputs and
`index.md`, existing conversations at their next message. No contract
change. A file write that fails is logged; the page is not told yet.

**Slice 1b, built.** Each part needed a contract change:

- *Delete to the Trash.* A host route the Mac calls when it connects: it
  sends the conversation and companion ids it has folders for, and the
  server answers which no longer exist. A call at connect, not an event,
  because an event is missed while the Mac is off. The Mac then moves each
  such folder to the Trash (`java.awt.Desktop.moveToTrash`), only when its
  marker matches.
- *Show in Finder.* A bridge method beside `project.openOutput`.
- *The "visible folder is behind" notice.* A field on a bridge answer.

## Deferred / out of scope

- **The person's attached inputs are not mirrored.** The spec says outputs.
  An `inputs/` folder is an easy later addition through the same `sync`.
- **Binary outputs.** Both producers write Markdown today. A binary artifact
  would get `.bin`; nothing produces one.
- **Moving the root.** Decided: `~/Hovel`. The override is for rigs.
- **The "waiting to start" thread state** seen on 2026-11-10 is unrelated and
  not touched here.

## Decided

All on 2026-11-10.

- **File names are `<nn> <first heading or title>.md`.**
- **Step 1 ships as two slices**: 1a in the Mac app, 1b after a server
  change.
- **Existing conversations are written out at their next message.**
- **A conversation folder's date is the day its first output is written.**
- **Every build writes to `~/Hovel`; `HOVEL_COMPANION_FOLDERS` overrides it
  for rigs.**
