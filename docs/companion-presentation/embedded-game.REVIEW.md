# Review: embedded macOS game prototype

**Verdict:** Ready to merge as a development prototype
**Reviewed:** JVM `progression/embedded`, the desktop host launch/lifetime changes, JNI layer bridge, build/launch scripts, embedded Godot presentation guards, integration fixture, and feature docs. Existing character and progression changes are outside this review.

## Blockers

None for the approved local macOS proof. This verdict does not approve production distribution or other platforms.

## Fixed in this pass

- `GameCanvas.kt`: clicks outside a modal panel no longer steal keyboard focus for the disabled game surface.
- `DesktopCompanionHost.kt`: process creation and close share a lifecycle lock, covering close during launch.
- `MacEmbeddedGame.kt`: explicit IPv4 loopback matches launch arguments; initial context reads have a timeout.
- Native/JVM composition: explicit Swing layer assignment and native game z-position keep Compose visible. Failure detaches the remote surface before displaying error text.
- Shutdown hook and fixture completion guard prevent orphaned helpers and a completed fixture timing out later.
- Launcher reuses its development bundle and docs identify the supported entry point and isolated save behavior.
- Controller focus now follows the current screen instead of capturing the first screen's state. Each screen starts with an actionable focused control; selection still requires a separate confirmation.
- A small Godot input translator adds left-stick navigation with press/release thresholds, diagonal suppression, and entry/disconnect handling. It reuses the existing semantic transport and consumes controller events only while the presentation owns input.

## Notes

- The renderer and binary input protocol are pinned to Godot's editor backend. Replacing them is isolated to the desktop adapters; the common presentation and progression contract stay reusable.
- The codec accepts a small checked set of Kotlin runtime types. Unknown values fail at encoding; it does not perform unchecked casts or general Variant deserialization.
- CALayerHost, the unauthenticated local debugger connection, fixed 2x rendering, and source-checkout packaging are deliberate proof constraints. Production needs a supported native embedding contract and authenticated transport. Do not ship this launcher as the release architecture.
- No platform-neutral engine abstraction was added before a second platform exists. Physical controllers and mixed-DPI displays remain explicit validation gaps.

## What works

- The same preview, confirmation, persistence acknowledgment, and review UI operates above the real Forward+ scene in a single full-screen window.
- Native composition was verified separately from 90 passing JVM tests and 160 Godot checks; resize, input, engine failure, and helper cleanup were exercised locally. The controller screen-transition regression failed before the fix and passed afterward; physical controller delivery still needs a playtest.
- Progression remains authoritative in Godot, and the original two-window host still uses the same optional launch seam.

Merge recommendation: keep this bounded adapter as the macOS development prototype. Decide the production embedding path before extending it into release packaging.
