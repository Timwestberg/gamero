# Validate Hovel inside the existing Mac app

This is a draft integration. Do not infer live browser, Keychain or Codex success
from JVM tests. Mobile remains outside scope. Leave all PRs open; no deployment.

## 1. Simulated embedded interface and package probe

This smoke cannot connect accounts or run provider inference. Build it with
`-Pgamero.simulatedSmoke=true`: the artifact contains a fixed simulation flag;
conflicting JVM/environment requests fail before startup. A normal build retains
its existing default. Do not substitute a normal artifact for this smoke.

Tim's verified tools have separate roles (these paths are Mac evidence, not Linux
validation results):

```sh
export JAVA_HOME="/Users/snowember/Library/Java/JavaVirtualMachines/corretto-21.0.4/Contents/Home"
export GAMERO_PACKAGING_JAVA_HOME="$JAVA_HOME"
export GAMERO_BROWSER_JAVA_HOME="/Applications/IntelliJ IDEA.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java" -version
"$JAVA_HOME/bin/javac" -version
"$JAVA_HOME/bin/jlink" --version
"$JAVA_HOME/bin/jpackage" --version
"$GAMERO_BROWSER_JAVA_HOME/bin/java" -version
"$GAMERO_BROWSER_JAVA_HOME/bin/java" --list-modules | grep '^jcef@'
./gradlew --offline -Porg.gradle.java.installations.auto-download=false javaToolchains
./gradlew --offline -Porg.gradle.java.installations.auto-download=false :composeApp:jvmTest :composeApp:jvmJar --console=plain
./gradlew --offline -Porg.gradle.java.installations.auto-download=false -Pgamero.simulatedSmoke=true :composeApp:verifyDesktopRuntimeWiring --console=plain
./gradlew --offline -Porg.gradle.java.installations.auto-download=false -Pgamero.simulatedSmoke=true :composeApp:jvmTest --tests '*Simulated*Test' :composeApp:jvmJar :composeApp:createDistributable --console=plain
```

The wiring check prints the effective runtime path for every packaging task; each
must resolve to the installed JBR Home above, never `build/compose/tmp/.../runtime`.
It also verifies the packaging JDK and disabled jlink tasks without packaging or
launching. Compose defaults are overridden after its task registration actions,
and the installed runtime properties are frozen against later replacement.

Corretto supplies packaging tools. The app uses the verified JBR/JCEF at launch
and bundles its entire containing runtime bundle, including `Contents/Frameworks`
and symlinks. Packaging replaces only its generated Home-only runtime copy and
runs read-only `codesign --verify --deep --strict` checks against the source JBR,
packaged runtime and outer app. If any check fails, **stop before launch** and
return the exact error. No signing, signature removal, installation, download,
quarantine modification or signing-identity access is performed. Mac packaging,
architecture compatibility and signatures require validation on that Mac.

Only after the package checks pass, use two terminals from the repository root:

```sh
# Terminal 1: the existing static fixture has no provider or network requests.
python3 -m http.server 8765 --bind 127.0.0.1 --directory tools
# Terminal 2: launch only the newly built simulated app, with its bundled JBR.
GAMERO_WEB_URL=http://127.0.0.1:8765/native-bridge-smoke.html \
  composeApp/build/compose/binaries/main/app/GameroSimulatedSmoke.app/Contents/MacOS/GameroSimulatedSmoke
```

The simulated launcher accepts only the loopback `native-bridge-smoke.html` URL.
Do not set a Codex path, workspace or account configuration. It ignores existing
live-provider variables and never constructs the real companion or Codex process
builder. It uses `~/Library/Application Support/Gamero/simulated-smoke` for its
isolated journal. Existing Keychain use and `Gamero/web-cache` are unchanged.

Click Continue. Require visible HTML, a Chromium user agent and
`GAMERO_JCEF_INITIALIZED` in the log. Click Check native bridge: expect version1,
`connectComputer: false`, `cloud: false`, `sandbox: read-only`,
`providerMode: simulated`. First use of the isolated journal logs
`GAMERO_SIMULATED_PROVIDER: launchesThisStart=1 state=SUCCEEDED`.
Reload and repeat the bridge check: expect a new context ID. Close and confirm
browser/JVM termination, then reopen the same artifact: expect
`launchesThisStart=0 state=SUCCEEDED`. An already-used smoke journal also reports0
on its first launch in this validation; do not delete evidence to force a count1.

Treat `GAMERO_RUNTIME_FAILED`, missing HTML, JNI/linkage errors, uncaught exceptions
or a failed bridge check as failed acceptance regardless of launcher exit status.
JCEF initialization waits up to30seconds and shutdown is bounded. A Gradle success
line alone is never rendering/bridge evidence. Do not bypass macOS prompts.

Simulation proves the local bridge and fixed durable receipt path only. It does
not exercise an account, transport polling, live Codex, a Brain identity adapter
or final launch fencing. Those remain separately gated.

## 2. Hovel registration and genuine task acceptance

Use the server-platform Hovel service PR stack, tool-webkit ConnectionCard stack,
and game-platform Hovel/native-connection stack. Follow server-platform
`docs/hovel/hovel.README.md` for PostgreSQL configuration, migration and explicit
`pnpm start:hovel`; the service binds127.0.0.1:3007. Enable the existing optional
edge Hovel proxy and serve the game host's `hovel.html` with its Godot export.
Use the application's actual account sign-in; do not create substitute credentials.

This is a separate live-provider test requiring explicit authorization. Build a
normal artifact without `gamero.simulatedSmoke=true`, set `GAMERO_PRODUCT=hovel`,
and supply an approved `GAMERO_CODEX_PATH` and `GAMERO_WORKSPACE`. Set
GAMERO_WEB_URL to that Hovel account UI and relaunch. The registered profile is scoped to this configured URL.
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
