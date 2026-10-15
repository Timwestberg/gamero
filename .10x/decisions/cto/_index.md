# CTO decisions

## Tech stack [DISCOVERED]
- Kotlin Multiplatform with Compose Multiplatform (`composeApp`): Android, iOS, desktop JVM.
- Desktop browser: KCEF/JCEF on a verified JetBrains Runtime. Mobile: `compose-webview-multiplatform` 2.0.3.
- Web app and engine bridge: `game-platform`. Shared contracts: `server-platform/event-contracts` (zod).

## Active features
- [host-bridge](host-bridge.md)
