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
- **A folder appears with its first file, or its first working turn.** A
  conversation that produces no output and runs no working specialist
  leaves nothing on disk.

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

## Reading for the coordinator

The coordinator has no file tools. It asks Hovel to read:

```json
{"kind":"read","files":["README.md"],"list":["src"],"search":"sign in"}
```

- The engine offers `read` in the main conversation of a project with a
  working folder. It answers the decision itself and asks the coordinator
  again; a run never sees one.
- The person is asked once per conversation (`read-files`, which the phone
  can answer). The yes is kept like a start of work.
- Every path goes through `PathGuard`. It allows only the working folder and
  the read-only folders, by their real path, and nothing `SkipList` names.
  What it refuses is named to the coordinator with its reason.
- `ReadLimits` is one message's allowance: 2 rounds, 32,000 characters.
- The conversation says what was read: "Read README.md and 2 more."

To change what may be read, change `PathGuard` or `SkipList`, and nothing
else: no other code opens a project file for the coordinator.

## The scratch folder

`<conversation's folder>/scratch/` is where working specialists leave files
for each other: a draft, a data file, a test log.

- The engine asks for it when a working turn starts
  (`companionFolders.scratch(names)`) and passes it in `WorkScope.scratch`.
  The specialist's task names the path.
- Only a runtime whose `scratch` is `ScratchSupport.VERIFIED` is given it.
  Claude Code gets `--add-dir`; Codex gets one more writable root. Neither
  changes the person's own provider settings.
- It is emptied when the run completes, and at each connect what is older
  than 7 days is deleted (`ScratchLimits.KEPT_DAYS`). It is never in
  `index.md`.
- To prove a provider again, for example after it changes how folders are
  granted, run its probe. Each is one short turn on your own subscription:

```bash
HOVEL_SCRATCH_CLAUDE_PROBE="$HOME/.local/bin/claude" ./gradlew :composeApp:jvmTest --tests '*ScratchProbeTest.claude*' -i
```

```bash
HOVEL_SCRATCH_CODEX_PROBE="/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex" ./gradlew :composeApp:jvmTest --tests '*ScratchProbeTest.codex*' -i
```

## Folders on the page

- `project.outputsFolder` answers `none`, `ready` or `behind` for a
  conversation; `project.showOutputs` opens its folder in Finder.
- When the Mac connects it asks the account which of its folders belong to
  a deleted conversation or companion (`POST /folders/gone`) and moves
  those to the Trash. A server without the route changes nothing.

## Approvals that survive a restart

`ApprovalMemory` keeps each yes in `<provider root>/<account>/approvals.json`.
`StartOfWork` reads and writes it, so a specialist's start of work is asked
once and not again after the app restarts. It is asked again when the
conversation, project, folder, provider or model changes.

- Only a yes is kept. "Not now" is never remembered.
- `StartOfWork()` with no memory keeps the old behaviour, which tests use.
- `StartOfWork.kept(projectId)` lists them and `withdraw(projectId, id)`
  takes one back. The project's settings do both through
  `project.approvals` and `project.withdrawApproval`. An approval's `id` is
  the SHA-256 of its fields, so the page never sees a folder.
- A file that cannot be read means every approval is asked again. It is
  never overwritten.

## Measuring the coordinator

Each message logs once, at the coordinator's first streamed text:
`coordinator first_word_ms=… spoken=… brief_chars=…`. Compare it before and
after a change that adds to the coordinator's turn.

## Space and clean-up

- `companionFolders.usage(accountId, companionId)` counts a companion's
  folder: all of it, the part in scratch, and each conversation's folder.
- `setLimit` keeps the person's limit for that companion on this Mac
  (`StorageLimits`: 1 GB by default, 100 MB to 1 TB).
- `clean` empties scratch and moves the chosen conversations' folders to
  the Trash. The conversations stay in the account.
- The page uses `project.storage`, `project.setStorageLimit` and
  `project.cleanStorage`. Passing the limit only changes what the page says.

## Checking it by hand

`companion-folders.TEST.md` has the manual cases, the known edge cases, and
what has and has not been seen in the real app.

## Known limits

- Slices 1b and 2b need the server of this branch. Against an older server
  a deleted conversation's folder stays, and the reading approval shows on
  the Mac only.
- The Trash trusts the account's answer. See the step 1 architecture for
  what that means when an account is pointed at the wrong database.
- After a restart the page shows no "behind" notice until the
  conversation's next message.
- With Codex set to full access, as on the development Mac, a specialist
  can write anywhere, so scratch limits nothing there. That is the
  person's setting, and Hovel leaves it.
- A conversation's folder can now appear before its first output: a working
  turn makes it for scratch.

- The ledger is per app address, as the conversations are. The same account
  run from two addresses on one Mac shares the folders through their
  markers; an output both wrote could get a ` 2` name.
- Simple outputs only: every output is Markdown today. Another kind would be
  written as `.bin`.
