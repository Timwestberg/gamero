# Companion folders: test cases

Manual checks for the feature in `companion-folders.SPEC.md`. Follow them by
hand in the Mac app. The automated tests are in
`composeApp/src/jvmTest/.../conversations/` and are not repeated here.

## Before you start

You need all of these for every case, unless a case says otherwise.

- The Mac app built from this branch, opened as Hovel, and connected. The
  window's top line reads "Connected · Conversations run on this Mac".
- A hovel-service built from this branch. An older server makes cases 5 and
  8 behave as "Known edge cases" says.
- A companion (these cases call it Ember) with one project (Test Project).
- Test Project has a working folder on this Mac. Choose one that holds a
  `README.md`, a `.env` file and a `src` folder.
- Ember's brain has a coordinator and at least one specialist in work mode
  (these cases call it TPM), each with a provider you are signed in to.
- You know where the folders are: `~/Hovel`, or the folder in
  `HOVEL_COMPANION_FOLDERS` on a test rig.

Each case starts in a new conversation in Test Project, unless it says
otherwise.

## Happy paths

### 1. An output becomes a file you can open

- **Scenario:** a specialist's output appears in the companion's folder.
- **Steps:**
  1. Ask: "TPM, write a short spec for a shopping list."
  2. When the dialog asks, choose **Start working**.
  3. Wait for the answer.
  4. In Finder, open `~/Hovel/Ember/conversations/`.
- **Expected:** one folder named `<today> <conversation title>`. In it,
  `outputs/01 <first heading of the spec>.md` holds the spec, and `index.md`
  lists that file with TPM, the provider, the model and the time.

### 2. A rename in Hovel, and a rename in Finder

- **Scenario:** Hovel follows its own renames and keeps yours.
- **Setup:** case 1 is done.
- **Steps:**
  1. In the conversation's "•••" menu, Settings, choose **Rename** and give
     the conversation a new title.
  2. Send any message in the conversation.
  3. Look at the folder's name in Finder.
  4. In Finder, rename the folder to "My spec work".
  5. Rename the conversation in Hovel again, and send another message.
- **Expected:** after step 2 the folder has the new title. After step 5 the
  folder is still "My spec work".

### 3. A file you delete stays deleted

- **Setup:** case 1 is done.
- **Steps:**
  1. In Finder, delete `outputs/01 ….md`.
  2. Send a message in the conversation that makes a second output.
- **Expected:** `02 ….md` appears. `01 ….md` does not come back.

### 4. Show in Finder

- **Setup:** case 1 is done.
- **Steps:**
  1. Open the conversation's "•••" menu and choose the Settings tab.
  2. Choose **Show in Finder**.
- **Expected:** Finder opens the conversation's folder. A conversation with
  no output and no working turn has no **Show in Finder** button.

### 5. A deleted conversation's folder goes to the Trash

- **Setup:** case 1 is done.
- **Steps:**
  1. In the conversation's "•••" menu, Settings, choose **Delete
     conversation** and confirm.
  2. Quit the Mac app and open it again, so it connects again.
  3. Look in `~/Hovel/Ember/conversations/` and in the Trash.
- **Expected:** the conversation's folder is in the Trash, and you can put
  it back from there. Other conversations' folders did not move.

### 6. Ember knows what the project is

- **Scenario:** the coordinator answers from the project brief.
- **Steps:**
  1. Ask: "What is this project, in two sentences?"
  2. Open Test Project's page and look under its working folder.
- **Expected:** Ember answers from the README and the folder names, with no
  dialog and no task for a specialist. The project page says, under the
  working folder: "For each message, Hovel sends the coordinator a short
  description of this folder…".

### 7. Ember reads a file you ask about

- **Scenario:** the coordinator asks Hovel to read, and you approve once.
- **Steps:**
  1. Ask: "What does the last section of my README say?"
  2. A dialog reads "Ember wants to read files in <folder> with …". Choose
     **Read files**.
  3. Wait for the answer.
  4. Ask about another file: "What does src hold?"
- **Expected:** after step 2 the conversation shows a line such as "Read
  README.md." and then an answer that matches the file. Step 4 shows no
  dialog and reads again.

### 8. The phone can answer the read request

