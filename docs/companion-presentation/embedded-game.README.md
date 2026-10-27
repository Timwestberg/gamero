# Embedded macOS companion prototype

One Hovel window contains the Forward+ Godot game and the existing Kotlin/Compose upgrade and review screens. Godot runs as an owned helper process without its own window.

For the app entry path, use [Play from the web catalog](catalog-launch.README.md). The standalone command below remains a development shortcut.

## Launch

Install Java 21 and Xcode command-line tools. Import the sibling `game-platform/games/ember-tower` project with Godot **4.8.beta1.official.4d10419ca** first, as required by the existing companion launcher. Then double-click `Play Embedded Companions.command`, or run:

```sh
./'Play Embedded Companions.command'
```

The launcher builds a small JNI library and refreshes a local named `.app` under ignored `build/embedded-godot`. The first launch also copies the JDK into that bundle. Use the command launcher because the app currently needs its environment and source checkouts.

Every launch defaults to a new temporary save and prints `HOVEL_EMBEDDED_SAVE <path>`. To keep a particular playtest across launches:

```sh
HOVEL_COMPANION_SAVE=/absolute/playtest/save.json ./'Play Embedded Companions.command'
```

Override `HOVEL_GAME_PROJECT` for another checkout of the same companion prototype. `JAVA_HOME` selects the JDK (default: `java_home -v 21`). Both come from `scripts/dev-env.sh`; see [every setting](README.md#desktop-companion-settings). Close this prototype before refreshing its bundle. The existing `:composeApp:runCompanions` launcher remains available.

## Play and extend

Enter the Foundry. Move with A/D, jump with Space, aim and fire with the mouse, dash with Shift, and pause with Escape. Earn an upgrade to open the glass cards. Preview, confirm, and continue to the same run; review uses the same window. Use macOS window controls for full screen.

On the Kotlin screens, use the D-pad or left stick to move focus. Cross/A previews the focused card; move to **Choose…** and press Cross/A again to confirm. Circle/B goes back. Release the stick between steps. The first action receives focus when the screen changes, including the final review.

Shared presentation code and progression contracts remain unchanged. `EmbeddedCompanionWindow` composes the native surface and `CompanionPresentation`. `MacEmbeddedGame` owns rendering/input transport; `MacGameLayer` is the JNI surface boundary. A different platform should replace those desktop adapters while retaining the common UI and authoritative game commands.

This is a development proof using Godot's editor embedding infrastructure and a private macOS layer API. It is **not a production distribution build**. The card shader still samples its supplied artwork, not live game pixels. See [architecture and limits](embedded-game.ARCH.md), [validation](embedded-game.TEST.md), and [review](embedded-game.REVIEW.md).
