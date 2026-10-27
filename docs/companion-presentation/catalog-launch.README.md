# Play from the web catalog

```sh
export GAMERO_BROWSER_JAVA_HOME="<installed JBR with JCEF>/Contents/Home"
scripts/fetch-voice.sh   # once, for spoken chat replies
./'Play From Catalog.command'
```

1. Continue into Gamero's existing web app.
2. Select Hovel Companions in Your adventures.
3. Enter the Foundry; upgrade and review screens appear inside the same window.
4. Use Back to games to return to the catalog, then open the game again.

Choose **Show Ember** in the catalog or Hovel to launch the desktop pet alongside the app. See [pet setup and controls](pet-launch.README.md).

The launcher reads its defaults from `scripts/dev-env.sh`: JDK 21 from `java_home` unless `JAVA_HOME` is set, the game in the sibling `game-platform` checkout, and contracts in `server-platform/event-contracts`. Install the game-platform dependencies first with pnpm install; its web toolkit and event contracts must match the web app. For worktrees with other names, export `HOVEL_GAME_PROJECT` and `GAME_PLATFORM_CONTRACTS_ROOT` (or `GAME_PLATFORM_SIBLING_ROOT` for a whole sibling set) before launch. GAMERO_CATALOG_PORT defaults to 8133. [Every setting](README.md#desktop-companion-settings) is listed in one table.

Progress defaults to ~/Library/Application Support/Gamero/companions/save.json. HOVEL_COMPANION_SAVE selects an existing playtest save; never share it between simultaneously running games. The standalone Play Embedded Companions command remains a development shortcut.

The local catalog fixture is apps/game-bridge-host/fixtures/companions.catalog.json. Native catalog entries use runtime: native; native code must separately allow the ID and install the game. A catalog entry cannot select an executable or filesystem path.
