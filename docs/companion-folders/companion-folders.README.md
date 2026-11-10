# Companion folders

Each companion has a folder the person can open in Finder. Slice 1a is
built: a conversation's outputs appear there as readable files, with an
index of who made each one. Why and what next: `companion-folders.SPEC.md`
and `companion-folders.ARCH.md`.

```
~/Hovel/
  Ember/
    .hovel.json                          who this folder belongs to
    conversations/
      2026-11-10 Add sign-in/
        .hovel.json
        index.md                         each output, who made it, with what
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

## Not built yet

Slice 1b, which needs a server change first: moving a folder to the Trash
when its conversation or companion is deleted, "Show in Finder" on the
page, and a notice on the page when a folder is behind. Until then a
deleted conversation leaves its folder in place, and a failed write shows
only in the Mac's log.

## Known limits

- The ledger is per app address, as the conversations are. The same account
  run from two addresses on one Mac shares the folders through their
  markers; an output both wrote could get a ` 2` name.
- Simple outputs only: every output is Markdown today. Another kind would be
  written as `.bin`.