- **Setup:** a phone paired with this Mac, with the Hovel app from this
  branch. A conversation that has not been approved for reading.
- **Steps:**
  1. From the phone, ask: "What does my README say?"
  2. On the phone, the request "Read project files" appears. Answer
     **Continue**.
- **Expected:** the Mac's dialog closes by itself, and the answer arrives.

### 9. Secrets and other folders are never read

- **Setup:** case 7 is done in this conversation.
- **Steps:**
  1. Ask: "Read my .env file and tell me what is in it."
  2. Ask: "Read /etc/hosts."
- **Expected:** Ember says it did not read them. No value from `.env`
  appears in the answer. No "Read …" line names either file.

### 10. A "Not now" is respected, and not remembered

- **Steps:**
  1. In a new conversation, ask: "What does my README's last section say?"
  2. In the dialog, choose **Not now**.
  3. Send the same question again.
- **Expected:** after step 2 Ember answers without the file and says it did
  not read it; there is no second dialog in that message. Step 3 shows the
  dialog again.

### 11. Approvals are kept, listed and withdrawn

- **Setup:** cases 1 and 7 are done.
- **Steps:**
  1. Quit the Mac app and open it again.
  2. In the same conversations, ask TPM for more work, and ask Ember to
     read a file.
  3. Open Test Project's page and find **Approvals on this Mac**.
  4. Choose **Withdraw** on the row that says "Reads project files".
  5. Ask Ember to read a file again.
- **Expected:** step 2 shows no dialog. Step 3 lists one row per approval,
  with the conversation, what it allows and the provider. After step 4 the
  row is gone, and step 5 shows the dialog again.

### 12. Specialists leave notes for each other

- **Setup:** a brain with two specialists.
- **Steps:**
  1. Ask for work that uses both, one after the other.
  2. Open `notes.md` in the conversation's folder.
- **Expected:** `notes.md` lists each task, each output and each handoff in
  order. When a specialist ends its reply with a `## Notes for the team`
  section, that text is in `notes.md` too.

### 13. The scratch folder

- **Setup:** TPM's provider is Claude Code or Codex.
- **Steps:**
  1. Ask: "TPM, write a draft in the scratch folder first, then give me the
     final spec."
  2. While TPM works, open `scratch/` in the conversation's folder.
  3. Wait for Ember's final answer, and look in `scratch/` again.
- **Expected:** during the work, `scratch/` holds TPM's draft. After the
  final answer, `scratch/` is empty and still there. `index.md` never lists
  a scratch file.

### 14. The space notice, the limit and Clean up

- **Scenario:** a companion's folder passes its limit.
- **Setup:** case 1 is done. Put a file larger than 1 GB in the
  conversation's `scratch/` folder. In Terminal, from that folder:
  `mkfile -n 1300m big.bin` makes one that takes no real disk space.
- **Steps:**
  1. Open the Companions tab.
  2. Under Ember, read the notice and choose **Clean up**.
  3. In **Folder on this Mac**, set **Space limit** to 2 GB.
  4. Set it back to 1 GB.
  5. Leave "Empty the agents' scratch folders" ticked and choose **Clean
     up**.
  6. Tick the conversation in the list and choose **Move to Trash**.
- **Expected:**
  - Step 1: "Ember's folder on this Mac takes 1.3 GB, over its 1 GB limit."
  - Step 3: the over-limit sentence goes. Step 4: it returns.
  - Step 5: the scratch line and the sentence go; `big.bin` is deleted.
  - Step 6: the conversation's folder is in the Trash. The conversation is
    still in Hovel and opens as before.

### 15. Memory cleanup erases notes and keeps outputs

- **Setup:** case 12 is done.
- **Steps:**
  1. Have Hovel forget what it knows (memory cleanup).
  2. Send a message in the conversation.
- **Expected:** `notes.md` is gone from the conversation's folder. Every
  file in `outputs/` is still there.

## Known edge cases

Each is how the feature behaves today. None is a bug to file.

**Servers and devices**

- **A server older than this branch.** The Mac asks about deleted folders,
  gets "not found" and changes nothing, so case 5 leaves the folder in
  place. The read request shows on the Mac only, so case 8 does not reach
  the phone.
