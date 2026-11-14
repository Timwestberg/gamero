# Run the Mac desktop foundation

Use this guide to build and open Gamero's Compose/JVM WebView host. Registering
the Mac and running conversations are in [hovel-companion.md](hovel-companion.md).
The legacy mobile bridge is disabled on desktop.

## Prerequisites

- macOS 13 or later, Apple Silicon (the current Compose supported Mac target).
- JDK 21 or 22 and network access for Gradle/Maven dependencies.
- A trusted web application URL; HTTP is allowed only on loopback for development.

From the repository root:

```sh
./gradlew :composeApp:jvmTest :composeApp:jvmJar --console=plain
```

### Run Hovel

Set `GAMERO_PRODUCT=hovel`. Without it the app runs as the games
product: the companion pack and the conversation runner are off, and Connect
computer stays disabled. The full Hovel launch:

```sh
# game-platform: serve the app over loopback HTTP on 8133 (GAMERO_CATALOG_PORT changes it).
# JCEF rejects the dev server's basic-ssl certificate, so use this launcher, not the HTTPS dev server.
(cd <game-platform>/apps/game-bridge-host && node --import tsx tools/companionLauncher.ts)

# gamero, from the repository root (Gradle on JDK 21):
GAMERO_PRODUCT=hovel \
GAMERO_WEB_URL=http://127.0.0.1:8133/hovel \
GAMERO_BROWSER_JAVA_HOME="<JBR with JCEF>/Contents/Home" \
HOVEL_GAMES_ROOT=<game-platform>/games \
HOVEL_EMBED_LIBRARY="$PWD/build/embedded-godot/libhovel_embed.dylib" \
JAVA_HOME=$(/usr/libexec/java_home -v 21) ./gradlew :composeApp:run
```

`HOVEL_GAMES_ROOT` and `HOVEL_EMBED_LIBRARY` turn on the embedded games
(Hovel Companions, Ember Tower, Ember: Overclock); build the library with
`scripts/build-embedded-macos.sh`. Without them `games.launch` answers
`unsupported`. A launch with a companion writes its `EmberProfile` to
`~/Library/Application Support/Gamero/companions/profiles/<companion id>.json`
and passes the path in `HOVEL_EMBER_PROFILE`. Every setting is in
[Desktop companion settings](companion-presentation/README.md#desktop-companion-settings).

`GAMERO_CODEX_PATH` is optional; without Codex the Mac runs conversations on the
runtimes it has and takes no memory jobs.

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

The app never signs in to Codex or any other runtime. Do not use native launch
alone as Hovel conversation or reconnect acceptance; follow
[hovel-native-validation.md](hovel-native-validation.md).

## Camera and microphone

Hovel meetings use the camera and the microphone in the web page. The Mac app
gives them only to the trusted Hovel page, with the rule in
[host-bridge.md](host-bridge.md#camera-and-microphone).

- `JcefMediaGrant` (`jvmMain/.../bridge/JcefMediaGrant.kt`) is the JCEF
  permission handler. `persistentDesktopBrowser` adds it to the WebView's own
  client before the first page loads.
- CEF names the requesting frame and that frame's own origin. A grant continues
  with the requested bits. Any other answer cancels the request.
- Do not pass `--enable-media-stream`. That switch grants every permission to
  every page, frames of other origins included.
- Another product (`GAMERO_PRODUCT` is not `hovel`) gets a handler with no
  trusted page, so every request is cancelled.

**Check 3 (2026-11-05).** JBR `21.0.9+10-b1163.94` with JCEF calls
`CefPermissionHandler.onRequestMediaAccessPermission` in Java. A probe page on
`http://127.0.0.1` asked for both devices, and a frame on `http://localhost`
asked for the microphone. CEF reported each frame with its own origin, its
`isMain` value and its bits (3 and 1). The compile-time API (`dev.datlag:jcef`
2024.04.20.4) has the same handler. Check the release runtime
(`jbr-release-25.0.4.1b635.72`) the same way before a release.

### Usage strings

macOS asks the person before the app first uses the camera or the
microphone, and shows the bundle's usage string. Without the string, macOS
does not allow the capture.

| Launch | Where the strings come from |
| --- | --- |
| Release app and DMG (`tools/build-release.sh desktop`, `:composeApp:createDistributable`) | `nativeDistributions.macOS.infoPlist.extraKeysRawXml` in `composeApp/build.gradle.kts`: `NSMicrophoneUsageDescription` and `NSCameraUsageDescription`. |
| Gamero Adventures prototype (`scripts/package-catalog-macos.sh`) | The script adds each string with PlistBuddy when it is missing, also after a jar refresh. |
| `./gradlew :composeApp:run` | There is no app bundle. macOS asks for the app that started Gradle, usually Terminal or the IDE, with that app's strings. |

**Signed builds.** A signed release uses the hardened runtime. Then it also
needs the `com.apple.security.device.audio-input` and
`com.apple.security.device.camera` entitlements. Compose's default
entitlements have neither, and this build does not add them yet. Mac voice has
the same gap.

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

On a Pixel 8 Pro (API 34) emulator, launched with the Hovel debug extras, the
start screen was verified before it was removed: the launch animation, a
recreated activity and "Open the door". Since then no host waits for a tap.
The desktop host shows the launch animation and "Lighting the lamps…" while it
prepares its browser, then opens the page. The Android and iPhone hosts have no
browser to prepare and open the page at once. That Android flow compiles; it
has not been run on an emulator or a device.

`MainActivity` is portrait-only, so recreation was tested with a dark-mode toggle
rather than rotation.

## Smoke markers

Every shell logs the same two lines (`SmokeMarkers.kt`):

- `GAMERO_PAGE_LOADED origin=<origin> title=<title>` when the page loads, and
  again when its title arrives.
- `GAMERO_BRIDGE_ANSWERED platform=<platform> product=<product> method=<method> outcome=<outcome>`
  for the first host bridge answer each page gets from its trusted main frame.
  `outcome` is `ok` or the error code, such as `unsupported`.

Hovel opens the page with no start screen to tap, so a script needs no click.
Both markers go to stderr on desktop:

```sh
GAMERO_PRODUCT=hovel GAMERO_WEB_URL=http://127.0.0.1:8080/hovel.html \
  ./gradlew :composeApp:run 2>&1 | grep -E 'GAMERO_(PAGE_LOADED|BRIDGE_ANSWERED)'
```

`tools/android-smoke.sh` builds the debug app, boots `GAMERO_AVD` (default
`Pixel_8_Pro_API_34`) unless a device is attached, launches with the Hovel
extras, and passes on both markers, as
`tools/ios-smoke.sh` does. `GAMERO_WEB_URL` defaults to the local edge,
`http://127.0.0.1:8080/hovel.html`: Android debug builds open http on loopback
(release builds stay HTTPS-only), and the script forwards the port to the Mac
with `adb reverse`. It writes
`build/android/smoke.png` and `build/android/smoke.log`, and each step's time to
`.build-timings.csv`.
