# Build production releases

`tools/build-release.sh` builds optimized release artifacts for each platform.
Pass one or more of `web`, `android`, `desktop`, `ios`, or `all`:

```sh
export GAMERO_WEB_URL=https://hovel.example.com/hovel.html   # the live page, once one exists
tools/build-release.sh android desktop ios
tools/build-release.sh all
```

Outputs land in `build/release/<platform>/`. The script stops at the first
failure and names what is missing.

## Build speed and timings

The script checks every requested platform's settings before it builds anything,
so a missing variable fails at once. The web build then runs in the background
(log: `build/release/web/build.log`), Android and desktop share one Gradle run, and
iOS archives last. Set `GAMERO_SEQUENTIAL=1` to run the web build in the
foreground instead. Inside the web build, Godot re-exports a game only when its
sources, preset or Godot version changed, and the type check runs alongside the
Vite build. The iOS archive skips Xcode's index store, which a release does not use.

Every step's duration is appended to `.build-timings.csv` at the repo root
(git-ignored; `BUILD_TIMINGS_FILE` moves it). Each row has the run, step, seconds,
commit and host:

- `build-release:web`, `build-release:android+desktop`, `build-release:ios`,
  `build-release:total`: each stage and the whole run.
- `build-web:*`: the web build's own steps (Godot export per game, `typecheck`,
  `vite-build`), with the same run id.
- `build-release:gradle:<task>`: every Gradle task that took a second or more,
  from Gradle's `--profile` report (also saved as
  `build/release/reports/gradle-profile.html`).
- `build-release:ios:<phase>`: task time per Xcode build rule that took a second or
  more, summed over parallel tasks: the totals of Xcode's build timing summary, which
  `archive` does not print, read from the archive's result bundle
  (`build/release/ios/Gamero.xcresult`). The Kotlin framework build is
  `ios:PhaseScriptExecution`.

`tools/build-timings.sh [runs]` prints one row per step and one column per recent
run, plus the best time seen, to show whether a change made a step faster.

## Settings for every native app

