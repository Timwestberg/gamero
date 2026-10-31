# Desktop execution and recovery

Conversation work runs through `ConversationRunner` and `CoordinatorEngine`.
The runner owns the host stream, heartbeat, claim journal and one serial lane
per conversation. The coordinator owns provider turns, delegated threads,
workflow checkpoints and the outbox. See [Hovel host architecture](hovel-companion.md)
for paths, trust boundaries, approvals and voice.

The separate `MemoryJobLane` still uses the reusable execution journal for
Codex memory extraction and consolidation. A Mac can register without Codex;
when Codex is absent it does not run memory jobs. Neither lane is owned by an
open browser page.

## Recovery rules

- Persist a claim identity before requesting work. Retry delivery with that
  identity, not a second provider run.
- Preserve ambiguous execution for reconciliation. Do not automatically replay
  file-changing work after a crash.
- Keep account/host journals and provider session bindings separate. An unreadable
  store must not prune sessions that a conversation could resume.
- The Mac keeps provider credentials and local folder paths. The server receives
  bounded conversation progress, artifacts and approvals, not those credentials.

## Verification

From the Gamero root with JDK 21 and dependencies already installed:

```sh
./gradlew --offline :composeApp:jvmTest :voice:jvmTest
```

Tests use fixtures for provider processes. Real replies, restart continuity,
Stop/approval races and context-folder access require separately authorized
live checks. The old Hovel-task execution API and its read-only prototype
instructions are retained only in Git history.
