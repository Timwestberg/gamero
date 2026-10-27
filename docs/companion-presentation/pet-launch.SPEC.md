# Launch Ember from Hovel

## What and why
Extend the approved web-to-native launch path with Show Ember / Hide Ember. The existing desktop pet appears above the desktop while Hovel remains usable.

## Behavior
- Show Ember is available from Hovel and the app catalog. It starts one host-owned pet; repeated requests reuse it.
- Show launch progress and actionable failure feedback. A browser without the native host explains that the desktop app is required.
- Hide Ember stops the owned pet. Quitting the pet itself updates the control and permits relaunch.
- The pet survives page navigation and game launch; closing the desktop host stops its pet.
- Keep the pet on Godot 4.7.2, separate from the embedded game's 4.8 runtime.

## Out of scope
Pet art, progression, account or chat integration, capture permissions, automatic launch at login, mobile support, and production installation.
