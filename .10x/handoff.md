# Handoff

**From:** SDE (A2). **To:** SDE (A3).

A3 moves `companion/NativeBridge.kt` onto `BridgeRouter`:
- JCEF transport: `CefMessageRouterHandlerAdapter` keeps origin and frame checks as `FrameFacts`, uses `frame.identifier` as `documentId`, and calls `router.endContext()` when the persistent watch query is cancelled.
- `HovelCompanion` implements `Companion`; its single-flight `mutex.tryLock()` failure throws `BridgeBusyException`.
- Keep v1 for `nativeCompanion.ts` until B3: a v1 request maps to the `companion.*` methods and keeps the old reply shape.
Desktop Hovel companion must keep passing `docs/hovel-native-validation.md` section 1.
