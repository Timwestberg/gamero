# Companion folders

Status: built, on the branch `claude/feat/conversations-lists-polish`, not
merged. "Decided" lists what was agreed. How to check it by hand is in
`companion-folders.TEST.md`.

## What

Each companion gets one folder on the person's Mac that they can open in
Finder. It holds, per conversation:

- the outputs the companion's agents produced, as files with readable names;
- one shared notes file that every agent in the conversation can read and add
  to, so agents share context without each handoff going through the
  coordinator;
- a scratch area for temporary handoff files, cleaned up by rule.

The coordinator also gets a view of the project it is attached to: a short
brief of the project in every turn, and a way to ask Hovel to read files for
it. It never gets file tools of its own.

It ships in two steps. Step 1 is the visible folder of outputs. Step 2 is the
shared notes, the scratch area and the coordinator's view of the project,
because all three are about what an agent may read.

```
~/Hovel/
  Ember/
    conversations/
      2026-11-10 Shopping list spec/
        outputs/
          01 Shopping list spec.md
          02 Architecture.md
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
- A provider's own file tools for the coordinator, and any write access for
  it. Hovel reads for it; a working specialist changes files.
- A code graph or another third-party index of the project. One was tested
  and not recommended: see `project-index-spike.md`.
- Proving that a working specialist's read-only folders stay read-only. That
  is still unproven for both providers (`ContextFolderSupport.UNVERIFIED`,
  `ContextFolderProbeTest`) and is separate work; nothing here depends on it.
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
  A conversation's folder is named `<date> <title>`, where the date is the
  day its first output was made.
- Hovel knows a folder by a small hidden marker file inside it, not by its
  name. Renaming the companion or the conversation in Hovel renames the
  folder. Two with the same name get ` 2`, ` 3`. The rename happens at the
  conversation's next message, which is when the Mac learns the new name.
- A name is made safe for Finder: `/` and `:` become `-`, and it is cut to
  80 characters.
- One account has one folder per companion, whichever address the app runs
  at. Every build writes to `~/Hovel`; a setting moves the root for a test
  rig, so a test account never writes into the real one.
- A folder is created when its first file is written, not when the companion
  is created. An unused companion leaves nothing on disk.

### Outputs (step 1)

- When an agent produces an output, Hovel writes it to `outputs/` as
  `<nn> <name>.md`: a two-digit number in the order written, then the
  output's first heading, or the specialist's name when it has no heading.
- `index.md` lists each output in order: its file, the specialist that made
  it, the provider and model, and the time. The output files hold only what
  the agent wrote, so they stay clean documents.
- Hovel writes each file once and never changes it. The person may edit,
  move or delete a file; Hovel does not restore it, and does not read the
  edit. An agent that is handed an output gets Hovel's recorded copy.
- A conversation that already exists gets its folder at its next message.
  One that is never used again stays out of the visible folder.
- If the folder cannot be written (a full disk, a missing drive), the
  conversation goes on. The private record is complete, the page says once
  that the visible folder is behind, and the missing files are written when
  the folder is back.
- The conversation page has "Show in Finder" for the conversation's folder,
  on the project's own Mac. This and the Trash need a server change, so they
  come in a second slice of step 1.

### Shared notes (step 2)

- Each conversation has one log of notes. A note is a time, the role that
  wrote it, and up to 2,000 characters of text.
- Every agent turn in the conversation is given the most recent notes, up to
  8,000 characters, marked as data from other agents and never as
  instructions. This is the same rule a handoff follows today.
- Hovel writes most of the log itself: each task the coordinator delegates,
  each output a specialist finishes, and each handoff report.
- An agent adds a note of its own by ending its reply with a section headed
  `## Notes for the team`. The section stays in the reply, where the person
  reads it too. No agent writes the file itself, so two agents working at
  once cannot damage it.
- `notes.md` is Hovel's readable copy of the log. The person can read it. An
  edit to it is not read back and is replaced at the next note.
- The coordinator's instructions say what notes are for: facts the next
  agent needs, decisions made, and open questions. Not a copy of the output.

### Scratch (step 2)

- `scratch/` is a place for a working specialist to leave files for the next
  one: a draft, a data file, a test log. A working specialist can read and
  write there. It keeps such files out of the project's own folder.
- A provider is given scratch only after a live check proves it can write
  there and nowhere else beyond the project's folder. One that fails the
  check gets no scratch.
- Scratch is emptied when the run that used it completes, and whatever is
  left is deleted after 7 days. It is never listed in `index.md`.
- Scratch is added to what the person's own provider settings allow. Hovel
  does not tighten those settings to make scratch the only other place a
  specialist can write.

### The coordinator's view of the project (step 2)

The coordinator has no tools, on any provider. Hovel reads for it. Two
things reach it, both as data and never as instructions.

**The project brief.** A short description of the project that Hovel builds
itself and puts in every main-conversation turn. It needs no approval: a
project that has a folder has a brief. Choosing the folder is the person's
consent to it.

- It holds the manifest (name, scripts, what it depends on), the start of
  the README, and a listing of folders with their first files.
- It is at most 10,000 characters. On the Hovel web app's source (1,014
  files) it measured 9,738 characters and took 3 ms to build.
- It is built fresh for each message. Building costs milliseconds, so there
  is nothing to store and no schedule.
- It follows the skip list below: a skipped file is not named in the
  listing, and its text is never in the brief.
- It is not kept anywhere. Nothing is written into the project's folder.

