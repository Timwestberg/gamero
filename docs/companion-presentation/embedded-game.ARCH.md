# Embedded macOS game architecture

## Data and interfaces

The existing `PresentationSnapshot`, `PresentationCommand`, `PresentationController`, and authenticated desktop transport are unchanged. Godot still owns progression, persistence, and gameplay pause state.

```kotlin
class MacEmbeddedGame(scope: CoroutineScope) : AutoCloseable {
    val launchArguments: List<String>
    val contextId: StateFlow<Long?>
    fun resize(width: Int, height: Int)
    fun input(event: ByteArray)
    fun releaseInput()
}
object MacGameLayer {
    external fun attach(canvas: Canvas, contextId: Long): Boolean
    external fun detach(canvas: Canvas)
}
```

## Native boundary

The inspected Godot 4.8-beta1 revision `4d10419cacd6d3009976e8f9fd3abd970fd58018` includes `--embedded` on macOS. Its editor embedding backend publishes a Core Animation context and accepts size/input events through the debugger protocol. This is more bounded than maintaining a custom engine build or changing the JVM process's AppKit lifecycle through LibGodot.

One JVM-owned JFrame contains a native Canvas for the remote game layer and a ComposePanel above it for upgrades/reviews. The JNI bridge attaches CALayerHost through the JDK's JAWT surface API. Godot remains a helper process with no standalone window. No screenshots, desktop capture, browser, or pixel-copy transport are used.

The embedding transport is separate from progression: a loopback ephemeral debugger socket carries only rendering and input messages. It decodes bounded packets and the context announcement; it does not evaluate code or inspect arbitrary game objects. Outgoing events follow the pinned engine's Variant and input codecs. AWT events only reach the game when its surface owns input. Modal transitions and focus loss release held input. Closing the host closes both sockets and stops the owned helper process.

JAWT adds its layers directly to the window, so Swing ordering alone is insufficient. The game container has native z-position -1, below Compose's Skia layer. The remote layer has a top-left origin at `(0, containerHeight)` and follows height changes. Surface sizes use the embedded backend's fixed 2x scale. Mixed-density displays are outside this proof.

Accept and initial-context reads time out after 25 seconds. Script debugger stops, lost surfaces, and child exits display an error in the owning window. Failure detaches the native game layer before showing Swing text. A JVM shutdown hook stops the child on normal process termination; process creation and teardown share a lock to cover close-during-startup.

## Scope and limits

This is a macOS development prototype. CALayerHost is a private macOS API also used by Godot's editor. The `--embedded` path is editor-build infrastructure, not a production export contract. Production packaging and public-API/in-process embedding require a separate decision after this proof. Engine compatibility is pinned and checked. The existing standalone launcher remains available.

The debugger socket is local but has no application authentication. This is acceptable only for a trusted local development machine. A production transport must authenticate its peer and use supported embedding interfaces. Native controller handling, IME/text entry, relative mouse capture, and additional monitor densities are not validated here.

The current glass artwork stays behind the Compose cards. Refraction of live Godot pixels is deferred. Renderer and game rules are unchanged.

## Controller navigation

Godot owns the device connection. `HovelPresentationInput` translates D-pad, bottom/right face buttons, and left-stick motion into the existing semantic input messages. Stick navigation uses 0.6 press / 0.3 release thresholds and retains the first axis through diagonals. Each deliberate tilt produces one step. Priming at presentation entry prevents replaying movement already held in gameplay; device disconnect clears its latch. Presentation consumes controller events while it owns input.

Compose's collector uses the current focused control through `rememberUpdatedState`. Capturing the state holder from the first screen leaves review actions looking up growth focus and prevents activation. Each new screen or offer requests its first action after layout. Confirmation stays an explicit separate button. `HOVEL_TRACE_CONTROLLER=1` logs semantic inputs in the desktop host for physical-device diagnosis.

## Source references

- Godot `platform/macos/display_server_macos_embedded.mm`, `embedded_debugger.mm`, and `macos_quartz_core_spi.h` at the pinned revision.
- Godot `core/debugger/remote_debugger_peer.cpp`, `remote_debugger.cpp`, `core/io/marshalls.cpp`, and `core/input/input_event_codec.cpp` at that revision.
- Java 21 `include/darwin/jawt_md.h`: attaching a CALayer to an AWT component.
- OpenJDK 21 `src/java.desktop/macosx/native/libawt_lwawt/awt/AWTSurfaceLayers.m`: window-level layer attachment and bounds.
