# Project index spike: engram

2026-11-10. A test of `@sentropic/engram` 0.26.0 as the index behind the
coordinator's view of a project. Recommendation, not yet agreed: do not adopt
it. A brief that Hovel builds itself answers the coordinator's questions
better and costs nothing.

## How it was run

- Installed with `--ignore-scripts` into a scratch folder: 157 packages, 378 MB.
- Run inside a macOS sandbox with no network, no access to the home folder
  beyond Node, writes confined to the scratch folder, and an empty
  environment (no API keys).
- On copies of three projects, never the originals. Code-only build:
  `engram update . --no-description --no-label`.

## Measured

| Project | Files | Build | Graph | Report |
| --- | --- | --- | --- | --- |
| A Next.js starter (my-shopping-list) | 18 | 0.8 s | 17 nodes, 12 edges | 2,247 characters |
| The Hovel web app's `src` (TypeScript) | 1,014 | 7.2 s | 5,428 nodes, 9,763 edges, 6.3 MB | 65,116 characters |
| gamero's `:voice` module (Kotlin) | 44 | failed | none | none |

- **Kotlin failed outright**: "Grammar not found for kotlin" for all 42 files.
  The Kotlin and Swift grammars are optional extras that a default install
  does not have.
- **Natural questions miss.** "How does read aloud work" returned 76 nodes and
  none was `readAloud.ts`. "Where is the voice picker for the device" missed
  `deviceVoices.ts`. On the starter, "what does this project do" returned "No
  matching nodes found": the code-only graph holds no README text.
- **Exact names rank low in `query`.** `createReadAloud` put its own file 19th,
  behind the most connected files.
- **`explain <symbol>` is exact and useful**: the file, the line, and what
  imports or calls it. Simple constants are not indexed.
- **An update is not incremental on this path.** After one file changed, all
  884 files were parsed again (4.5 s).
- **The report is too large to hand to a model each turn**: about 16,000
  tokens for the web app. On the starter the report itself says the project
  fits in one context window and may not need a graph.
- **It behaved.** It wrote only inside `<project>/.engram/`, left nothing in
  the home or temp folders, and made no network call (none was possible).
- **Without the two opt-out flags and with no key**, it leaves instruction
  files for an assistant to fill in and a pending marker.

## Compared with a built-in brief

A brief made of the manifest, the start of the README and a bounded folder
listing was sized on the same copies:

| Project | Built-in brief | engram report |
| --- | --- | --- |
| Next.js starter | 1,979 characters, built in under 1 ms | 2,247 characters |
| Web app `src` | 9,738 characters, built in 3 ms | 65,116 characters |

The brief answers "what is this project and where are things". engram's one
strength, exact symbol lookup, is a question for a working specialist, which
has its provider's own code search.

## Recommendation

- Make the coordinator's project index a brief Hovel builds itself.
- Do not make engram a dependency of Hovel. A person who wants it can install it
  as a skill in their own Claude Code or Codex and attach it to a specialist.
- Whatever is decided, never run from Hovel: `engram install` (edits `~/.claude/CLAUDE.md`, project
  `CLAUDE.md`, `AGENTS.md` and hook files) or `engram hook install` (git hooks
  and `.gitattributes`).
