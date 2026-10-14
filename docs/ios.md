# Run Gamero on iOS

Use this guide to build the iOS app and load Hovel from game-platform in the
iOS Simulator. The SwiftUI shell in `iosApp/` hosts the shared Compose `App()`
WebView through `MainViewController()`.

## Prerequisites

- An Apple Silicon Mac. There is no `iosX64` target, so Intel simulators are not
  supported: androidx `lifecycle-runtime-compose` 2.11 does not publish it.
- Xcode 26 with an iOS simulator runtime (Xcode > Settings > Components).
- JDK 21 for Gradle, the same as the desktop build.
- Full Xcode as the active developer directory. If `xcodebuild -version` says
  it needs Xcode, either run
  `sudo xcode-select -s /Applications/Xcode.app/Contents/Developer`, or export
  `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` per shell.
  `tools/ios-smoke.sh` does the latter by itself.
- Network access the first time, so Xcode can resolve the Firebase Swift package
  and Gradle can fetch Kotlin/Native toolchains.

## Serve Hovel locally

The app loads `http://127.0.0.1:8080/hovel.html` by default. The simulator
shares the Mac's loopback, so serve the built web app with game-platform's
production edge. It speaks HTTP and sends the COOP/COEP headers Hovel needs.
From `game-platform`, with `tool-webkit` and `event-contracts` checked out next
to it (see `docs/hovel/HOVEL.README.md` and `infra/README.md` there):

```sh
pnpm bootstrap
pnpm --filter @formulagen/game-bridge-host build
APP=game-bridge-host STATIC_ROOT=apps/game-bridge-host/dist pnpm serve:edge
```

Run `pnpm dev:results-stub` in another shell if realtime is not running. This
HTTP setup is enough to load the page and run the smoke test. It is **not**
enough to sign in; see the next section. The room itself also needs the Godot
export (`godot --headless ... --export-release Web builds/web/hovel.html`,
Godot 4.6.3).

## Sign in (HTTPS)

Sign-in needs HTTPS. Identity marks its session cookies `Secure`, and WKWebView
drops them over `http://127.0.0.1`. The PIN is accepted, but the session is
lost, and retrying shows "No active PIN challenge for this phone".

1. Run server-platform's backends, including identity. On `main`, identity
   already scopes the refresh cookie to the `/api/identity` path the web app
   uses:
   ```sh
   docker compose --profile backends up -d --wait
   ```
2. In `game-platform`, start the HTTPS dev server on `https://localhost:8123`.
   It proxies `/api/identity` to identity and starts a local results stub when
   realtime is not running:
   ```sh
   pnpm dev
   ```
3. Trust its self-signed certificate in the simulator, once per simulator. The
   certificate is generated on the dev server's first run, inside a PEM file
   that also holds its key, so extract only the certificate:
   ```sh
   awk '/BEGIN CERTIFICATE/,/END CERTIFICATE/' \
     <game-platform>/apps/game-bridge-host/node_modules/.vite/basic-ssl/_cert.pem > /tmp/vite-dev-cert.pem
   xcrun simctl keychain booted add-root-cert /tmp/vite-dev-cert.pem
   ```
4. Launch the app with the HTTPS URL:
   ```sh
   SIMCTL_CHILD_GAMERO_WEB_URL=https://localhost:8123/hovel.html \
     xcrun simctl launch booted org.formulagen.wrapper.WestbergWrapper
   ```
   The URL applies only to that launch. Opening the app from the home screen
   or from Xcode's Run uses the Info.plist default, which is HTTP. To make HTTPS
   stick, set it in the scheme's environment variables or in `Config.xcconfig`
   (`GAMERO_WEB_URL=https:/$()/localhost:8123/hovel.html`).
5. Sign in with any E.164 phone number. With `PIN_PROVIDER=console`, no SMS is
   sent; the PIN is logged by identity:
   ```sh
   docker logs --since 5m server-platform-identity 2>&1 | grep "DEV PIN" | tail -1
   ```
   A PIN works once and expires after 5 minutes. After "No active PIN
   challenge", go back and request a new PIN.

## Build and run from Xcode

1. Open `iosApp/iosApp.xcodeproj`.
2. Pick the shared `iosApp` scheme and an iPhone simulator.
3. Run (⌘R).

The "Compile Kotlin Framework" build phase runs
`./gradlew -Pgamero.iosTargets=true :composeApp:embedAndSignAppleFrameworkForXcode`.
The flag turns on only the iOS targets, so no Android SDK is needed. The first
build compiles the Kotlin framework and takes a few minutes.

