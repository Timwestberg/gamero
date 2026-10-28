# Companion presentation

Shared Compose level-up and run-review screens live under `composeApp/src/commonMain/kotlin/org/formulagen/wrapper/progression`. They depend on validated presentation data and callbacks, not Godot or desktop APIs.

For the macOS prototype with the game and these screens in **one window**, use [Play Embedded Companions](embedded-game.README.md). The launcher below retains the earlier two-window host.

```sh
# Java 21; the companion prototype must already be imported by Godot.
HOVEL_GAME_PROJECT=/absolute/game-platform/games/ember-tower ./gradlew :composeApp:runCompanions
```

Use `games/ember-tower/scripts/play-companions-glass.sh` in the game checkout to handle import and launch together. `HOVEL_REDUCED_EFFECTS=1` starts with motion/transparency reduced. The normal Gamero WebView entry point is unchanged.

- `PresentationTypes.kt`: versioned wire types and validation.
- `PresentationController.kt`: preview, confirm, retry and refresh state.
- `CompanionGlass.kt`: shared palette and small companion portrait.
- `CompanionPresentation.kt`: responsive screens and semantic controls.
- `DesktopCompanionHost.kt`: loopback session and game process.
- `CompanionMain.kt`: native prototype window lifecycle.

[Behavior, architecture, usage and test guide](../../../game-platform/docs/hovel-companions/companion-presentation.README.md) live beside the authoritative game feature. Android/iOS hosting needs an adapter; both targets compile the same shared screens.

Run `HOVEL_GAME_PROJECT=/absolute/game-platform/games/ember-tower ./gradlew :composeApp:jvmTest` for real game integration. Tests write disposable saves and UI captures under `composeApp/build/reports/companion-presentation`.

`CompanionOneFloorTest` drives real arena combat through rendered Compose previews, confirmation, resume and the success receipt. Set `HOVEL_ONE_FLOOR_RENDERED=1` for native Godot captures. Add `--rerun-tasks` after external Godot changes. See the [one-floor playtest guide](../../../game-platform/docs/hovel-companions/one-floor-playtest.TEST.md) for evidence and human checks.

## Try the material

Double-click `Preview Companion Glass.command`, or run `./gradlew :composeApp:runCompanionGlassPreview` with Java 21. This separate preview does not launch Godot or write progression. Select a card, confirm, switch between Foundry and Moonlit Garden artwork, resize the window, and toggle Reduced effects.

## Dedicated glass screen

The app's Android minimum stays API 24. The upgrade card uses the same refraction shader on Android 13+ (API 33, AGSL) and desktop/iOS (SkSL); on Android 7–12 the card keeps its plain tinted panel without refraction. The screen supplies its own artwork; even when hosted above the embedded game, the glass does not sample Godot frames.

```kotlin
val scene = PresentationScene("MY GAME") {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF173C48), Color(0xFF101A24))))
    // Draw game-specific artwork here.
}
CompanionPresentation(state, onSelect, onAction, onRetry, scene = scene)
```

`PresentationScene.paint` is a Compose `DrawScope` lambda. It renders once per size/density/scene change into a shared bitmap. Keep it deterministic and static; publish a new scene object when artwork changes. Glass samples that bitmap at each card's screen position. Foreground text and buttons are never filtered. Reduced effects skip shader creation.

`LiquidGlassShader.kt` owns the shared shader and uniform contract, `LiquidGlassSurface.kt` owns composition, and `LiquidGlassRenderer` has Android and shared Skia adapters. Source compatibility does not establish visual or performance parity on physical mobile devices.

## Native chat, voice and providers

The desktop host answers the `chat.*` and `providers.*` host-bridge v2 methods natively; the page never sees a token, path or command line. `DesktopServices.kt` builds these services with the pet and game launchers.

