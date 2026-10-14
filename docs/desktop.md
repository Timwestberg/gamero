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

For launch, set `GAMERO_BROWSER_JAVA_HOME` to an independently installed and
verified JetBrains Runtime with JCEF (`Contents/Home` on Mac). Keep Gradle's
`JAVA_HOME` on JDK21. The current official JBR/JCEF release inspected for validation
is `jbr-release-25.0.4.1b635.72`; the Mac validator must verify its provenance and
record the actual Chromium user agent. Gamero does not download native browser
binaries, remove quarantine, or bypass macOS security prompts.

The window first shows the destination and Continue. Continue initializes the
installed browser. Setup failures remain visible. Browser cache lives beneath
`~/Library/Application Support/Gamero/web-cache`. The wrapper resolves KCEF/JCEF
API `2024.04.20.4`; compatibility with the selected installed runtime remains a
Mac validation gate. Closing waits off the UI thread for up to 15 seconds, then
explicitly exits with failure if native initialization/shutdown stalled.

The app does not sign in to Codex, register a computer, or run tasks in this step.
Do not use native launch alone as Hovel task/result/reconnect acceptance.

## Build boundaries

Desktop is the default target. `-Pgamero.iosTargets=true` adds only the iOS
targets and needs no Android SDK; see [ios.md](ios.md). `-Pgamero.mobileTargets=true`
adds both Android and iOS; Android build/validation is outside this change.
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

Foundation compilation and seven JVM tests passed. Mac launch is assigned to the
coordinator's separate validation task. No native success is claimed yet.
