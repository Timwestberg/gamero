# Embedded game validation

Tested locally on Apple M3 Max with Java 21 and Godot 4.8-beta1, Metal/Forward+. All saves were isolated from the existing user playtest.

## Automated checks

```sh
HOVEL_GAME_PROJECT=/absolute/game-platform-companions/games/ember-tower \
  ./gradlew :composeApp:jvmTest :composeApp:prepareEmbeddedCompanionApp
```

90 JVM tests passed, including the binary protocol boundary, script-stop error reporting, presentation retry/reconnect, and real game progression integration. The companion Godot suite separately passed 160 checks. These checks do not establish native layer ordering.

## Controller regression

The growth-to-review Compose test failed before the fix: controller navigation retained focus from the earlier screen. It now passes, along with initial focus, preview before confirmation, duplicate-confirm protection, and review back/accept actions. The Godot checks exercise stick drift, hold/diagonal suppression, screen-entry priming, device disconnect, face-button mapping, bridge forwarding, and leaving gameplay input alone when the presentation is closed.

For a physical check, launch with `HOVEL_TRACE_CONTROLLER=1`. Use the D-pad and separate left-stick tilts to move between choices; Cross/A previews, then move to Choose and press Cross/A to confirm. On the review, navigate between Return to Hovel and Shared equipment; Circle/B returns. The host log should show matching `HOVEL_CONTROLLER_INPUT` messages. Injected events do not establish physical controller delivery.

The updated app was rebuilt and reopened with the user's existing completed-run save, backed up before restart. The native roster and Kotlin review both showed Ember at Level 2 with 130 XP, the retained Arc rifle, and the banked Foundry lance. A DualSense was detected on macOS; physical button delivery remains unconfirmed.

## Native round trip

```sh
HOVEL_PRESENTATION_SCENARIO=res://companions/tests/embedded_handoff_scenario.gd \
  ./'Play Embedded Companions.command'
```

The fixture starts a run and awards five synthetic kill events after three seconds to reach an upgrade. It checks pause, hidden Godot handoff UI, retained run identity, resumed processing, and the later failure receipt. It is an integration fixture, not a combat balance test. Complete it within 90 seconds; after completion it remains on the roster.

1. See three glass choices above the actual game scene.
2. Enter full screen. Choose Energy, confirm 60 → 63, and continue.
3. See the review in that same window. Return to Hovel.
4. Expect log markers `GROWTH_READY`, `RESUMED`, and `ROUNDTRIP_OK` with the `HOVEL_EMBEDDED_` prefix.

This sequence passed through native mouse clicks, including full-screen card rendering and the roster showing Level 2 / Energy 63. Normal-game mouse entry, movement, jump, and pause were also exercised. Terminating the host with SIGTERM removed its Godot child, verified by process IDs.

The command launcher was also exercised directly. A native drag resized the window from 1440 × 920 to approximately 1122 × 727 points; the scene remained aligned and clicking Level Choices opened the centered Compose panel. Stopping only the test engine while that panel was open replaced both surfaces with a visible error. Closing that error window exited the host.

## Remaining hardware checks

Physical controllers, mixed-DPI external displays, other Macs, mobile platforms, and production exports are not covered. Recheck native composition whenever Godot, Compose/Skiko, or the JDK changes. A successful headless test cannot substitute for seeing both layers together.