| Variable | Default | Effect |
| --- | --- | --- |
| `GAMERO_WEB_URL` | `http://127.0.0.1:8080/hovel.html` | Page the app opens. Checked against the same rule as `WebUrlPolicy.kt`: HTTPS, or http on loopback; a host, no userinfo, no fragment. Android requires HTTPS. |
| `GAMERO_PRODUCT` | `hovel` | Product the app presents. `hovel` shows the Hovel launch and start screen on desktop, Android and iOS (see [ios.md](ios.md#choose-the-product)), and on iOS adds Hovel's host bridge capabilities. |
| `GAMERO_VERSION` | `1.0.0` | Version name. `MAJOR.MINOR.PATCH` with `MAJOR > 0`, because macOS packaging rejects `0.x`. |
| `GAMERO_BUILD_NUMBER` | `1` | Android `versionCode` and iOS `CURRENT_PROJECT_VERSION`. Raise it for every store upload. |

These reach Gradle as `-Pgamero.product`, `-Pgamero.webUrl`, `-Pgamero.version`
and `-Pgamero.buildNumber` (`composeApp/build.gradle.kts`). Without them,
development builds behave as before.

There is no live Hovel URL yet, so the default is game-platform's local edge,
which serves the web build at `/hovel.html` on port 8080:

```sh
cd ../game-platform && pnpm build:web
APP=game-bridge-host STATIC_ROOT=apps/game-bridge-host/dist pnpm serve:edge
```

Builds made with a loopback URL are for local testing only; the script says so.
Desktop and the iOS simulator reach the edge directly (`NSAllowsLocalNetworking`
covers iOS); a physical iPhone cannot reach the Mac's loopback.

Android release builds open HTTPS pages only (`HovelAndroid.kt`): with any other
URL the app skips Hovel and starts the legacy WorldWinner page, so the script
refuses to build `android` without an HTTPS `GAMERO_WEB_URL`. Until a live URL
exists, an HTTPS placeholder such as `https://127.0.0.1/hovel.html` builds and
shows the Hovel start screen, but the game page itself will not load. Local play
uses debug builds, not release builds: the desktop and iOS simulator debug builds
open the local edge, and an Android debug build takes the `GAMERO_WEB_URL` launch
extra (README.md), which must also be an HTTPS page the device trusts.

## Web

Runs `scripts/build-web.sh` in the game-platform checkout next to this repo (or
`GAME_PLATFORM_DIR`). It exports Hovel with `godot --export-release`, builds the
minified host into `apps/game-bridge-host/dist`, and checks the result. See
game-platform's README, "Production build". `build/release/web/DIST_PATH` records
where the build is.

## Android

`bundleRelease` and `assembleRelease` with `-Pgamero.mobileTargets=true`. R8
shrinks and optimizes code and resources (`composeApp/proguard-rules.pro`). The
URL and product become the `gamero_web_url` and `gamero_product` string
resources. Outputs: the AAB for Play, the APK for sideloading, and R8's
`mapping.txt` for de-obfuscating crash reports.

Signing uses `GAMERO_ANDROID_KEYSTORE`, `GAMERO_ANDROID_KEYSTORE_PASSWORD`,
`GAMERO_ANDROID_KEY_ALIAS` and `GAMERO_ANDROID_KEY_PASSWORD`. Without a keystore
the outputs are unsigned, for signing elsewhere (for example Play App Signing
with an upload key).

## Desktop

`packageReleaseDistributionForCurrentOS`: a DMG on a Mac, MSI on Windows, DEB on
Linux. ProGuard shrinks the JVM code without renaming it
(`composeApp/proguard-desktop.pro`), since KCEF and JCEF use reflection. Its
optimizer is off: it needs a complete class hierarchy, and dev.datlag:jcef and
JOGL extend Thrift, JavaFX and SWT classes the app does not ship.
A packaged app has no shell environment, so the URL and product are baked in as
`-Dgamero.webUrl` and `-Dgamero.product`. `GAMERO_WEB_URL` and `GAMERO_PRODUCT` in
the environment still override them at launch.

`GAMERO_BROWSER_JAVA_HOME` is required. It is the JetBrains Runtime that gets
bundled into the app, so it must be a JBR **SDK** with JCEF (`jbrsdk_jcef`, with
`jlink`, `jpackage` and `jmods`). The runtime-only JBR inside IntelliJ IDEA is
enough for `./gradlew :composeApp:run`, but it cannot package the app or run
`runRelease`: ProGuard reads the JDK classes from `jmods`, which it lacks. The bundle keeps every runtime
module for now; trimming it to `suggestRuntimeModules`' list shrinks the app.

Optional signing and notarization for distribution outside the App Store:

| Variable | Effect |
| --- | --- |
| `GAMERO_MAC_SIGN_IDENTITY` | Developer ID Application identity to sign the app and DMG. |
| `GAMERO_NOTARIZE_APPLE_ID`, `GAMERO_NOTARIZE_PASSWORD`, `GAMERO_NOTARIZE_TEAM_ID` | Also run `notarizeReleaseDmg`. The password is an app-specific password. |

These are macOS-only. The script refuses them on other systems, and refuses
notarization unless the signing identity and all three notarization variables are set.

## iOS

Mac only. Archives the `iosApp` scheme in Release, which also builds the Kotlin
framework in release mode (`embedAndSignAppleFrameworkForXcode` follows the Xcode
configuration). `GAMERO_WEB_URL`, `GAMERO_VERSION` and `GAMERO_BUILD_NUMBER`
override `Config.xcconfig`, including its Release URL.

With `GAMERO_IOS_TEAM_ID`, the archive is signed automatically and exported as an
IPA using `GAMERO_IOS_EXPORT_METHOD` (`app-store-connect` by default, or
`release-testing` for ad hoc, or `enterprise`). Both are checked before anything builds:
the team must be a 10-character Apple team ID. The team goes in as
`DEVELOPMENT_TEAM`, not `TEAM_ID`, because `Config.xcconfig` appends `TEAM_ID` to
the bundle identifier. Without a team the archive is built unsigned, which checks
that Release compiles but cannot be installed. The xcodebuild log is
`build/release/ios/xcodebuild.log`.
