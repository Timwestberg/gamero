# Companion folders

Each companion has a folder the person can open in Finder, and its agents
share context on the person's Mac. Built so far:

- **Slice 1a:** a conversation's outputs as readable files, with an index of
  who made each one.
- **Slice 2a:** a brief of the project in every coordinator turn, a shared
  notes log every agent reads and adds to, and approvals that survive a
  restart.

Why and what next: `companion-folders.SPEC.md`, `companion-folders.ARCH.md`
(step 1) and `companion-folders-step2.ARCH.md` (step 2).

```
~/Hovel/
  Ember/
    .hovel.json                          who this folder belongs to
    conversations/
      2026-11-10 Add sign-in/
        .hovel.json
        index.md                         each output, who made it, with what
        notes.md                         the shared notes, as a document
        outputs/
          01 Shopping list spec.md
          02 Architect.md                an output with no heading
```

## Use it

Nothing to call. The conversation engine keeps the folder up to date:

```kotlin
// CoordinatorEngine, at a claim and after each output is stored
companionFolders.sync(FolderNames(accountId, companionId, companion, conversationId, title), outputs) { ref ->
    store.outputBytes(conversationId, ref.id)
}
```

- `sync` is safe to repeat. Pass every output the conversation holds; it
  writes the ones it has not written.
- It never throws. A failure is logged and tried again at the next message.
- `CompanionFolders.NONE` does nothing. It is the default, so a test writes
  no folders unless it asks for them.

## Where the folders are

- `~/Hovel` for every build.
- `HOVEL_COMPANION_FOLDERS=/some/path` moves the root. Set it for any rig
  that signs in a test account, so that account never writes into the
  person's real folders. `logs/run-mac.sh` in the polish rig sets it.

## Rules worth knowing

- **A folder is found by its `.hovel.json`, never by its name.** A folder the
  person renames in Finder keeps their name from then on. A folder with the
  right name and no marker is somebody else's and is never used.
- **Each output is written once.** What the person edits, moves or deletes
  stays as they left it. Hovel remembers what it wrote in
  `<provider root>/<account>/companion-folders.json`.
- **Names come from claims.** The Mac learns a companion's name and a
  conversation's title only when it claims a message. A rename, and a
  conversation older than the folders, are caught at the next message.
- **An output's file is `<nn> <first heading>.md`**, or `<nn> <specialist>.md`
  when the output has no heading.
- **A folder appears with its first file.** A conversation that produces no
  output leaves nothing on disk.

## Change it

| To change | Go to |
| --- | --- |
| How a name is made safe, the 80-character limit | `CompanionFolderRules.safeName` |
| How an output's file is named | `CompanionFolderRules.outputName`, `firstHeading` |
| What `index.md` says | `CompanionFolderRules.index` |
| Where and when folders are created or renamed | `VisibleFolders.settle` |
| What is mirrored | `CoordinatorEngine.mirrorOutputs` builds the list |

The rules are pure functions with no disk, tested in
`CompanionFolderRulesTest`. `VisibleFoldersTest` covers the disk on a
temporary root. To mirror something new, such as the person's attached
inputs, add it to the list `mirrorOutputs` passes; `sync` needs no change.

Do not write into the visible tree from anywhere but `VisibleFolders`, and
do not read the visible files back: the conversation record is the truth.

## The project brief

`projectBrief(folders)` in `ProjectBrief.kt` describes the project's working
folder: what its build files say, the start of its README, and its folders
with their first files. The coordinator gets it in every main-conversation
turn (`projectBrief` in its request), so it can answer "what is this
project?" without a handoff.

- It is built for each message and never stored. The walk is bounded
  (`BriefLimits`: 60 folders, 12 files named per folder, 10,000 characters),
  so a large project costs no more than a small one.
- It needs no approval. A project that has a folder has a brief, and the
  brief goes to the coordinator's provider on every turn.
- `SkipList` decides what is never read and never named: `.env` files, key
  files, `.git`, and dependency and build folders. Add a name there, not at
  a call site. A linked folder is not followed.
- To teach the brief a new kind of project, add its build file to
  `MANIFESTS`. Only `package.json` is quoted; the others are named.

## The shared notes

A conversation's notes are a log every agent in it reads and adds to.

- **Hovel writes most of it** (`CoordinatorEngine.leaveNote`): each task the
  coordinator gives, how each finished output starts, and each handoff
  report.
- **An agent adds a note** by ending its reply with a section headed
  `## Notes for the team`. `TeamNotes.section` copies it into the log. The
  section stays in the reply.
- **Every turn is given the recent notes as data**: a specialist's turn in
  `specialist()` (through `withTeamNotes`), the coordinator's as `teamNotes`
  in its request. `TeamNoteLimits` holds the sizes.
- They are kept in `<conversation>/notes.json` in the private store, through
  `ConversationStore.notes` and `addNote`. `eraseContext` deletes them, so
  memory cleanup takes them with the rest of the agents' context.
- A note never stops a conversation. One that cannot be kept is logged and
  dropped.

```kotlin
// To record something new in the log, one line where it happens:
leaveNote(role.name, TeamNoteKind.TASK, "what was asked")
```

## Approvals that survive a restart

`ApprovalMemory` keeps each yes in `<provider root>/<account>/approvals.json`.
`StartOfWork` reads and writes it, so a specialist's start of work is asked
once and not again after the app restarts. It is asked again when the
conversation, project, folder, provider or model changes.

- Only a yes is kept. "Not now" is never remembered.
- `StartOfWork()` with no memory keeps the old behaviour, which tests use.
- `ApprovalMemory.withdraw` takes an approval back. Nothing calls it yet;
  the list in the project's settings is slice 2b.
- A file that cannot be read means every approval is asked again. It is
  never overwritten.

## Measuring the coordinator

Each message logs once, at the coordinator's first streamed text:
`coordinator first_word_ms=… spoken=… brief_chars=…`. Compare it before and
after a change that adds to the coordinator's turn.

## Not built yet

Slice 1b, which needs a server change first: moving a folder to the Trash
when its conversation or companion is deleted, "Show in Finder" on the
page, and a notice on the page when a folder is behind. Until then a
deleted conversation leaves its folder in place, and a failed write shows
only in the Mac's log.

Slice 2b, which needs a server change first: the coordinator reading files
on request, its approval, and the list of approvals in the project's
settings with the line that says what the brief sends.

Slice 2c, which needs a live check on each provider first: the scratch
folder for working specialists.

## Known limits

- The ledger is per app address, as the conversations are. The same account
  run from two addresses on one Mac shares the folders through their
  markers; an output both wrote could get a ` 2` name.
- Simple outputs only: every output is Markdown today. Another kind would be
  written as `.bin`.
