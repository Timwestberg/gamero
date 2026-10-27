# Catalog launch validation

## Local evidence
- Apple M3 Max, Godot 4.8-beta1 / Metal Forward+, installed signed JBR/JCEF 21.0.9.
- 91 JVM tests passed, including installed-ID validation and the existing real-game/presentation integration tests.
- 16 focused web tests passed. The complete host type check passed with matching sibling contracts/toolkit sources; changed TypeScript/JSON files passed Biome.
- Native mouse round trip passed: Continue -> existing glass catalog -> Hovel Companions -> Play -> embedded roster -> Kotlin final review -> Back to games. One app window remained visible; returning stopped the game helper.
- A separate test save passed Level Choices -> Free respec -> Energy preview 60 to 63 -> Choose Energy -> Upgrade saved -> roster -> Back to games. The save contained the confirmed Energy allocation.
- The return control uses a Compose surface. A Swing button was visible during loading but became covered after Godot attached; this was reproduced and corrected.
- Packaging includes JBR's signed Frameworks directory beside Home. jpackage's Home-only runtime copy omitted Chromium and crashed during startup; the corrected bundle initialized JCEF and rendered the catalog.

- Initial close-during-game testing exposed a JAWT detach after peer disposal. The window close handler now tears down the game before exitApplication; fallback cleanup checks Canvas displayability. The native retest passed: closing Gamero with the game attached stopped the app, game helper and catalog server without a new JVM crash log.

## Playtest
Setup: install dependencies, select the installed JBR runtime, then run Play From Catalog.command as described in the README. Use an isolated HOVEL_COMPANION_SAVE for tests that change a build.

1. Press Continue, select Hovel Companions, then Play.
2. Expect the native roster in the same Gamero window, with Back to games visible above the game.
3. Open Level Choices or Last Review. Expect the existing Kotlin glass UI above the game.
4. Preview an available stat and confirm it. Expect Upgrade saved before returning.
5. Press Back to games. Expect the original web catalog, with no remaining Godot child of that app.
6. Launch again. Expect the saved level, equipment and allocation to remain.
7. Close Gamero during a game. Expect the game helper and local catalog server to exit.

## Edges and limits
- Plain browser: native games explain that Gamero desktop is required. Web exports still use their existing iframe/session flow.
- Unknown IDs or extra launch fields are rejected. The page cannot select project, executable or save paths.
- Back to games ends the current helper. An unfinished run follows the existing interrupted-run recovery rules on next launch.
- This local catalog fixture does not publish to the catalog service or submit native results to the web result service. Godot remains the progression authority.
- Physical controller delivery, mobile embedding, production signing/export, and mixed-DPI displays are not established by these checks.
