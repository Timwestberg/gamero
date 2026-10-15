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
- [ ] A4: smoke page v2 on the Mac (Mac snapshot 2026-10-14: tests pass on JVM 84, Android 16, iOS 20; compile passes; button clicks pending)
- [ ] Phase B: Android and iOS transports, web client (B1–B4)
- [ ] Phase C: proof capabilities and parity (C1–C5)
- [ ] Phase D: legacy decision (ADR-003)

## bridge-kit (approved 2026-10-14)

Spec `specs/2026-10-14-bridge-kit-design.md`, ADR-002 (Accepted). G5: product set. **Phase:** Implementation.

- [ ] K0 test-all-targets script
- [ ] K1 typed handlers and events
- [ ] K2 method time limit
- [ ] K3 platform set and product set
- [ ] K4 parity test per factory
- [ ] B1+K5 Android transport with HostBridge and TransportContractTest
- [ ] B2 iOS transport on the kit, no kit change
- [ ] C3+ haptic.play on mobile, legacy haptic path deleted
