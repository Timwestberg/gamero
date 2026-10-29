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

Debug builds load `http://127.0.0.1:8080/hovel` by default. The simulator
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
   SIMCTL_CHILD_GAMERO_WEB_URL=https://localhost:8123/hovel \
     xcrun simctl launch booted org.formulagen.wrapper.WestbergWrapper
   ```
   The URL applies only to that launch. Opening the app from the home screen
   or from Xcode's Run uses the Info.plist default, which is HTTP. To make HTTPS
   stick, set it in the scheme's environment variables or in `Config.xcconfig`
   (`GAMERO_WEB_URL=https:/$()/localhost:8123/hovel`).
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
SIMCTL_CHILD_GAMERO_WEB_URL=http://127.0.0.1:8080/hovel \
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
   write the scheme separator as `/$()/`, e.g. `https:/$()/example.com/hovel`.
   Debug defaults to `http://127.0.0.1:8080/hovel`. Release keeps
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

## Choose the product

The app reads the product once at launch, in the same order as the URL:

1. The `GAMERO_PRODUCT` launch environment variable (`SIMCTL_CHILD_GAMERO_PRODUCT`
   from `simctl`).
2. The `GameroProduct` Info.plist key, fed from `GAMERO_PRODUCT` in
   `iosApp/Configuration/Config.xcconfig`. Debug sets `hovel`. Release leaves it
   empty, and `tools/build-release.sh` passes its own `GAMERO_PRODUCT` (default
   `hovel`) to the archive, as it does for desktop and Android.

The first non-blank value is used, trimmed. If it is `hovel`, the app plays the
Hovel launch and start screen, as desktop and Android do. The web app opens
after the player taps "Open the door", without the legacy native bridge. Any
other value loads the web app directly. `tools/ios-smoke.sh` sets
`GAMERO_PRODUCT=gamero`, because the start screen waits for a tap.

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
GAMERO_WEB_URL=https://localhost:8123/hovel \
GAMERO_SMOKE_ROOT_CERT=/tmp/vite-dev-cert.pem tools/ios-smoke.sh
```

`GAMERO_PAGE_LOADED` comes from the iOS `onPageLoaded` callback on `App()`. It
fires when the page finishes loading, and again when its title arrives, so the
first line may show `title=null`.

## Phone approvals

In the Hovel product the iPhone app is an **approval device**: it answers a
working agent's permission requests, start-of-work confirmations, stage
approvals and resume reviews in a native dialog. The code is
`commonMain/.../phone/PhoneApprovals.kt`, with the iOS pieces in
`iosMain/.../phone/IosPhoneApprovals.kt`: the Keychain item
(`org.formulagen.gamero.approval-device`, readable only while the phone is
unlocked, this device only), CommonCrypto, an ephemeral `NSURLSession`
without redirects, and `UIAlertController`.

1. **Register.** Hovel → Settings → Approval devices → "Use this phone for
   approvals". The page calls `approvalDevice.prepare`; the app asks
   natively, makes a device id and token, and gives the page only the token's
   SHA-256. The page registers it with the account, then
   `approvalDevice.activate` reads the device's own status with the new token
   and keeps it in the Keychain. The device is now **pending**: it reads its
   status and pairing code, and nothing else, for 10 minutes.
2. **Pair on each Mac.** "Pair with a Mac" calls `approvalDevice.pair`. The
   app fetches a six-digit code with its own token and shows it in a native
   alert; the page never sees it. On the Mac, Hovel → Pair a phone… takes the
   code on that Mac's own host route. The phone then answers that Mac's
   approvals, and only that Mac's; pair it on each Mac it should answer for.
   The alert closes once a Mac confirms it (`paired`), when the code runs out
   (`expired`), or when the person closes it.
3. **Answer.** While a Hovel page runs, each open approval on the owner stream
   calls `approvals.present(id)`. The app reads it from
   `/api/hovel/v1/hovel/devices/<owner>/<device>/approvals/<id>` under the
   configured web app's origin and shows it: where it comes from in the
   alert's title (names cleaned to one line), and only the server's lines in
   the message, each checked as `shareableText` checks it. A line it will not
   show, and anything outside a project's folders, is answered on the Mac
   (`mac-only`); an approval of a Mac that has not confirmed this phone says
   to answer or pair there (`unpaired`). The app posts the answer itself, and
   the page learns only how the dialog ended. Nothing stays up longer than 10
   minutes.
4. **Forget.** Signing out, or this phone's own Revoke, calls
   `approvalDevice.forget`: the app revokes the device on its own token, then
   clears the Keychain item. A device the server refuses (revoked elsewhere,
   or a pending one that ran out) is cleared on its next call.

The simulator reaches the local edge over HTTP loopback
(`NSAllowsLocalNetworking`). The iOS test binary runs outside an app bundle,
where the Keychain answers `errSecNotAvailable`, so
`IosPhoneApprovalsTest` checks CommonCrypto and ids there and marks the
Keychain test `@Ignore` with that reason.

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
- **Approvals need the page open.** There are no push notifications yet, so
  the phone shows an approval only while a Hovel page runs.

See [TEST.md](../TEST.md) for manual test cases.
