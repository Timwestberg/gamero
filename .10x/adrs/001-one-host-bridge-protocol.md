# ADR-001: One host bridge with a shared router and thin platform transports

**Status:** Accepted (Tim, 2026-10-14)
**Date:** 2026-10-14
**Feature:** host-bridge
**Author:** 10x-Team (Architect + Staff Engineer)

## Context

Gamero hosts the same web app on desktop, Android and iOS. The web app needs
native features, and it must get the same behavior on each platform.

The code has two bridges:

- The desktop JCEF bridge (`companion/NativeBridge.kt`) works. It checks the
  frame and the origin, versions the envelope, and binds calls to a document
  context. It is tied to JCEF and carries only Hovel companion methods.
- The mobile bridge (`webview/WebBridge.kt`) does not deliver messages. Nothing
  calls `dispatcher.dispatch`, and no manager starts. It has no origin check, and
  it builds script by string interpolation.

Platform features use about 50 `expect fun` functions. Desktop implements them
by throwing `UnsupportedOperationException`. The web app cannot find out what a
host supports.

## Decision

1. Use one wire protocol, `host-bridge.v2`. Keep the desktop context rules
   (`watchContext`, `contextId`, cancel on navigation). Add a fixed
   `{ok, result | error:{code, message}}` reply.
2. Put all protocol rules in a pure `BridgeRouter` in `commonMain`: frame and
   origin check, size limit, envelope, context, method lookup, decode, error
   mapping.
3. Give each platform one thin transport that only moves strings and reports
   frame facts from the platform API:
   - JCEF: `CefMessageRouter`.
   - Android: `WebViewCompat.addWebMessageListener`.
   - iOS: `WKScriptMessageHandlerWithReply`.
4. Model each native feature as a capability interface. `HostCapabilities` takes
   every capability as a constructor parameter. A platform passes an
   implementation, an `Unsupported` object, or `null` for a product-only
   capability. A missing capability is a compile error.
5. Keep the contract in `server-platform/event-contracts` (zod and JSON
   fixtures). Kotlin tests read the same fixtures. Do not generate code yet.
6. Ship one web client, `@formulagen/host-bridge`, in `game-platform`.

## Alternatives considered

| Alternative | Pros | Cons | Why not |
|---|---|---|---|
| Fix the legacy `deviceEvent` bridge and add a JCEF path | Small first change | Keeps no origin check, string-built script, untyped payloads, and no reply channel. Desktop would still differ. | It makes two protocols permanent. |
| Keep `expect fun` per feature | Familiar KMP pattern | 50+ top-level functions. No way to ask "is this supported?". Desktop throws at call time. | It does not give consistency or discovery. |
| Use the `compose-webview-multiplatform` JS bridge on all platforms | One library | No frame or origin facts, no JCEF router support, and its message API is fire-and-forget. | It cannot meet the security rule. |
| Kotlin as the source of truth, with generated TypeScript | One definition | Needs a generator and build step in two repos. | Fixtures give the drift check now, at lower cost. Revisit if drift occurs. |

## Consequences

### Positive

- A new capability is one contract entry, one interface, and one implementation
  for each platform.
- The router is pure Kotlin, so `commonTest` covers all protocol rules once.
- The web app asks `host.capabilities` and adapts. It does not guess the platform.
- One security rule for all platforms.

### Negative

- Two protocol versions run on desktop until `nativeCompanion.ts` moves to the
  new client.
- Android needs the `androidx.webkit` dependency, and the device WebView must
  support `WEB_MESSAGE_LISTENER`. Old WebViews get no bridge.
- The legacy managers stay frozen and unused until a separate decision.

### Risks

- **Desktop regression in the Hovel companion.** Mitigation: move desktop first,
  keep the JVM tests and the smoke page check green, and accept v1 during the move.
- **Contract drift between zod and Kotlin.** Mitigation: both sides run the same
  fixtures in CI.
- **Mobile WebView quirks** (reply timing, frame facts in redirects).
  Mitigation: the smoke page runs on real Android and iOS builds through Remote
  Control on Tim's Mac before merge.

## Dependencies

- Depends on `WebUrlPolicy`, `IosWebUrl` and `hovelWebUrl` for the trusted origin.
- Constrains every later native feature: it must be a capability.
- A later ADR decides the fate of the legacy managers.