**Reading files.** When the brief is not enough, the coordinator asks Hovel
in its reply, the way it asks to delegate. It can list a folder, search for
text, and read files, several in one request.

- Reading asks first. The first time in a conversation, the Mac asks: "Ember
  wants to read files in `my-shopping-list` with Codex." It is the dialog a
  specialist's start of work uses today. If the person says no, the
  coordinator answers from the brief or hands the question to a specialist.
- An approval is remembered across restarts, for exactly what the dialog
  named: this conversation, project, folder and provider. It is asked again
  when any of them changes. This holds for a specialist's start of work too,
  which today is asked again after every restart.
- The project's settings list what is approved, and the person can withdraw
  an approval there. "Not now" is never remembered.

- Hovel checks every path. A path is read only when, after links are
  resolved, it is inside the project's working folder, one of its read-only
  folders, or the conversation's own folder. Anything else is refused and
  the coordinator is told so.
- Secrets and noise are skipped whatever is asked. The skip list is fixed
  and the same in every project: `.env` files, key files, `.git`, dependency
  and build folders, and files that are not text. It does not follow the
  project's `.gitignore`. A long file is given in part, with a note that it
  was cut.
- What was read comes back as data on a follow-up turn of the same message.
- A message has a budget: two rounds of reading and 32,000 characters in
  all. After that the coordinator answers with what it has, or hands the
  question to a specialist.
- The conversation shows what was read, as a line of activity: "Ember read
  3 files", with their names. The names are stored with the conversation.
- In Talk, the status line shows what is being read. The thinking phrase the
  person already hears covers the silence, so no line is spoken for it.
- A turn that asks for no file costs nothing more than it does today.

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
- Each companion's folder has a space limit on the Mac that holds it: 1 GB
  unless the person sets another, in that companion's settings. When the
  folder passes it, the Companions page says so. Nothing is blocked, and
  Hovel deletes nothing by size alone.
- The person can clean up a companion's folder. Clean up shows each
  conversation's folder with its size and when it last changed, the one
  changed longest ago first. It empties the agents' scratch folders, and
  moves the folders the person ticks to the Trash. The conversations stay
  in Hovel and can still be opened. A folder that went comes back only when
  its conversation has something new to put in it. The person's own files
  in the companion's folder are counted and never touched.

### Constraints

- Nothing in a companion folder leaves the Mac. What reaches the server is
  what reaches it today: a preview of each output, up to 512 characters.
- **What the coordinator reads goes to its provider, and what it quotes goes
  to the server.** The brief and any file text are sent to the model on the
  person's own subscription. Anything the coordinator repeats in its reply is
  stored as a conversation message, as every reply is.
- **The brief is sent without a prompt.** From the moment a project has a
  folder, its file names, its manifest and the start of its README go to the
  coordinator's provider on every turn. The project's settings say so where
  the folder is chosen. File text beyond the brief still asks first.
- **Read-only and scope are Hovel's own code.** No provider setting is
  trusted, so they are proven by ordinary tests of the path check: links,
  `..`, names that differ only by case, and paths outside the folders.
- **A file can try to instruct the coordinator.** It has no tools, so the
  most it can do is answer wrongly or propose a handoff, and a handoff to a
  working specialist is already gated by the person's approval.
- **A read costs one more model round.** The time from the person's message
  to the first word of the reply is measured before and after, in text and
  in Talk.
- The handoff rules that depend on an output's exact content (stage consent
  binds to input digests) keep using the private record, which is why the
  visible files are write-once copies.

## Decided

All on 2026-11-10, but the three on space, which are of 2026-11-11.

Space:

- **Clean up moves only what the person picks, and to the Trash.** Not a
  zip of older folders, and not an automatic removal by age.
- **The limit is per companion**, 1 GB by default, raised in that
  companion's settings.
- **Over the limit there is a notice and nothing else.** Work goes on and
  outputs are still written.

The folders:

- **The root is `~/Hovel`**, visible in the home folder.
- **No subfolder per provider.** The provider and model of each output are
  in `index.md`.
- **The visible files are one-way copies.** Hovel never reads an edit back.
- **A deleted conversation or companion moves its folder to the Trash.**
- **Memory cleanup erases the notes and keeps the outputs.**

The coordinator's view of the project:

- **Hovel reads for the coordinator; it gets no provider file tools.**
- **The project index is a brief Hovel builds itself, not a third-party
  graph.** After the spike in `project-index-spike.md`.
- **The brief is built fresh for each message and never stored.**
- **The brief needs no approval; reading files does.**
- **The skip list is fixed and does not follow `.gitignore`.**
- **The conversation shows what the coordinator read, by file name.** In
  Talk that is the status line; nothing is spoken for it.
- **Reading is asked through its own approval, which the phone can answer.**
- **Hovel writes the notes log; agents add a visible section to it.**
- **Scratch is built, after a live check on each provider.**

Approvals and limits:

- **An approval is remembered across restarts**, for reading and for a
  specialist's start of work.
- **File names are a number and the output's first heading.**
- **Existing conversations get their folder at their next message, and its
  date is the day of its first output.**
- **Every build writes to `~/Hovel`; a setting moves it for test rigs.**
- **The limits are starting values**, to be tuned from use: notes of 2,000
  characters, 8,000 characters of notes given to a turn, scratch kept 7
  days, a warning at 1 GB, a brief of 10,000 characters, and a reading
  budget of two rounds and 32,000 characters.
