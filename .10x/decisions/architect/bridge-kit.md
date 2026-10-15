# bridge-kit: architecture

See the spec `specs/2026-10-14-bridge-kit-design.md` and ADR-002.

## Components
| Unit | Source set | Does | Replaces |
|---|---|---|---|
| `HostBridge` | commonMain | Owns scope, router, document counter, event channel, failure log. API: `receive(raw, frame, reply)`, `documentChanged()`, `endContext(contextId)`, `close()`; takes a `post(json)` function | The setup code in each transport |
| Platform adapter | jvmMain / androidMain / iosMain | Reads frame facts from the WebView, calls `HostBridge`, posts JSON | `NativeBridge` duties above the WebView |
| `BridgeMethod.handle<T>` | commonMain | Binds a handler with its capability as a typed parameter; the binder takes its methods from the handlers | Position matching in `CapabilityBinder` |
| `BridgeEvent<D>` | commonMain | Typed event name and payload serializer | String name + `JsonElement` |
| `CapabilityPack` | commonMain | A set of bound capabilities. `HostCapabilities` is the platform pack; a product passes its own packs to `HostBridge` | `companion` parameter in `HostCapabilities` |
| `TransportContractTest` | commonTest | Six scenarios each platform adapter runs with a fake WebView | Prose transport rules |

## Rules
- A new capability touches its own file, the contract, and one line per platform. It does not touch the router, `HostBridge` or an adapter. A platform capability also adds one `HostCapabilities` parameter and one `bound` line; a product capability goes in its product pack instead.
- A new platform is one adapter and one factory, and it passes `TransportContractTest` with no kit change.
- Product capabilities never enter `HostCapabilities`; the parity test checks them.
- Every method has a time limit (30 s default). A timeout answers `failed`.
