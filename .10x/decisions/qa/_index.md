# QA decisions

## Coverage [DISCOVERED]
- Desktop: JVM tests for browser lifecycle, editing, readiness, companion, Hovel adapter.
- Common: URL policy and user agent. iOS: URL policy. Android: none for the bridge.
- No test covers the legacy bridge, which is how its missing dispatch went unnoticed.

## Active features
- [host-bridge](host-bridge.md)
