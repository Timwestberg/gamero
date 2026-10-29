# Validate Hovel inside the existing Mac app

This is a draft integration. Do not infer live browser, Keychain or Codex success
from JVM tests. Mobile remains outside scope. Leave all PRs open; no deployment.

## 1. Safe embedded interface and native capability probe

Use JDK21 for Gradle and the separately verified JBR/JCEF runtime for launch.
No download or quarantine modification occurs in Gamero. Keep any actual macOS
security/license approval visible to the user.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain
# Separate terminal, from the repository root:
python3 -m http.server 8765 --bind 127.0.0.1 --directory tools
# Launch terminal:
export GAMERO_BROWSER_JAVA_HOME="<verified-JBR-with-JCEF>/Contents/Home"
export GAMERO_PRODUCT=hovel
export GAMERO_CODEX_PATH="/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex"
export GAMERO_WORKSPACE="<existing-user-approved-read-only-workspace>"
GAMERO_WEB_URL=http://127.0.0.1:8765/native-bridge-smoke.html ./gradlew :composeApp:run --console=plain
```

Click Continue. Record visible HTML and Chromium user agent. Click Check native
bridge: expect version1, connectComputer true, cloud false, sandbox read-only.
Reload and repeat: expect a new context ID. Close; confirm JVM/browser termination.
This fixture does not register a host, store credentials, sign in or execute Codex.

Click Check host bridge v2. Each line shows the call, its round-trip time, and the
reply. With the companion configured, expect these replies:

| Line | Expected reply |
|---|---|
| `watchContext` | `{"version":2,"contextId":"<uuid>"}` |
| `host.capabilities` | `ok:true`, methods `host.capabilities`, `host.info` and the three `companion.*` methods, and no events |
| `host.info` | `ok:true`, `platform` `desktop`, `bridgeVersion` 2 |
| `companion.capabilities` | `ok:true`, `connectComputer` true, `cloud` false, `sandbox` `read-only` |
| `haptic.play` | `ok:false`, code `unsupported` |
| `link.close` | `ok:false`, code `unknown-method` |
| stale context | `ok:false`, code `expired` |
| non-persistent watch | `ok:false`, code `invalid` |

Without `GAMERO_CODEX_PATH` and `GAMERO_WORKSPACE`, the three `companion.*` methods
are missing from `host.capabilities`, and `companion.capabilities` answers
`unsupported`. Check native bridge (version 1) then fails with 403. That is the
expected result, because only the companion uses version 1. The page also logs
the whole result after `GAMERO_BRIDGE_V2_SMOKE`.

Require `GAMERO_JCEF_INITIALIZED` in the launch log before the browser appears.
JCEF startup is asynchronous: Gamero now waits up to30seconds for its native
initialization callback before exposing WebView. A runtime initialization timeout,
Compose window exception or uncaught background exception logs
`GAMERO_RUNTIME_FAILED`, closes the app and exits nonzero after bounded cleanup.
Treat any such marker, JNI/linkage error, exception dialog, missing visible HTML
or failed bridge check as failed acceptance even if an outer launcher reports
success. A Gradle success line alone is never rendering/bridge evidence.
Keep the existing verified signed JBR/JCEF installation; this fix does not replace
runtime binaries, change quarantine or broaden access.

The bridge uses public JCEF persistent queries. Native browser/main-frame/origin
facts and a per-context UUID gate every operation. Context release cancels the
persistent query and invalidates pending authorization. Actual injection and
cancellation delivery must be verified against the installed runtime.

## 2. Hovel registration and genuine task acceptance

> Project conversations (companion projects step C) run beside Hovel tasks, which
> stay until step E removes the web task UI; the task steps below still apply
> with `GAMERO_WORKSPACE` set. The conversation run, against a hovel-service
> with the conversation routes (server-platform main since step B): register
> once, then quit and relaunch Hovel and confirm the status line reaches
> "Connected · Conversations run on this Mac" with no page action. Then, from a
> browser on the same Mac: send a message in a talk-only project (the reply
> streams); choose a working folder with `project.chooseFolder` and send work to
> a working specialist (the start of work asks natively, the thread fills in);
> reply in the thread; quit mid-turn and relaunch (the message settles
> `reconciliation-required` and does not run again); go offline, send, come
> back (it runs once). See [hovel-companion.md](hovel-companion.md).

Use the server-platform Hovel service PR stack, tool-webkit ConnectionCard stack,
and game-platform Hovel/native-connection stack. Follow server-platform
`docs/hovel/hovel.README.md` for PostgreSQL configuration, migration and explicit
`pnpm start:hovel`; the service binds127.0.0.1:3007. Enable the existing optional
edge Hovel proxy and serve the game host's `/hovel` page with its Godot export.
Use the application's actual account sign-in; do not create substitute credentials.

Set GAMERO_WEB_URL to that Hovel account UI and relaunch with the same native
configuration above. The registered profile is scoped to this configured URL.
Sign in to Codex on this Mac with `codex login` in Terminal, then open
**Profile → Providers** in that same Hovel account and choose Check again until
Codex shows Connected. Hovel uses the player's own Codex login (Codex's default
home) for the Providers card, companion chat and the approved task adapter alike,
and never signs in or out itself; no provider token enters the web view or Hovel
service. Provider connection alone does not register this computer.

**Approval gate for the coordinator:** the validator's read-only browser permission
does not itself authorize storing a host credential or launching a provider task.
Have Tim approve the concrete native computer-connection dialog before continuing.
It identifies the account, server and workspace and explains Keychain persistence
and read-only execution. Do not bypass Keychain/macOS prompts.

After native approval, web registration sends only host identity and a SHA256
digest. The native bearer stays in Keychain and native HTTP memory. The companion
polls heartbeat every10seconds and executes one host-pinned local task at a time.
Cloud capability remains false. No silent migration or automatic ambiguous retry.

Submit one explicitly approved bounded prompt such as: `Reply with Hovel connection
verified. Do not use tools or modify files.` Record server task ID, pinned host ID,
claimed/running/succeeded states and actual result. Disconnect/reconnect the app
and confirm the same task is not launched again. For a deliberately interrupted
launch, expect reconciliation-required rather than automatic execution. Inspect
another host selection/readiness separately. Use test accounts/data only as
explicitly authorized by the coordinator.

The existing native journals and output files retain reconciliation evidence.
Closing stops polling and interrupts local execution; interrupted claims remain
ambiguous. Restart requires the user's explicit Connect computer/native approval
again and reuses the same persisted host identity. Native profile switching across
accounts is intentionally rejected in this first slice.

## 3. Memory: recalled notes and a memory job

The unit tests run a fake Codex, so the real CLI's model and effort flags are
checked here. Use a test account whose memory is turned on in Hovel's memory
settings with this computer as the extraction computer.

1. **Flags.** On the Mac, run `codex exec --help` and `codex --help` for the
   installed version. Confirm `exec` takes `-m <model>` and that
   `-c model_reasoning_effort="low"` is accepted before `exec`. If not, change
   `codexExecArguments` in `companion/CodexCliProvider.kt` before going on.
2. **A memory job.** Finish one approved task, then wait for the next local day
   (or use a test account with an earlier finished day). Within 5 minutes the
   companion calls `memory/consolidate`, the next heartbeat brings a memory job,
   and it runs beside any running task. Confirm it reaches `succeeded` and the
   day's notes appear in Hovel. Repeat with a named model and `high` effort in
   memory settings and confirm Codex used them.
3. **No project instructions.** Put an `AGENTS.md` in the player's workspace
   that asks for a different output. Confirm the memory job's result is still
   memory JSON: memory jobs run in `memory-workspace-<hostId>`, not the
   workspace. Repeat with one in `~/.codex/AGENTS.md`: memory jobs use the
   player's own Codex home, so if `--ignore-user-config` does not keep it out,
   record that here before release.
4. **One login, two runs.** Start a long task, let a memory job run beside it,
   then confirm `codex login status` still reports a ChatGPT login. Both runs
   share one sign-in and may refresh it at the same time.
5. **Recalled memory.** Submit an approved task for the same character. Confirm
   the task's recalled notes appear in Codex's input in their own section,
   between the character context and the task.

Memory job receipts and output stay in `memory-journal-<hostId>` and
`memory-output-<hostId>`, apart from the task journal.
