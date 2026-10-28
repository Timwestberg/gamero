# Desktop execution core

This reusable JVM core has no Hovel domain dependency and is not yet connected to
the WebView or a server. The product adapter supplies `ExecutionRemote`; native
configuration supplies one registered host ID, private journal/output directories,
a user-selected workspace and an absolute path to supported Codex tooling.

## Task lifecycle

`ExecutionCoordinator.accept` persists the immutable task and a generated claim
ID before requesting a server claim. Claim delivery can retry with that same ID.
After acknowledgement it durably records `LAUNCHING` before any provider process.
A crash or exception beyond that boundary becomes `RECONCILIATION_REQUIRED` and
never launches again automatically. Successful/failed results are durable before
publication; reconnect republishes the saved receipt. A task cannot change its
host, prompt, context, recalled memory or model choice under the same ID.

`ExecutionRequest.recalledMemory` (a task's generated notes) and
`ExecutionRequest.extraction` (a memory job's model and effort) are left out of
a record while they are null, so a record without them is the same as before
and an older companion still reads it. A record holding either cannot be read
by a companion older than these fields; memory jobs use their own journal
directory, which an older companion never opens.

A process lock prevents two companions from owning the same host journal. Atomic
replacement and file/directory sync protect receipts. Corruption fails closed and
preserves evidence. Full bounded output remains locally; server previews are
limited to 65,536 characters and explicitly indicate truncation. Provider output
files also survive ambiguous failures for manual inspection. Store each host's
journal in a separate private directory and retain it when offline or signing out.

## Codex boundary

`CodexCliProvider` checks `codex login status` for a ChatGPT subscription login.
Authentication remains in the user's supported Codex installation. The adapter
never accepts or stores provider credentials and removes inherited OpenAI API-key
environment variables. It forces ChatGPT login mode, ignores user configuration,
and invokes `codex exec` directly with stdin, no shell, ephemeral execution,
approvals disabled, and a read-only sandbox. This initial capability can inspect a
workspace but cannot promise file-changing work. Missing/incompatible tooling or
sign-in prevents useful execution; no cloud handoff is implemented.

stdin carries the text from `providerPrompt`: for a task, the character
context, recalled memory when present, then the task; for a memory job, the
job's input alone. A task runs on Codex's own model and effort. A memory job
adds `-c model_reasoning_effort="<effort>"` before `exec` and `-m <model>` after
it, each only when the value is not `runtime-default` (`codexExecArguments`).
A model is checked against the server's rule (a letter or digit, then letters,
digits and `._:/-`, at most 256 characters), so it is never read as a flag.

Only fixture executables are used by unit tests. Live CLI compatibility and an
explicitly authorized bounded Mac task remain integration acceptance gates. The
core must not be advertised as a connected computer before the product adapter,
origin-checked native bridge, heartbeat and secure host registration are complete.

## Verification

Run `./gradlew :composeApp:jvmTest :composeApp:jvmJar` with JDK21. Tests cover
ambiguous claims/launches, restart delivery, mutation rejection, corruption,
concurrent ownership, bounded previews, oversized encoded records, CLI arguments,
stdin isolation, subscription readiness, nonzero provider exits, records an
older companion wrote, recalled memory and memory job limits, and memory job
model and effort flags.
