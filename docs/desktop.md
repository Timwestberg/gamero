# Run the Mac desktop foundation

Use this guide to build and open Gamero's existing Compose/JVM WebView host.
Agent registration and execution are a later integration, not part of this
foundation. The legacy mobile bridge is disabled on desktop.

## Prerequisites

- macOS 13 or later, Apple Silicon (the current Compose supported Mac target).
- JDK 21 or 22 and network access for Gradle/Maven dependencies.
- A trusted web application URL; HTTP is allowed only on loopback for development.

From the repository root:

```sh
./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain
GAMERO_WEB_URL=http://localhost:5173/hovel.html ./gradlew :composeApp:run
```

The window first shows the destination and a Continue button. Continue initializes
KCEF, using a bundled compatible runtime where available or downloading its
browser runtime from the pinned official JetBrains release
`jbr-release-25.0.4.1b635.72`. The resolved wrapper uses KCEF/JCEF API
`2024.04.20.4`; runtime compatibility and the actual Chromium version must be
recorded during Mac validation before agent operations are enabled. On Mac, browser files and web cache live beneath
`~/Library/Application Support/Gamero`. Setup failures and restart requirements
are visible. After initialization, the configured web page must be visible.

The app does not sign in to Codex, register a computer, or run tasks in this step.
Do not use native launch alone as Hovel task/result/reconnect acceptance.

## Build boundaries

Desktop is the default target. Existing mobile source remains available using
`-Pgamero.mobileTargets=true`, but mobile build/validation is outside this change.
Mobile-only package versions are retained. Unsupported legacy mobile functions
fail explicitly on desktop and are not exposed through its WebView bridge.

The version catalog updates the desktop Kotlin/Compose/compiler, lifecycle,
coroutines, serialization, WebView, Koin, settings and hot-reload dependencies.
Material3 remains on its latest published stable 1.9.0; newer listed versions are
prereleases. Gradle 8.14.4 avoids a mobile AGP9 migration in this Mac-only task.

Sources: [Kotlin compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[WebView desktop setup](https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md).

## Verification status

Compilation and JVM tests are in progress. Mac launch is assigned to the
coordinator's separate validation task. No native success is claimed yet.
