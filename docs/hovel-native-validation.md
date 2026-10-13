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

Use the server-platform Hovel service PR stack, tool-webkit ConnectionCard stack,
and game-platform Hovel/native-connection stack. Follow server-platform
`docs/hovel/hovel.README.md` for PostgreSQL configuration, migration and explicit
`pnpm start:hovel`; the service binds127.0.0.1:3007. Enable the existing optional
edge Hovel proxy and serve the game host's `hovel.html` with its Godot export.
Use the application's actual account sign-in; do not create substitute credentials.

Set GAMERO_WEB_URL to that Hovel account UI and relaunch with the same native
configuration above. The registered profile is scoped to this configured URL.
Codex must already report `Logged in using ChatGPT` through supported `codex login
status`. User-managed sign-in is `codex login`; no provider token enters Hovel.

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
