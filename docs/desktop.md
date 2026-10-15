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
adds both Android and iOS; with it, the Android debug build
(`:composeApp:assembleDebug`) compiles inside the shared `composeApp` module.
There is no `iosX64` target: Compose 1.12 and androidx lifecycle 2.11 do not
publish it.
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

Verified on 9ad5c59 with Gradle 9.3.1/AGP 9.1.1: the desktop compile, all 63 JVM
tests and the Android debug build pass. Mac launch is assigned to the
coordinator's separate validation task. No native desktop success is claimed yet.

On a Pixel 8 Pro (API 34) emulator, launched with the Hovel debug extras:

- The launch animation plays and settles on the start screen.
- A tap on the button area 0.5s into the launch does nothing.
- Recreating the activity 1.2s into the launch shows the settled screen at once;
  the intro does not replay.
- Recreating it again after it settles does not crash.
- "Open the door" loads the configured URL in the web view.

`MainActivity` is portrait-only, so recreation was tested with a dark-mode toggle
rather than rotation.

## Smoke markers

Every shell logs the same two lines (`SmokeMarkers.kt`):

- `GAMERO_PAGE_LOADED origin=<origin> title=<title>` when the page loads, and
  again when its title arrives.
- `GAMERO_BRIDGE_ANSWERED platform=<platform> product=<product> method=<method> outcome=<outcome>`
  for the first host bridge answer each page gets from its trusted main frame.
  `outcome` is `ok` or the error code, such as `unsupported`.

`GAMERO_AUTO_ENTER=1` steps past the Hovel start screen once the launch
settles, so a script needs no click. Desktop reads it from the environment
only, never from a `-D` property, so a packaged release can't bake it in; on
Android and iOS only debug builds honor it. Both markers go to stderr on
desktop:

```sh
GAMERO_PRODUCT=hovel GAMERO_AUTO_ENTER=1 GAMERO_WEB_URL=http://127.0.0.1:8080/hovel.html \
  ./gradlew :composeApp:run 2>&1 | grep -E 'GAMERO_(PAGE_LOADED|BRIDGE_ANSWERED)'
```

`tools/android-smoke.sh` builds the debug app, boots `GAMERO_AVD` (default
`Pixel_8_Pro_API_34`) unless a device is attached, launches with the Hovel
extras and `GAMERO_AUTO_ENTER 1`, and passes on both markers, as
`tools/ios-smoke.sh` does. `GAMERO_WEB_URL` is required and must be an HTTPS
page the emulator trusts, because the app opens HTTPS only, even in debug
builds. A `localhost` port is forwarded to the Mac with `adb reverse`. It writes
`build/android/smoke.png` and `build/android/smoke.log`, and each step's time to
`.build-timings.csv`.