- **The Trash step trusts the account.** An account pointed at an empty
  database would send every folder to the Trash. They can be put back.
- **The space limit is kept on the Mac that holds the folder.** Another Mac
  has its own folder and its own limit.

**Folders**

- **A folder can appear before its first output.** A working turn makes it
  for scratch.
- **A folder you cleaned up or deleted comes back only with a new output.**
  It then holds only the new one. `index.md` still lists the earlier
  outputs, whose files are gone.
- **A folder with the right name and no `.hovel.json` is not Hovel's.**
  Hovel makes its own beside it, named with " 2".
- **After the app restarts, the page shows no "not in the folder yet" line**
  until the conversation's next message.
- **The same account run from two app addresses on one Mac** shares the
  folders. An output both wrote can get a " 2" name.

**Reading**

- **One message can read twice, and 32,000 characters in all.** After that
  Ember answers from what it has, or gives the work to a specialist.
- **A search stops after 1,500 files, 3 seconds or 40 lines**, and says so.
  A file over 200,000 bytes is not searched, and only its start is read.
- **The project brief is sent with no approval.** That was decided. It
  never names an environment file, a key file or a build folder.
- **Reading is offered in the main conversation only.** A reply in a task's
  thread is not offered it.

**Scratch and space**

- **With Codex set to full access, scratch limits nothing.** A Codex
  specialist can then write anywhere, as its own settings allow. Hovel does
  not tighten them.
- **Scratch is emptied only when the run completes.** A run that pauses,
  fails or is cancelled keeps its scratch. What is left is deleted after 7
  days, when the Mac next connects.
- **A link where `scratch/` should be is refused.** The specialist then
  works without a scratch folder.
- **A very large folder is counted as "at least".** Counting stops at
  200,000 entries or 2 seconds.
- **The Companions page counts folders when it shows the companions.** A
  folder that passes its limit while the page is open shows its notice the
  next time the page is opened.
- **Passing the limit blocks nothing.** Outputs are still written.

**Not handled**

- Read-only project folders are not given to working specialists yet. Their
  live check has not passed. Ember can still be read their files.
- The person's own attached files are not copied to the folder.

## What has been checked live, and what has not

Checked in the real Mac app on a test account, with live turns on Claude
Code (2026-11-11):

- Case 1: the output was written as `outputs/01 Shopping list spec.md`, with
  `index.md` beside it.
- Case 4: Show in Finder. Case 5: the Trash step.
- Case 6: the line under the working folder on the project page.
- Case 7: the dialog "Ember wants to read files in …", then "Read
  src/list.ts." and a correct answer. Case 9: `.env` refused by Hovel,
  `/etc/hosts` not asked for, and the secret in no message. Case 10: after
  **Not now**, an answer that says it did not read the file, and no kept
  approval.
- Case 11: the approvals section, in its empty state. The two kept
  approvals were then seen in the Mac's own record, not on the page.
- Case 12, with one specialist: `notes.md` held the task, the finished work
  and the specialist's own note.
- Case 13: the draft was in `scratch/` during the work, and the folder was
  empty and still there after the run completed.
- Case 14: the space notice, the limit and Clean up.

Checked by a live provider turn in throwaway folders: both providers keep to
the scratch folder (`ScratchProbeTest`).

Not checked:

- Case 8, the phone answering a read request. The request was seen on the
  server as one any device may answer, with no path in it, and the web page
  showed "Read project files". The simulator phone could not be registered
  and paired by automation, so nobody answered from a phone.
- Cases 2, 3 and 15 by hand; their rules are covered by automated tests.
- Case 12 with two specialists.

Found while checking, and their state:

- The coordinator was not told that working specialists have a scratch
  folder, so it took a draft there for a possible mistake and stopped to
  ask. Fixed: the coordinator is now told.
- The Mac's permission dialog hid its buttons when the command was long,
  and a path in the scratch folder makes commands long. With a long "for
  this conversation" label, Deny was pushed off its row at any window
  size. Fixed: the buttons are outside the scrolling text and wrap onto
  their own lines. Covered by a layout test; not yet seen in the real app.
- A specialist's output file starts with what it said before its first
  heading. That is how an output is stored today.
