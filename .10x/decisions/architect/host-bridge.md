# host-bridge: architecture

See the spec `specs/2026-10-14-host-bridge-design.md` and ADR-001.

## Components
| Unit | Source set | Does | Depends on |
|---|---|---|---|
| `BridgeEnvelope` + serializers | commonMain | Wire types for v2 (and v1 read-only for the move) | kotlinx.serialization |
| `BridgeRouter` | commonMain | Checks, routes, maps errors, owns context and pending jobs | registry, coroutines |
| `CapabilityRegistry` / `HostCapabilities` | commonMain | Maps method names to typed handlers; lists supported methods | capability interfaces |
| `BridgeTransport` | expect/actual | Moves strings, reports `FrameFacts`, signals navigation | platform WebView API |
| Capabilities (`HostInfo`, `LinkOpener`, `Haptics`, `Lifecycle`, `Companion`) | interfaces in commonMain, actuals per platform | Platform work | platform SDKs |
| `@formulagen/host-bridge` | game-platform package | Detects transport; `capabilities`, `call`, `on` | zod, event-contracts |

## Data flow
web `call` → transport (raw string + FrameFacts) → router checks → handler (typed) → router encodes reply → transport reply → web promise.
Events: capability emits → router wraps `{version, event, data}` → transport posts JSON → web `on` handler.

## Failure modes
- Navigation or reload: router ends the context, cancels pending jobs; client reopens a context.
- Capability throws: `failed`, logged on host, no stack trace to page.
- Unknown or unsupported method: `unknown-method` or `unsupported`, never an exception.
- Bad frame or origin: `forbidden`, nothing else is parsed.
- Busy (single-flight capability such as `companion`): `busy`.
