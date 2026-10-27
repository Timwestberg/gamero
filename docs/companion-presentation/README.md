# Companion presentation

Shared Compose level-up and run-review screens live under `composeApp/src/commonMain/kotlin/org/formulagen/wrapper/progression`. They depend on validated presentation data and callbacks, not Godot or desktop APIs.

For the macOS prototype with the game and these screens in **one window**, use [Play Embedded Companions](embedded-game.README.md). The launcher below retains the earlier two-window host.

```sh
# Java 21; the companion prototype must already be imported by Godot.
HOVEL_GAME_PROJECT=/absolute/game-platform-companions/games/ember-tower ./gradlew :composeApp:runCompanions
```

Use `games/ember-tower/scripts/play-companions-glass.sh` in the game checkout to handle import and launch together. `HOVEL_REDUCED_EFFECTS=1` starts with motion/transparency reduced. The normal Gamero WebView entry point is unchanged.

- `PresentationTypes.kt`: versioned wire types and validation.
- `PresentationController.kt`: preview, confirm, retry and refresh state.
- `CompanionGlass.kt`: shared palette and small companion portrait.
- `CompanionPresentation.kt`: responsive screens and semantic controls.
- `DesktopCompanionHost.kt`: loopback session and game process.
- `CompanionMain.kt`: native prototype window lifecycle.

[Behavior, architecture, usage and test guide](../../../game-platform-companions/docs/hovel-companions/companion-presentation.README.md) live beside the authoritative game feature. Android/iOS hosting needs an adapter; both targets compile the same shared screens.

Run `HOVEL_GAME_PROJECT=/absolute/game-platform-companions/games/ember-tower ./gradlew :composeApp:jvmTest` for real game integration. Tests write disposable saves and UI captures under `composeApp/build/reports/companion-presentation`.

`CompanionOneFloorTest` drives real arena combat through rendered Compose previews, confirmation, resume and the success receipt. Set `HOVEL_ONE_FLOOR_RENDERED=1` for native Godot captures. Add `--rerun-tasks` after external Godot changes. See the [one-floor playtest guide](../../../game-platform-companions/docs/hovel-companions/one-floor-playtest.TEST.md) for evidence and human checks.

## Try the material

Double-click `Preview Companion Glass.command`, or run `./gradlew :composeApp:runCompanionGlassPreview` with Java 21. This separate preview does not launch Godot or write progression. Select a card, confirm, switch between Foundry and Moonlit Garden artwork, resize the window, and toggle Reduced effects.

## Dedicated glass screen

Android minimum is API 33. The upgrade card uses the same refraction shader on Android (AGSL) and desktop/iOS (SkSL). The screen supplies its own artwork; even when hosted above the embedded game, the glass does not sample Godot frames.

```kotlin
val scene = PresentationScene("MY GAME") {
    drawRect(Brush.verticalGradient(listOf(Color(0xFF173C48), Color(0xFF101A24))))
    // Draw game-specific artwork here.
}
CompanionPresentation(state, onSelect, onAction, onRetry, scene = scene)
```

`PresentationScene.paint` is a Compose `DrawScope` lambda. It renders once per size/density/scene change into a shared bitmap. Keep it deterministic and static; publish a new scene object when artwork changes. Glass samples that bitmap at each card's screen position. Foreground text and buttons are never filtered. Reduced effects skip shader creation.

`LiquidGlassShader.kt` owns the shared shader and uniform contract, `LiquidGlassSurface.kt` owns composition, and `LiquidGlassRenderer` has Android and shared Skia adapters. Source compatibility does not establish visual or performance parity on physical mobile devices.
