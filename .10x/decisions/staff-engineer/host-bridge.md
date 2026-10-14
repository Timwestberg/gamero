# host-bridge: standards

- **Names.** Methods are `area.verb` (`link.open`, `haptic.play`). Events are `area.event` (`lifecycle.pause`). Error codes are kebab-case strings.
- **One place per concern.** Validation and error mapping only in `BridgeRouter`. Platform code never parses envelopes.
- **No string-built script.** Any value that goes into script goes through `Json.encodeToString` first. Prefer `postMessage` channels.
- **Unsupported is a value.** Capabilities return `Unsupported` objects, never throw `UnsupportedOperationException`.
- **Reuse.** Move `NativeOriginPolicy`, the context and pending-job logic from `companion/NativeBridge.kt` into the router; do not rewrite them.
- **Logging.** One log line per call at debug level: method, result code, duration. No args (they can carry user data).
- **Legacy.** Do not add to `EventBus`, `WebBridge.kt` or `expect fun platformX`. They are frozen.
