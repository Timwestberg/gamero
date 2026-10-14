# host-bridge: delivery plan

Tim's flow: one PR per task group, two review rounds, parent first through stacked PRs. Mac builds run through Remote Control.

## Phase A: shared core, desktop only (no behavior change)
| # | Task | Repo | Size | Depends on |
|---|---|---|---|---|
| A1 | `host-bridge.v2` zod contract and JSON fixtures (valid/invalid) | server-platform | 0.5 d | – |
| A2 | `BridgeEnvelope`, `BridgeRouter`, `HostCapabilities` in commonMain; fixture tests in commonTest | gamero | 1 d | A1 |
| A3 | JCEF transport on the router; `HovelCompanion` becomes the `companion` capability; accept v1 | gamero | 1 d | A2 |
| A4 | Smoke page v2 on desktop; run on Mac; log round-trip times | gamero | 0.5 d | A3 |

## Phase B: mobile transports and web client
| # | Task | Repo | Size | Depends on |
|---|---|---|---|---|
| B1 | Android transport (`addWebMessageListener`, origin rules, `androidx.webkit`) | gamero | 1 d | A2 |
| B2 | iOS transport (`WKScriptMessageHandlerWithReply`, frame facts) | gamero | 1 d | A2 |
| B3 | `@formulagen/host-bridge` client; move `nativeCompanion.ts` onto it | game-platform | 1 d | A1 |
| B4 | Remove v1 from the desktop router | gamero | 0.25 d | A3, B3 |

## Phase C: proof capabilities on all platforms
| # | Task | Size | Depends on |
|---|---|---|---|
| C1 | `host.info` + `host.capabilities` | 0.5 d | A2 |
| C2 | `link.open` (HTTPS only) | 0.5 d | C1, B1, B2 |
| C3 | `haptic.play` (Android, iOS; desktop `Unsupported`) | 0.5 d | C1, B1, B2 |
| C4 | `lifecycle.pause` / `lifecycle.resume` events | 0.5 d | C1, B1, B2 |
| C5 | Parity test and generated parity table in `docs/host-bridge.md` | 0.5 d | C1–C4 |

## Phase D: legacy decision
| # | Task | Size |
|---|---|---|
| D1 | ADR-002: delete or port the legacy managers and `WebBridge.kt` | 0.25 d |

## Order and parallel work
A1 → A2 → A3 → A4. After A2, B1, B2 and B3 can run in parallel. C starts when the transports land.

## Risks
- Mac access for desktop, iOS and Android device runs. Each PR waits for one Remote Control smoke run.
- Android WebView without `WEB_MESSAGE_LISTENER`: the host reports no bridge. Check on the test device early (B1).
