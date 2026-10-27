# Catalog launch architecture

## Data and interfaces
```typescript
// Existing catalog shape gains an optional runtime; omitted means web.
interface RuntimeSelection { id: string; runtime?: 'web' | 'native' }
// host-bridge.v2 `games` capability (event-contracts host-bridge/games.ts).
// games.launch args: { gameId: 'hovel-companions' }
// result: { gameId: 'hovel-companions', started: true }
// A launch while a game is open answers `busy`; a host without the game answers `unsupported`.
// event games.closed: { gameId: 'hovel-companions' } when the player returns; desktop only.
```

The web App branches at startRuntime. Native entries call launchNativeGame and never create a web gameplay session or iframe. The page waits for the host-bridge v2 `games.closed` event (`{ gameId }`, desktop only) before restoring the catalog. Ordinary web games retain their current flow.

The shared v2 router checks the frame, origin, context and arguments; an unknown game ID or an extra field is `invalid` before native code runs. DesktopGameLauncher (the desktop `GameCapability`, built by DesktopServices) chooses paths from native configuration and checks that the game and the embed library exist before it changes the window. It mounts EmbeddedCompanionWindow into the current JFrame's layered pane, hiding but retaining the web content. Returning removes that surface, restores the browser, and stops its game and sockets. The standalone entry point uses the same surface.

This host answers `games.launch` over host-bridge v2, so the page uses the shared `@formulagen/host-bridge` client like every other capability; the v1 `games.*` route is gone. The page's document context owns an open game: when that context ends (reload, navigation), DesktopServices closes the game. A cancelled request does not end the context.

The development command starts the existing Vite application with an explicit companion catalog file on loopback HTTP and launches normal Gamero using the installed JBR/JCEF runtime. GAME_PLATFORM_SIBLING_ROOT can point Vite to matching contract/toolkit sources when a worktree's siblings are older. No production catalog is changed.
