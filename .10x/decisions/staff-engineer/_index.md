# Staff engineer decisions

## Conventions [DISCOVERED]
- Security-relevant classes carry a one-line KDoc that states the invariant (see `NativeOriginPolicy`).
- Tests per source set: `commonTest`, `jvmTest`, `iosTest`. Desktop has the most coverage.
- Smoke pages live in `tools/`. Release scripts log step timings to `.build-timings.csv`.
- Contracts with fixtures live in `server-platform/event-contracts` (pattern: `game-result.v1`).

## Active features
- [host-bridge](host-bridge.md)