- `bridge/ChatCapability.kt` (commonMain): the `chat.*` contract types (team members, the entry union, `chat.handover`, `chat.retry`, failures), `ChatLimits`, `HandoverLimits` and argument validation. `bridge/ProviderCapability.kt` mirrors `providers.*` (`RuntimeId`, `providers.list`, `providers.models`, status `detail`). `bridge/SharedRules.kt` holds the profile ID, companion-name and model-name rules; a common test checks every mirrored limit against the contract's `fixtures/limits.json`.
- `chat/DesktopChat.kt`: one chat view's lease, its history, request dedupe, the reply watchdog, speech, unanswered messages and retries. It owns no runtime process: each reply runs on the sending member's `ChatRuntime`.
- `chat/ChatRuntime.kt`: the seam (`readiness`, `startTurn`, `summarize`, `release`) and the turn types. `chat/CodexChatRuntime.kt` is Codex behind it (one app-server per active profile, the conversation-only configuration, thread resume, the default model named on `runtime-default` turns, ephemeral summaries). `chat/ClaudeChatRuntime.kt` and `chat/ClaudeStream.kt` are Claude Code behind it (one locked-down `claude -p` per turn, the `system/init` lockdown check, failure classification).
- `chat/ChatHandover.kt`: `Handovers`, the pure transforms of a handover and of the notes the host records itself (a runtime's first turn, catching a runtime up, a model change, a lost conversation), and the context blocks a runtime is owed. `chat/Persona.kt` is the one companion persona both runtimes use.
- `chat/ChatHistory.kt`: the version 2 record at `<providers root>/<profile>/companion-chats/<companionId>.json` (0600): entries, each runtime's native conversation, and the context each runtime is owed. A version 1 record in the profile's old Codex home moves there on first read.
- `chat/DesktopSpeech.kt`: spoken replies through the `:voice` module (PocketTTS via sherpa-onnx), with independent stop.
- `providers/RuntimeProvider.kt`: one `RuntimeProvider` per offered runtime behind `providers/DesktopProviders.kt`: `CodexProvider` (the account process and its model list) and `ClaudeProvider`. `providers/RuntimeCopy.kt` holds each runtime's names and messages for chat and providers.
- `providers/CodexAppServer.kt`: bounded stdio JSON-RPC to one Codex app-server, limited to each owner's method list. `providers/CodexRuntime.kt` owns how every Codex child is started (`codexProcess`): the player's own Codex on its default home, with no Hovel settings and a scrubbed environment. `providers/CodexConnection.kt` reads the player's Codex sign-in for the Providers card; Hovel never signs Codex in or out (the player runs `codex login` / `codex logout`).

### Working members

A team member in `work` mode (`chat.send`/`chat.retry` `mode: work`) uses its provider's own agent with the player's full setup in the companion's project folder ([companion abilities](../../../game-platform/docs/launcher/companion-abilities.SPEC.md)). Which runtimes may work is one answer (`codexWorkEnabled`, Claude when offered): `providers.list.working` tells the page, and the chat's `ChatWork.runtimes` run it.

- `workspace/`: `WorkspaceStore` keeps each companion's folder per profile (`<providers root>/<profile>/workspaces.json`, 0600). `NativeFolderPicker` is the Mac's folder panel. `DesktopWorkspace` answers `workspace.choose|clear|status` with the folder's name and whether it exists, never its path; one picker at a time (a second Choose answers the current status), never interrupted, and its choice is kept even when the bridge call gave up meanwhile.
- `approvals/`: `ApprovalRequest` carries an `ApprovalSubject` with the exact native action. The dialog shows it as it is (the command verbatim, every path in full, only the home folder as `~`); the page only ever sees `summary()`, relative to the project folder. `ApprovalRelay` is how both working runtimes ask: requests are registered on the protocol reader in order, `awaiting-you` is shown only while a dialog is on screen, and a stop, a close, a withdrawal, a timeout or a failure denies. `NativeDialogs` holds the rules (one dialog at a time, 10-minute timeout); its buttons carry their values, and the dismiss button (Deny, Not now) is the default for Escape, Enter and closing. `NativeDialogHost` keeps every button disabled for 750 ms after a dialog appears. `StartOfWork` asks before a member's first working message in a conversation and is remembered for exactly what it named (lease, companion, folder, member), only while the chat's view is still current, and forgotten when the view ends.
- `chat/ChatWork.kt`: the `WorkRuntime` seam (no summaries; a working member summarizes through its talk runtime) and `availability`, the side-effect-free check before the start dialog. `chat/DesktopChat.kt`: no folder, a moved folder, Not now or a runtime that may not work answer as talk with a notice and no activity; otherwise `awaiting-you` while the start dialog is up, then the working turn, whose reply carries `activity` from its start (a retry keeps the steps a failed try finished). A refused send, retry or handover keeps its notice until the state changes. `chat/ChatHandover.kt` builds the contract's structured `WorkLog` (files changed, commands with their results, what was denied, where the work stopped) for summaries and recent-messages handovers, and the prose the next model reads; a runtime's working and talk conversations (`ChatRecord.workConversations`) catch each other up. `ChatHistory` keeps every written record under its read cap, dropping the oldest step details first.
- `chat/claude/`: `ClaudeControl` (the `--permission-prompt-tool stdio` control protocol), `ClaudeActivity` (steps from `tool_use`/`tool_result`), and `ClaudeWorkRuntime`: one long-lived `claude -p` per chat lease in the folder, the working persona appended; another model or folder replaces it with `--resume` once the old process has exited (`set_model` exists in Claude Code's protocol but was not exercised). A session Claude Code cannot resume is a lost conversation. Protocol lines up to 16 MiB are kept; a longer one is answered from its opening (a permission request denied, another request refused, a `result` ending the turn). A permission request after the turn ended is denied. The reply text and every step are scrubbed (folder `.`, home `~`) before the page sees them. The "denied without asking" self-check counts only denials that needed approval, and not in a mode that denies on its own.
- `chat/codex/CodexWorkRuntime.kt`: the same for Codex from its schema, one app-server per chat lease, the model on each `turn/start`; off unless `codexWorkEnabled`.
- `providers/`: a signed-out card (either runtime) carries no `message`; the page owns that help. `companion/CodexCliProvider.kt` asks `codex login status` under each credential store (the file, then the keyring) and runs the task with the store its login was found in, because `--ignore-user-config` would otherwise read only the default file store.

### Codex on the player's own login

Every Codex child (the Providers card, companion chat, Hovel tasks) runs on the player's own Codex sign-in, in Codex's default home (`$CODEX_HOME` when the player set it, else `~/.codex`), and every Hovel profile on the Mac shares it, as with Claude Code.

- **Environment.** `scrubCodexEnvironment` removes `OPENAI_*`, `ACCESS_TOKEN`, `CODEX_API_KEY` (API-key billing) and every other `CODEX_*` variable (another Codex session's, such as `CODEX_SANDBOX`), and keeps `CODEX_HOME` (where the player's own Codex is) and `CODEX_CA_CERTIFICATE`.
- **No Hovel settings.** `forced_login_method` would log the player out of an API-key login, and `cli_auth_credentials_store` would look for the login where the player may not keep it, so neither is passed. Hovel reads the account type instead and uses only a ChatGPT login; an API key shows as an error on the card.
- **Talk-only chat lockdown.** The player's `config.toml` now loads, and Codex merges `-c` tables into it, so `mcp_servers={}` would leave the player's servers running. `TALK_ONLY_LOCKDOWN` is the lockdown, once: the process gets it as `-c` arguments, and before every thread (`thread/start`, `thread/resume`, ephemeral summaries) the chat reads the effective configuration (`config/read`) and sends it again as the thread's own `config`, plus every MCP server, plugin and feature the player's configuration turns on, each turned off by name (`talkOnlyOverrides`). An MCP server that starts anyway ends the turn as a lockdown failure. The player's `~/.codex/AGENTS.md` still loads as instructions (Codex has no switch for it); the persona keeps the companion from mentioning the player's setup.
- **Old per-profile homes.** Hovel used to sign Codex in itself, in `<providers root>/<profile>/codex` (the providers root is `~/Library/Application Support/Gamero/providers/<sha256 of the app origin>`), with the login in the keyring. Those folders stay on disk untouched; nothing signs in there any more. Chat history stays where it is (it is runtime-neutral), but a Codex thread started in an old home cannot be resumed on the player's login, so the next Codex reply starts a new thread with the recent messages and the conversation notes it (a `host` handover entry). Conversations opened since carry `playerLogin`.
- `providers/ClaudeRuntime.kt` finds Claude Code, checks its version and starts its children; `providers/ClaudeConnection.kt` reads its one sign-in (`claude auth status --json`, cached briefly) for the Providers card and chat readiness alike, and `providers/ClaudeStatus.kt` maps it to a card state. Hovel never signs in or out of Claude Code.
- `process/ChildProcess.kt`: bounded reads and commands, SIGINT, and the allowlisted child environment new runtimes use.

Behavior and the web side live with the catalog app in game-platform: [companion chat](../../../game-platform/docs/launcher/companion-chat.README.md) ([architecture](../../../game-platform/docs/launcher/companion-chat.ARCH.md), [tests](../../../game-platform/docs/launcher/companion-chat.TEST.md)) and [providers](../../../game-platform/docs/launcher/providers.README.md) ([architecture](../../../game-platform/docs/launcher/providers.ARCH.md), [tests](../../../game-platform/docs/launcher/providers.TEST.md)). Those docs are on game-platform's companions branch until it merges.

## Desktop companion settings

Every environment variable the desktop companion path reads. The launchers take their defaults from `scripts/dev-env.sh`; a value you export first wins, except `HOVEL_EMBED_LIBRARY`. `GAMERO_WEB_URL` and `GAMERO_PRODUCT` are described in [release.md](../release.md#settings-for-every-native-app).

| Variable | Default | Read by | Effect |
| --- | --- | --- | --- |
| `JAVA_HOME` | `/usr/libexec/java_home -v 21` | `dev-env.sh`, Gradle, the packaging scripts | JDK for builds, jpackage and the embed library's JNI headers; the runtime of the standalone embedded app. |
| `GAMERO_BROWSER_JAVA_HOME` | none; Play From Catalog requires it | `build.gradle.kts`, `package-catalog-macos.sh` | JetBrains Runtime with JCEF that the catalog app bundles and runs on. |
| `GAME_PLATFORM_SIBLING_ROOT` | the folder above this checkout | `dev-env.sh`, game-platform's Vite config | Folder that holds the `game-platform` and `server-platform` checkouts; base of the next two defaults. |
| `HOVEL_GAME_PROJECT` | `$GAME_PLATFORM_SIBLING_ROOT/game-platform/games/ember-tower` | `dev-env.sh`, `main.kt`, `DesktopGameLauncher`, `EmbeddedCompanionWindow`, `CompanionMain`, tests | Companion game project. Its `scripts/godot.sh` must report Godot `4.8.beta1.official.4d10419ca`. Unset in the app, `games.launch` answers `unsupported`; unset in tests, real-Godot tests skip. |
| `GAME_PLATFORM_CONTRACTS_ROOT` | `$GAME_PLATFORM_SIBLING_ROOT/server-platform/event-contracts` | `dev-env.sh`, the catalog's Vite server | Event-contracts checkout the web catalog builds against. Play From Catalog stops if it is missing. |
| `GAMERO_CATALOG_PORT` | `8133` | Play From Catalog, game-platform `companionLauncher.ts` | Loopback port of the catalog server; Play From Catalog sets `GAMERO_WEB_URL` from it. |
| `HOVEL_EMBED_LIBRARY` | `build/embedded-godot/libhovel_embed.dylib`, always | `DesktopGameLauncher`, `MacGameLayer` | JAWT layer bridge for the embedded game. A missing file fails the launch with a log line instead of a crash. |
| `HOVEL_COMPANION_SAVE` | catalog: `~/Library/Application Support/Gamero/companions/save.json`; standalone: a new temporary file | `DesktopGameLauncher`, `EmbeddedCompanionWindow`, the game | Progression save the game owns. Never share one between running games. |
| `HOVEL_PET_PROJECT` | `<game-platform>/ember-desktop-pet` | Play From Catalog, `DesktopPetLauncher` | Ember desktop pet project. Missing build files report the pet unavailable. |
| `HOVEL_PET_GODOT` | `/Applications/Godot.app/Contents/MacOS/Godot` | Play From Catalog, `DesktopPetLauncher` | Godot 4.7.2 executable for the pet. |
| `HOVEL_KOKORO_MODEL` | `spikes/ember-voice/mac/models/kokoro-multi-lang-v1_0` | `dev-env.sh`, `desktopVoiceConfig` | Kokoro v1.0 folder for Mac chat. Needs `model.onnx`, `voices-blends.bin`, `tokens.txt`, and `lexicon-us-en.txt`. Missing, or sherpa-onnx not linked: voice is unavailable and chat is text only. |
| `HOVEL_KOKORO_VOICE` | unset, so the saved id or Sam (`adam70-michael30`) | `desktopVoiceConfig` | Kokoro voice id for this launch. Overrides `~/.gamero/kokoro-voice`. |
| `HOVEL_VOICE_BACKEND` | `kokoro` | `dev-env.sh`, `desktopVoiceConfig` | `kokoro` is Mac chat. `sherpa` is PocketTTS for a later cloned voice. |
| `HOVEL_VOICE_MODEL` | `spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26` | `dev-env.sh`, `desktopVoiceConfig` | PocketTTS model folder. Used when `HOVEL_VOICE_BACKEND=sherpa`. |
| `HOVEL_VOICE_REFERENCE` | `spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav` | `dev-env.sh`, `desktopVoiceConfig` | PocketTTS reference clip. Used when `HOVEL_VOICE_BACKEND=sherpa`. |
| `GAMERO_CODEX_PATH` | `codex` on `PATH`, then Homebrew, then ChatGPT.app's bundled CLI | `findCodexExecutable` | Codex for Providers, chat and the Hovel companion. Without one, chat and Providers say to install Codex. |
| `PATH` | inherited | `findCodexExecutable`, `findClaudeExecutable` | Searched for `codex` and `claude` when their paths are unset. |
| `SHELL` | `/bin/zsh` when unset | `readLoginShellPath` | The login shell a working Claude member's `PATH` is read from, once, so the player's own tools are found (an app started from Finder has only the system folders). |
| `CODEX_HOME` | `~/.codex` (Codex's own default) | every Codex child | Not set by Hovel. When the player set it, Codex children keep it: that is where the player's own Codex is. |
| `CLAUDE_CONFIG_DIR` | `~/.claude` (Claude Code's own default) | working Claude members | Not set by Hovel. When the player set it, a working member keeps it; talk-only members never read it. |
| `HOVEL_CLAUDE_PROVIDER` | `1` from `dev-env.sh`; unset otherwise | `claudeProviderEnabled` | `1` offers Claude in Providers and as a companion team runtime, in a development build only: the app must carry `-Dgamero.dev=true` (Gradle run tasks and the local jpackage prototypes set it) and not `-Dgamero.release=true` (set by `tools/build-release.sh`). See [release.md](../release.md#release-and-development-builds). |
| `HOVEL_CODEX_WORK` | unset | `codexWorkEnabled` | `1` lets Codex team members work in a project folder, in a development build only (`-Dgamero.dev=true`, never with `-Dgamero.release=true`), until Codex's approval protocol is verified live. The same answer is `providers.list.working`; otherwise a working Codex message answers as talk with a notice. |
| `GAMERO_CLAUDE_PATH` | `claude` on `PATH`, then `~/.local/bin`, Homebrew, `/usr/local/bin` | `findClaudeExecutable` | The player's own Claude Code (2.1.304 or later), run with its own sign-in. Hovel never signs in or out. Without one, the Claude card says to install it. |
| `GAMERO_WORKSPACE` | none | `main.kt` | Workspace for approved Hovel companion tasks; the Hovel companion is off without it. |
| `HOVEL_REDUCED_EFFECTS` | unset | `CompanionMain` | `1` starts the two-window prototype with reduced motion and transparency. |
| `HOVEL_PRESENTATION_SCENARIO` | unset (`res://companions/main.tscn`) | `DesktopCompanionHost` | Godot script to run instead of the game scene, for tests. |
| `HOVEL_PRESENTATION_HEADLESS` | unset | `DesktopCompanionHost` | `1` runs Godot with `--headless`, for tests. |
| `HOVEL_PRESENTATION_DIAGNOSTICS` | unset | `CompanionMain` | `1` logs window focus. |
| `HOVEL_TRACE_CONTROLLER` | unset | `DesktopCompanionHost` | `1` prints each forwarded controller input. |
| `HOVEL_ONE_FLOOR_RENDERED` | unset | `CompanionOneFloorTest` | `1` captures native Godot frames instead of running headless. |
| `HOVEL_CHAT_CODEX_SMOKE` | unset | `DesktopChatSmokeTest` | Path to a `codex` executable; runs the real app-server configuration check. |
| `HOVEL_CHAT_VOICE_SMOKE` | unset | `DesktopChatSmokeTest` | `1` runs the local voice playback check. |
| `HOVEL_CHAT_CLAUDE_SMOKE` | unset | `DesktopChatSmokeTest` | Path to a signed-in `claude`; runs two short Haiku turns (a new session, then a resume) on that sign-in. |
| `HOVEL_CLAUDE_WORK_SMOKE` | unset | `DesktopChatSmokeTest` | Path to a signed-in `claude`: runs one short Haiku working turn on the owner's full setup in a fresh scratch folder (never a real project); the test's broker allows only an edit of `notes.txt` and denies everything else. Prints states only. |

The host sets some variables for its children rather than reading them: `HOVEL_PRESENTATION_PORT`, `HOVEL_PRESENTATION_SESSION` and `HOVEL_EMBEDDED` for the game, `HOVEL_COMPANION_APPEARANCE` for the pet, and nothing for Codex children: they keep the player's own `CODEX_HOME`.
