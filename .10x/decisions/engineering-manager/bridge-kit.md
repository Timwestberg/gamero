# bridge-kit: delivery

Approved by Tim on 2026-10-14. One stacked PR per row, in this order.

| # | Task | Size | Depends on | Verify |
|---|---|---|---|---|
| K0 | `tools/test-all-targets.sh` | 0.25 d | – | Runs on Tim's Mac; prints a count line per target |
| K1 | Typed handlers and typed events | 0.5 d | A2b | JVM tests; mobile via K0 |
| K2 | Method time limit | 0.25 d | K1 | Router test for a hanging call |
| K3 | Platform set and product set; companion becomes a Hovel pack | 0.5 d | K1 | JVM tests; A4 smoke page |
| K4 | Parity test for each platform factory | 0.25 d | K3 | All targets via K0 |
| B1+K5 | Android transport; extract `HostBridge` and `TransportContractTest`; move JCEF onto `HostBridge` | 1.5 d | K1 | A4 passes before and after; Android smoke run |
| B2 | iOS transport on `HostBridge`, no kit change | 1 d | B1+K5 | iOS smoke run |
| C3+ | `haptic.play` on Android and iOS; delete the legacy haptic path | 0.5 d | B1, B2 | Device check |

Total about 4.75 d. Builds and runs are verified on Tim's Mac through Remote Control; the cloud checks JVM only.
