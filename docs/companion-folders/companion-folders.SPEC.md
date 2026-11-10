# Companion folders

Status: draft for review, 2026-11-10. Nothing here is built. "Decisions needed"
lists what was filled in by inference.

## What

Each companion gets one folder on the person's Mac that they can open in
Finder. It holds, per conversation:

- the outputs the companion's agents produced, as files with readable names;
- one shared notes file that every agent in the conversation can read and add
  to, so agents share context without each handoff going through the
  coordinator;
- a scratch area for temporary handoff files, cleaned up by rule.

The coordinator also gets read-only access to the project's folder, so the
companion can answer a question about the project's files itself.

It ships in two steps. Step 1 is the visible folder of outputs. Step 2 is the
shared notes, the scratch area and the coordinator's read access, which share
one approval rule because all three are about what an agent may read.

```
~/Hovel/
  Ember/
    conversations/
      2026-11-10 Shopping list spec/
        outputs/
          Shopping list spec.md
          Architecture.md
        index.md          who made each output, with which provider and model
        notes.md          the shared log (step 2)
        scratch/          temporary handoff files (step 2)
```

### Not in scope

- A subfolder per provider. A file is found by its conversation, not by who
  wrote it, and a specialist can move from one provider to another. The
  provider and model are recorded in `index.md`.
- Reading the visible files back. Hovel writes them and never reads them
  again; its own private record stays the truth (see Behavior).
- Sending files to the phone or the server. The phone keeps showing the short
  preview of each output that it shows today.
- Write access for the coordinator. It reads; a working specialist changes
  files.
- Agents that talk to each other with no coordinator turn at all. The notes
  file shares context; the coordinator still decides who runs.
- iPhone and Android. This is the Mac app.

## Why

Three problems, all seen on 2026-11-10.

1. **The person cannot see what their companion made.** Outputs already stay
   on the Mac, under `~/Library/Application Support/Gamero/providers/<hash>/
   <account>/conversations/<id>/artifacts/<id>`. That path is hidden, keyed by
   ids and by the app's address, and holds files with no name or extension.
2. **Agents share context only through the coordinator.** A specialist sees
   the task it was handed and its inputs. What another specialist learned
   reaches it only if the coordinator repeats it.
3. **The companion cannot look at the project it is attached to.** A
   coordinator turn has no file access. It can now say which folder the
   project has and hand file work to a specialist, but "what is in my
   README?" still costs a handoff.

The person benefits: their context is on their own computer, in a form they
can open, search, back up and delete.

## Behavior

### The folders

- The root is `~/Hovel`. A companion's folder is named after the companion.
  A conversation's folder is named `<date started> <title>`.
- Hovel knows a folder by a small hidden marker file inside it, not by its
  name. Renaming the companion or the conversation in Hovel renames the
  folder. Two with the same name get ` 2`, ` 3`.
- A name is made safe for Finder: `/` and `:` become `-`, and it is cut to
  80 characters.
- One account has one folder per companion, whichever address the app runs
  at. A development build uses its own root, so it never writes into the
  real one.
- A folder is created when its first file is written, not when the companion
  is created. An unused companion leaves nothing on disk.

### Outputs (step 1)

- When an agent produces an output, Hovel writes it to `outputs/` under the
  output's title, with an extension from its type (`.md` for Markdown and
  plain text). A second output with the same title gets ` 2`.
- `index.md` lists each output in order: its file, the specialist that made
  it, the provider and model, and the time. The output files hold only what
  the agent wrote, so they stay clean documents.
- Hovel writes each file once and never changes it. The person may edit,
  move or delete a file; Hovel does not restore it, and does not read the
  edit. An agent that is handed an output gets Hovel's recorded copy.
- Outputs of conversations that already exist are written out the first time
  the new version runs.
- If the folder cannot be written (a full disk, a missing drive), the
  conversation goes on. The private record is complete, the page says once
  that the visible folder is behind, and the missing files are written when
  the folder is back.
- The conversation page has "Show in Finder" for the conversation's folder,
  on the project's own Mac.

### Shared notes (step 2)

- Each conversation has one log of notes. A note is a time, the role that
  wrote it, and up to 2,000 characters of text.
- Every agent turn in the conversation is given the most recent notes, up to
  8,000 characters, marked as data from other agents and never as
  instructions. This is the same rule a handoff follows today.
- An agent adds a note through Hovel, as part of its reply. No agent writes
  the file itself, so two agents working at once cannot damage it.
- `notes.md` is Hovel's readable copy of the log. The person can read it. An
  edit to it is not read back and is replaced at the next note.
- The coordinator's instructions say what notes are for: facts the next
  agent needs, decisions made, and open questions. Not a copy of the output.

### Scratch (step 2)

- `scratch/` is a place for a working specialist to leave files for the next
  one: a draft, a data file, a test log. A working specialist can read and
  write there. It keeps such files out of the project's own folder.
