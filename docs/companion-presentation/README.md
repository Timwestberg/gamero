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

- `bridge/ChatCapability.kt` (commonMain): the `chat.*` contract types, `ChatLimits` and argument validation. `bridge/SharedRules.kt` holds the profile ID and companion-name rules both chat and pet use.
- `chat/DesktopChat.kt`: one chat view's lease, its Codex app-server, turns and the saved thread. `chat/ChatCodex.kt` is the conversation-only Codex configuration; `chat/ChatHistory.kt` the per-companion record under the profile's Codex home.
- `chat/DesktopSpeech.kt`: spoken replies through the `:voice` module (PocketTTS via sherpa-onnx), with independent stop.
- `providers/CodexAppServer.kt`: bounded stdio JSON-RPC to one Codex app-server, limited to each owner's method list. `providers/CodexRuntime.kt` owns how every Codex child is started (`codexProcess`) and each profile's private Codex home. `providers/CodexConnection.kt` and `providers/DesktopProviders.kt` are the Providers account flow behind `bridge/ProviderCapability.kt`.

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
| `HOVEL_VOICE_MODEL` | `spikes/ember-voice/mac/models/sherpa-onnx-pocket-tts-int8-2026-01-26` | `dev-env.sh`, `desktopVoiceConfig` | PocketTTS model folder. Missing, or sherpa-onnx not linked: voice is unavailable and chat is text only. |
| `HOVEL_VOICE_REFERENCE` | `spikes/ember-voice/mac/models/voices/0a67_enhanced-24k.wav` | `dev-env.sh`, `desktopVoiceConfig` | Reference voice clip for spoken replies. |
| `GAMERO_CODEX_PATH` | `codex` on `PATH`, then Homebrew, then ChatGPT.app's bundled CLI | `findCodexExecutable` | Codex for Providers, chat and the Hovel companion. Without one, chat and Providers say to install Codex. |
| `PATH` | inherited | `findCodexExecutable` | Searched for `codex` when `GAMERO_CODEX_PATH` is unset. |
| `GAMERO_WORKSPACE` | none | `main.kt` | Workspace for approved Hovel companion tasks; the Hovel companion is off without it. |
| `HOVEL_REDUCED_EFFECTS` | unset | `CompanionMain` | `1` starts the two-window prototype with reduced motion and transparency. |
| `HOVEL_PRESENTATION_SCENARIO` | unset (`res://companions/main.tscn`) | `DesktopCompanionHost` | Godot script to run instead of the game scene, for tests. |
| `HOVEL_PRESENTATION_HEADLESS` | unset | `DesktopCompanionHost` | `1` runs Godot with `--headless`, for tests. |
| `HOVEL_PRESENTATION_DIAGNOSTICS` | unset | `CompanionMain` | `1` logs window focus. |
| `HOVEL_TRACE_CONTROLLER` | unset | `DesktopCompanionHost` | `1` prints each forwarded controller input. |
| `HOVEL_ONE_FLOOR_RENDERED` | unset | `CompanionOneFloorTest` | `1` captures native Godot frames instead of running headless. |
| `HOVEL_CHAT_CODEX_SMOKE` | unset | `DesktopChatSmokeTest` | Path to a `codex` executable; runs the real app-server configuration check. |
| `HOVEL_CHAT_VOICE_SMOKE` | unset | `DesktopChatSmokeTest` | `1` runs the local voice playback check. |

The host sets some variables for its children rather than reading them: `HOVEL_PRESENTATION_PORT`, `HOVEL_PRESENTATION_SESSION` and `HOVEL_EMBEDDED` for the game, `HOVEL_COMPANION_APPEARANCE` for the pet, and `CODEX_HOME` for each Codex child.
