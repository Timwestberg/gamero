# Status

**Feature:** host-bridge (spec `specs/2026-10-14-host-bridge-design.md`, ADR-001)
**Phase:** Implementation, Phase A (design approved by Tim 2026-10-14).

- [x] Discovery of the gamero bridge code
- [x] Spec and ADR-001 (Proposed)
- [x] Strategy, architecture, standards, security, QA and delivery notes
- [x] Tim approves the design
- [x] A1: contract and fixtures (server-platform #128)
- [x] A2: router in commonMain, fixture tests pass on JVM
- [x] A2b: capability bindings beside their interfaces; all capability methods suspend (JVM tests pass)
- [x] A3: JCEF transport on the router, companion as a capability (JVM tests pass; app launch not run)
- [x] A4: smoke page v2 on the Mac (2026-10-14, on the K4 stack: all 8 v2 lines and v1 match `docs/hovel-native-validation.md`; the no-companion and reload variants run again after JCEF moves onto HostBridge)
- [ ] Phase B: Android and iOS transports, web client (B1–B4)
- [ ] Phase C: proof capabilities and parity (C1–C5)
- [ ] Phase D: legacy decision (ADR-003)

## bridge-kit (approved 2026-10-14)

Spec `specs/2026-10-14-bridge-kit-design.md`, ADR-002 (Accepted). G5: product set. **Phase:** Implementation.

- [x] K0 test-all-targets script (Mac, K4 stack: JVM 87, Android 18, iOS 22, 0 failures)
- [x] K1 typed handlers and events (JVM tests pass)
- [x] K2 method time limit (JVM tests pass)
- [x] K3 platform set and product set (JVM tests pass)
- [x] K4 parity test per factory (desktop; Android and iOS reuse assertMatchesParity in B1 and B2)
- [x] B1+K5 Android transport with HostBridge and TransportContractTest (Mac: JVM 102, Android 29, iOS 32, 0 failures; Android debug APK builds; desktop smoke on HostBridge passes with and without the companion, reload check included. Android device smoke waits for the web client, B3)
- [ ] B2 iOS transport on the kit, no kit change (code done, no commonMain change; Mac targets and the simulator build pending)
- [ ] C3+ haptic.play on mobile, legacy haptic path deleted
