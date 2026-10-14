# Architect decisions

## Structure [DISCOVERED]
- `commonMain`: `App`, `WebViewManager`, legacy `EventBus` + managers, `webview/WebBridge.kt` (legacy bridge).
- `jvmMain`: desktop host, JCEF lifecycle, `companion/NativeBridge.kt` (working bridge), Hovel companion and Brain worker.
- `androidMain` / `iosMain`: `expect fun platformX` actuals. iOS forwards to Swift through `NSNotificationCenter`.
- Legacy bridge never dispatches, and no manager is started (Koin registers companion objects that nothing resolves).

## Principles
- Protocol rules live in pure `commonMain` code. Transports only move strings and report frame facts.
- Frame and origin facts come from the platform, never from the page.

## Active features
- [host-bridge](host-bridge.md) — ADR-001
- [bridge-kit](bridge-kit.md) — ADR-002