## Build and run from the command line

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug -derivedDataPath build/ios CODE_SIGNING_ALLOWED=NO build
xcrun simctl boot "iPhone 17"   # any available iPhone simulator
xcrun simctl install booted build/ios/Build/Products/Debug-iphonesimulator/WestbergWrapper.app
SIMCTL_CHILD_GAMERO_WEB_URL=http://127.0.0.1:8080/hovel.html \
  xcrun simctl launch --console-pty booted org.formulagen.wrapper.WestbergWrapper
```

To compile and test only the Kotlin side:

```sh
./gradlew -Pgamero.iosTargets=true :composeApp:compileKotlinIosSimulatorArm64 :composeApp:iosSimulatorArm64Test
```

## Choose the web app URL

The app resolves the URL once at launch, in this order:

1. The `GAMERO_WEB_URL` launch environment variable. From `simctl`, set
   `SIMCTL_CHILD_GAMERO_WEB_URL`. In Xcode, use Edit Scheme > Run > Arguments >
   Environment Variables.
2. The `GameroWebURL` Info.plist key. It is fed from `GAMERO_WEB_URL` in
   `iosApp/Configuration/Config.xcconfig`. xcconfig treats `//` as a comment, so
   write the scheme separator as `/$()/`, e.g. `https:/$()/example.com/hovel.html`.
   Debug defaults to `http://127.0.0.1:8080/hovel.html`. Release keeps
   `https://web.worldwinner.com`, the URL the app loaded before Hovel, because
   loopback only works on a simulator.

The first non-blank value is used. It must pass `isAllowedWebUrl` in
`commonMain/.../WebUrlPolicy.kt`, the same rule desktop uses: HTTPS only, HTTP
only on `localhost`, `127.0.0.1` or `::1`, a host is required, and userinfo and
fragments are rejected. Scheme and host match case-insensitively. An invalid
value does not fall back to
the next source. Instead the app shows a "Set GAMERO_WEB_URL" message and logs
`GAMERO_WEB_URL is missing or invalid`.

`Info.plist` sets `NSAppTransportSecurity` > `NSAllowsLocalNetworking`, which
permits cleartext HTTP to loopback only.

## Smoke test

```sh
tools/ios-smoke.sh
```

The script:

1. Fails fast if `GAMERO_WEB_URL` is not responding.
2. Builds the app into `build/ios`.
3. Creates a throwaway simulator of the newest numbered iPhone on the newest iOS
   runtime, installs the app, and launches it with the URL.
4. Passes when the app logs `GAMERO_PAGE_LOADED origin=<origin> title=Hovel`
   within 90 seconds. The title must match exactly, so `Hovel Error` fails.
5. Saves `build/ios/smoke.png` and deletes the simulator.

It exits non-zero on build failure, crash, or timeout, and leaves the build log
in `build/ios/xcodebuild.log` and the app console in `build/ios/smoke.log`.

Tune it with `GAMERO_WEB_URL`, `GAMERO_EXPECTED_TITLE`, `GAMERO_SMOKE_TIMEOUT`
(seconds), or `GAMERO_SIM_DEVICE` (reuse an existing simulator by name or UDID;
it is left booted).

To check the HTTPS setup that sign-in needs, also pass the dev server's
certificate, extracted as in [Sign in (HTTPS)](#sign-in-https). curl and the
throwaway simulator then both trust it:

```sh
GAMERO_WEB_URL=https://localhost:8123/hovel.html \
GAMERO_SMOKE_ROOT_CERT=/tmp/vite-dev-cert.pem tools/ios-smoke.sh
```

`GAMERO_PAGE_LOADED` comes from the iOS `onPageLoaded` callback on `App()`. It
fires when the page finishes loading, and again when its title arrives, so the
first line may show `title=null`.

## Known limitations

- **Connect computer is unavailable on iOS.** The Mac-only Codex companion
  (`jvmMain/.../hovel/*` and the JCEF `gameroQuery` bridge) has no iOS
  equivalent. game-platform's `nativeCompanion.ts` detects that the bridge is
  missing and runs Hovel as a remote UI. This is intentional.
- **Physical devices cannot reach `127.0.0.1` on the Mac.** Point
  `GAMERO_WEB_URL` at an HTTPS origin the device can reach.
- **Sign-in needs HTTPS.** Over HTTP loopback, WKWebView drops identity's
  `Secure` cookies. See [Sign in (HTTPS)](#sign-in-https).
- `iosApp/iosApp/GoogleService-Info.plist` must stay in place, because
  `FirebaseApp.configure()` runs at launch.

See [TEST.md](../TEST.md) for manual test cases.
