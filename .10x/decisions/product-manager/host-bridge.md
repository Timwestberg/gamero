# host-bridge: scope

## Users
- **Web app developers** (game-platform): call native features without per-platform code.
- **Host developers** (gamero): add a native feature in one known place.
- **Players**: get the same behavior on every device.

## Stories
1. As a web developer, I call `host.capabilities()` and get the same answer shape on desktop, Android and iOS.
2. As a web developer, I call `host.call("link.open", {url})` and the system browser opens on every platform.
3. As a host developer, I add a capability, and the compiler tells me each platform that has no implementation.
4. As a player on desktop, the Hovel companion keeps working through the change.

## Success criteria
- One smoke page runs every capability on desktop, Android and iOS with the same output, except the cells the parity table marks `unsupported`.
- The router fixture tests pass on JVM, Android unit and iOS simulator targets.
- The existing desktop companion checks in `docs/hovel-native-validation.md` still pass.
- Adding `haptic.play` touches only its contract, interface, implementations and fixtures.

## Out of scope
- Legacy Worldwinner flows (Amplitude, AppsFlyer, Braze, audio). Frozen.
- New product features beyond the four proof capabilities.
