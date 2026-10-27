# Pet launch validation

## Local evidence
- macOS / Apple M3 Max, official Godot 4.7.2 with Forward+ for Ember, Godot 4.8-beta1 for the embedded game, installed JBR/JCEF 21.0.9.
- 95 desktop host JVM tests passed, including four real-process lifecycle cases. The four targeted cases also passed after adding a macOS-only test assumption.
- 22 existing pet Kotlin tests passed; the pet was built and imported successfully.
- 20 focused web tests passed, including shared contexts, unknown methods on an older host, expired contexts, response validation and existing catalog tests. The complete host type check passed with the matching sibling sources documented in the catalog guide. Changed web files passed Biome.
- Live catalog Show Ember displayed the actual desktop overlay; Hovel navigation retained the same process. Hide Ember reaped it, and Show Ember on Hovel launched it again.
- Closing the host with Ember running stopped the app, pet and local catalog server. Independently launched Godot processes remained untouched.
- Live coexistence passed: the 4.8 game reached its embedded roster while the same 4.7.2 pet stayed alive; Back to games restored the catalog with Hide Ember still shown.

## Playtest
Setup: follow [pet setup](pet-launch.README.md), then launch the catalog app.

1. Choose **Show Ember**. Expect waking-up feedback, followed by **Hide Ember** and a visible desktop overlay.
2. Navigate to **Hovel · Return home**. Expect the pet to remain visible and the control to say **Hide Ember**, including before sign-in.
3. Choose **Hide Ember**, then **Show Ember**. Expect one pet to close and one new pet to appear.
4. Quit through the pet menu. Within two seconds expect **Show Ember**; launch it again.
5. From the catalog, launch Hovel Companions while Ember is running. Expect the game in Gamero and Ember on the desktop. Return with **Back to games**; expect the pet to remain running.
6. Close the host. Expect its pet to close too. Start the host again; expect **Show Ember**, with no automatic pet launch.

## Edges and limits
- A plain browser explains that the desktop app is required after Show Ember. Missing installations and wrong engine versions display feedback without launching a substitute executable.
- Repeated Show requests reuse the owned process. Extra request fields and unknown methods are rejected. Cancellation during startup reaps the unfinished child.
- Polling stops after a bridge error; a later explicit action can retry. Unsupported pet methods on older hosts do not reset a valid game context.
- Signed-in room toolbar placement is implemented and type-checked; local visual validation covered the catalog and Hovel welcome screen. Account integration, controller interaction with the web button, other operating systems, and production packaging are not established by these checks.
