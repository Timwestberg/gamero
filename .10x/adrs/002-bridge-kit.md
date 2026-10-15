# ADR-002: A shared bridge kit, with platform and product capability sets

**Status:** Accepted (Tim, 2026-10-14)
**Date:** 2026-10-14
**Feature:** bridge-kit
**Author:** 10x-Team (Architect + Staff Engineer)
**Builds on:** [ADR-001](001-one-host-bridge-protocol.md)

## Context

ADR-001 put every protocol rule in a pure `BridgeRouter`. That part works: its
11 tests pass on JVM, Android and iOS. The code around the router is not
reusable yet:

- The JCEF transport makes its own scope, router, event channel, document id
  and failure log. Android and iOS would copy all of it.
- No shared transport test runs on every platform. JCEF has its own tests
  (`JcefV2QueriesTest`), but nothing proves that each platform rejects
  sub-frames, ends a context on navigation, and delivers events the same way.
- Handlers are matched to methods by list position at run time, and events have
  no types.
- `HostCapabilities` holds the Hovel-only companion, so every platform and
  every future product must name it.
- A call has no time limit.

Tim's goals are reusable, extensible and consistent, with composability first.

## Decision

1. Add `HostBridge` to `commonMain`. It owns the scope, the router, a document
   counter, the event channel and the failure log. A platform adapter only
   calls `receive(raw, frame, reply)`, supplies `post(json)`, and calls
   `documentChanged()` on main-frame navigation. A transport whose only
   signal is the end of one watch (JCEF) calls `endContext(contextId)`
   instead, which never closes a newer watch's context.
2. Add `TransportContractTest` to `commonTest`. Every platform adapter runs the
   same six scenarios with a fake WebView.
3. Bind handlers with the capability as a typed parameter, and add a typed
   `BridgeEvent<D>`, so the compiler checks what the run-time list check checks
   today.
4. Split capabilities into two sets. `HostCapabilities` keeps only what every
   platform must decide (`links`, `haptics`, `lifecycle`). A product adds its
   own `CapabilityPack` values when it starts the bridge; Hovel adds the
   companion pack. The parity test checks both sets. (Tim chose this on
   2026-10-14.)
5. Give each method a time limit, 30 seconds by default. A call that runs out
   is cancelled and answers `failed`. The wire contract does not change.
6. Build the Android transport and extract the kit in the same step. iOS then
   uses the kit with no change, or the PR writes down why it needed one.
7. Retire legacy code one capability at a time: the PR that ships a capability
   on a platform deletes the legacy manager and `expect fun` functions for it.

## Alternatives considered

| Alternative | Why not |
|---|---|
| Extract the whole kit from JCEF before Android | It designs from one example. Android would change the kit again. |
| Build Android and iOS by copying JCEF, extract later | Three copies to align; the differences show up late. |
| Keep product capabilities in `HostCapabilities` | The core learns about each product, and every platform names every product's capabilities. |

## Consequences

### Positive

- A new platform is one adapter file and one factory that pass the shared test.
- A new product adds its packs without editing the core or other platforms.
- One test kit proves that every platform behaves the same way.

### Negative

- The compiler no longer forces a decision for product capabilities. The parity
  test does that.
- The JCEF transport changes again when it moves onto `HostBridge`.

### Risks

- **Desktop regression.** A4 must pass before JCEF moves onto `HostBridge`, and
  again after.
- **Android frame facts** need `WEB_MESSAGE_LISTENER`; check it early in B1+K5.
- **iOS events** need a channel other than the one-time reply; use
  `callAsyncJavaScript` with an `arguments` map.

## Dependencies

- Changes the `HostCapabilities` shape that ADR-001 described.
- The legacy-manager decision moves to ADR-003.
