# Embedded macOS game prototype

## What and why

The approved next slice hosts the existing companion game and Kotlin upgrade UI in one Hovel window on macOS. The separate-process launcher can leave a paused game in a full-screen Space while its upgrade window appears elsewhere. The embedding prototype proves the intended application boundary before expanding the UI.

## Behavior

- One application window contains the live Godot game and Compose presentation.
- An earned upgrade presents the existing preview and confirmation flow above the game. Gameplay pauses and UI input cannot also move or attack.
- Closing or confirming the upgrade returns control to the same run. The game stays in the same window, including in full screen.
- Resize, focus loss, and closing the host have explicit behavior. The game must not outlive its host.
- Tests and the first interactive launch use a separate save, preserving the existing user's run.
- Existing gameplay, progression rules, and confirmation semantics remain authoritative in Godot.
- D-pad and deliberate left-stick tilts navigate the shared screens. The bottom face button previews or activates the focused control; the right face button goes back. Preview and confirmation remain separate actions, including after moving from growth to review.

## Out of scope

Android, iOS, Windows, Linux, production packaging, live game-pixel refraction, and replacing the standalone launcher. The glass material initially keeps its existing supplied artwork. This is a native integration proof, not a promise that every platform uses identical embedding code.

