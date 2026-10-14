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

Desktop is the default target. Mobile targets are enabled with
`-Pgamero.mobileTargets=true`; with it, the Android debug build
(`:composeApp:assembleDebug`) compiles inside the shared `composeApp` module.
iOS mobile builds are not validated, and the `iosX64` target is removed because
Compose 1.12 no longer publishes it.
Mobile-only package versions are retained. Unsupported legacy mobile functions
fail explicitly on desktop and are not exposed through its WebView bridge.

The version catalog updates the desktop Kotlin/Compose/compiler, lifecycle,
coroutines, serialization, WebView, Koin, settings and hot-reload dependencies.
Material3 remains on its latest published stable 1.9.0; newer listed versions are
prereleases. Compose 1.12.1's Android artifacts require AGP 9.1+ and compileSdk 37,
so the build uses Gradle 9.3.1 (the minimum for AGP 9.1.1), AGP 9.1.1 and
compileSdk 37.

AGP 9 rejects `com.android.application` inside a Kotlin Multiplatform module.
`gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false`
to opt out temporarily. Remove both once the Android app moves to its own module
that depends on `composeApp`.

Sources: [Kotlin compatibility](https://kotlinlang.org/docs/multiplatform/multiplatform-compatibility-guide.html),
[Compose compatibility](https://kotlinlang.org/docs/multiplatform/compose-compatibility-and-versioning.html),
[WebView desktop setup](https://github.com/KevinnZou/compose-webview-multiplatform/blob/main/README.desktop.md).

## Verification status

Foundation compilation and seven JVM tests passed. Mac launch is assigned to the
coordinator's separate validation task. No native success is claimed yet.

On Gradle 9.3.1/AGP 9.1.1 the Android debug build passes and 63 JVM tests pass.
On a Pixel 8 Pro (API 34) emulator the Hovel launch animation plays and settles
on the start screen, and the intro does not replay when the activity is recreated.
`MainActivity` is portrait-only, so recreation was tested with a dark-mode toggle
rather than rotation.
