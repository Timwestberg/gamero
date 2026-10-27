# Catalog launch architecture

## Data and interfaces
```typescript
// Existing catalog shape gains an optional runtime; omitted means web.
interface RuntimeSelection { id: string; runtime?: 'web' | 'native' }
// Existing desktop v1 envelope, inside its native-authorized document context.
// games.launch args: { gameId: string }
// result: { gameId: string, started: true }
```

The web App branches at startRuntime. Native entries call launchNativeGame and never create a web gameplay session or iframe. The page waits for the native gameroGameClosed event before restoring the catalog. Ordinary web games retain their current flow.

NativeBridge keeps its main-frame/origin/context checks. DesktopGameLauncher accepts only the installed hovel-companions ID and chooses paths from native configuration. It mounts EmbeddedCompanionWindow into the current JFrame's layered pane, hiding but retaining the web content. Returning removes that surface, restores the browser, and stops its game and sockets. The standalone entry point uses the same surface.

This checkout's desktop bridge is v1. The newer shared web HostBridge client requires v2 and cannot talk to it. The small nativeGames adapter explicitly matches the existing desktop protocol; migrate it to a games capability pack when that shared native kit reaches this branch. Do not fork the shared v2 client or pretend this host supports it.

The development command starts the existing Vite application with an explicit companion catalog file on loopback HTTP and launches normal Gamero using the installed JBR/JCEF runtime. GAME_PLATFORM_SIBLING_ROOT can point Vite to matching contract/toolkit sources when a worktree's siblings are older. No production catalog is changed.