- Scratch is emptied when the run that used it completes, and whatever is
  left is deleted after 7 days. It is never listed in `index.md`.

### Read access for the coordinator (step 2)

- With the person's approval, a coordinator turn can read the project's
  working folder, its read-only folders, and the conversation's own folder.
  It cannot write, run commands, or reach the network.
- The first time in a conversation, the Mac asks: "Ember wants to read
  `my-shopping-list` with Codex." It is the dialog that a specialist's start
  of work uses today, and it is remembered for the same thing: this
  conversation, project, folder and provider.
- Without approval, or on a provider that cannot be held to read-only, the
  coordinator behaves as it does today: it names the folder and delegates.
- A reading turn has a budget, so a spoken reply does not stall: when it
  cannot answer within the budget, it hands the question to a specialist.

### Cleanup

| What | Kept until |
| --- | --- |
| Scratch | The run completes, or 7 days |
| Outputs, `index.md`, `notes.md` | The conversation is deleted |
| A conversation's folder | The conversation is deleted: it moves to the Trash |
| A companion's folder | The companion is deleted: it moves to the Trash |

- A folder goes to the Trash, not straight to nothing, so a mistaken delete
  can be undone. A folder the person moved or renamed by hand is left alone
  and reported.
- When the person has Hovel forget what it knows (memory cleanup), the notes
  are erased with the rest of the agents' context. Outputs are the person's
  documents and stay.
- When a companion's folder passes 1 GB, the Companions page says so. Hovel
  deletes nothing by size alone.

### Constraints

- Nothing in a companion folder leaves the Mac. What reaches the server is
  what reaches it today: a preview of each output, up to 512 characters.
- Neither provider's read-only mode is proven yet. Both work runtimes mark
  read-only folders as unverified (`ContextFolderSupport.UNVERIFIED`), so
  the read access ships only for a provider that passes a live check.
- A Claude coordinator turn is one model turn with every tool off. Reading
  files needs read tools and more than one turn, which changes its cost and
  its speed.
- The handoff rules that depend on an output's exact content (stage consent
  binds to input digests) keep using the private record, which is why the
  visible files are write-once copies.

## Decisions needed

These were filled in by inference. Each changes the design if the answer is
different.

1. **The root is `~/Hovel`, visible in the home folder.** Or `~/Documents/
   Hovel`, or a folder the person picks.
2. **The visible files are one-way copies.** Hovel never reads an edit back.
   The other choice, edits that agents see, breaks the digest rule above and
   needs its own design.
3. **Deleted folders go to the Trash.** Not deleted outright.
4. **Memory cleanup erases notes and keeps outputs.**
5. **The read approval is asked again after each restart**, as start of work
   is today. Remembering it across restarts is friendlier and is a separate,
   small change that would cover both.
6. **The numbers:** notes of 2,000 characters, 8,000 characters given to a
   turn, scratch kept 7 days, a warning at 1 GB.
7. **No provider subfolders.** You proposed them; this draft records the
   provider in `index.md` instead.

<!-- IMPLEMENTATION-PLAN:START -->

## When (remove after delivery)

Step 1, the visible folder:

- **Folder identity and names.** The root, the marker file, safe names,
  renames and duplicates. Depends on nothing. Done when renaming a companion
  and a conversation renames their folders and a hand-moved folder is
  reported, not recreated.
- **Write outputs and the index.** Each new output is written once with its
  name and extension, and `index.md` gains its line. Depends on folder
  identity. Done when a delegated task's output is a readable file within a
  second of appearing in the conversation, and a failed write does not stop
  the run.
- **Existing conversations.** Outputs already recorded are written out once.
  Depends on writing outputs. Done when it can run twice without duplicates.
- **Delete to the Trash.** A deleted conversation or companion moves its
  folder to the Trash. Depends on folder identity. Done when the folder is
  in the Trash and can be put back.
- **Show in Finder.** A host-bridge method and the page's control. Depends on
  folder identity, and on a contract change in server-platform first. Done
  when it opens the right folder on the project's Mac and is absent
  elsewhere.

Step 2, shared context:

- **Prove read-only.** A live check per provider that a coordinator turn can
  read the folder and cannot write, run a command or reach outside what it
  was given. Depends on nothing; it gates the read access. Done when each
  provider is marked verified or not.
- **The notes log.** The record, its limits, `notes.md`, and the recent notes
  in every turn's input as data. Depends on step 1. Done when a second
  specialist's turn shows the first one's note.
- **Agents add notes.** The reply format for a note, on talk and work turns.
  Depends on the notes log. Done when two specialists running at once both
  land their notes intact.
- **Scratch.** The folder, write access for working specialists, and the two
  cleanup rules. Depends on step 1.
- **Read access and its approval.** The dialog, what is remembered, the
  reading budget, and the fall back to delegating. Depends on the proof and
  on the approval rule being agreed.
- **Cleanup on memory erase, and the size notice.** Depends on the notes log.

<!-- IMPLEMENTATION-PLAN:END -->
