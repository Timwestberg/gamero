# Gamero iOS: manual test cases

Covers running Hovel in the iOS app. Setup, build, and URL configuration are in
[docs/ios.md](docs/ios.md). Automated coverage: `tools/ios-smoke.sh` (case 1)
and `IosWebUrlTest` (URL rules in cases 3 and 4).

Common preconditions: an Apple Silicon Mac with Xcode 26 and an iOS simulator
runtime. Unless a case says otherwise, Hovel is served at
`http://127.0.0.1:8080/hovel.html` with `pnpm serve:edge`.

## Happy paths

### 1. Smoke test passes

- **Preconditions:** Hovel is served. No simulator is required in advance.
- **Steps:** Run `tools/ios-smoke.sh`.
- **Expected:** The script prints
  `PASS: GAMERO_PAGE_LOADED origin=http://127.0.0.1:8080 title=Hovel` and exits
  0. `build/ios/smoke.png` shows the Hovel welcome screen, and no
  `gamero-smoke-*` simulator is left in `xcrun simctl list devices`.

### 2. Run from Xcode with the default URL

- **Preconditions:** Hovel is served.
- **Steps:** Open `iosApp/iosApp.xcodeproj`, pick the `iosApp` scheme and an
  iPhone simulator, then Run.
- **Expected:** The build succeeds without an Android SDK. The app opens full
  screen on Hovel's "Welcome" sign-in card. The Xcode console shows
  `GAMERO_PAGE_LOADED origin=http://127.0.0.1:8080 title=Hovel`.

### 3. Launch environment overrides Info.plist

- **Preconditions:** A second page is served on loopback, e.g.
  `http://localhost:8080/index.html`.
- **Steps:** Launch with
  `SIMCTL_CHILD_GAMERO_WEB_URL=http://localhost:8080/index.html xcrun simctl launch --console-pty booted org.formulagen.wrapper.WestbergWrapper`.
- **Expected:** The app loads that page, not `hovel.html`. The log shows
  `origin=http://localhost:8080` with that page's title.

### 4. HTTPS URL from the xcconfig

- **Preconditions:** A reachable HTTPS deployment of Hovel.
- **Steps:** Set `GAMERO_WEB_URL=https:/$()/<host>/hovel.html` in
  `iosApp/Configuration/Config.xcconfig`, build, and run without a launch
  override.
- **Expected:** Hovel loads from that origin, and the log shows
  `origin=https://<host> title=Hovel`.

## Known Edge Cases

### Missing or invalid URL

- **Steps:** Launch with `SIMCTL_CHILD_GAMERO_WEB_URL=http://example.com/hovel.html`
  (cleartext to a non-loopback host). Repeat with a URL that has a `#fragment`,
  `user:pass@`, or a `file:` scheme, and with an empty xcconfig value and no
  launch override.
- **Expected:** No web page loads. The app shows "Set GAMERO_WEB_URL to an HTTPS
  app URL (or HTTP localhost for development)." below the Dynamic Island, and
  logs `GAMERO_WEB_URL is missing or invalid`. An invalid launch value does
  **not** fall back to the Info.plist URL.

### Web app not running

- **Steps:** Stop `serve:edge`, then run `tools/ios-smoke.sh`.
- **Expected:** The script fails within about 10 seconds with
  `... is not responding. Start the web app first`, before building anything.
  Launching the app directly shows WKWebView's blank or failed-load page and no
  `GAMERO_PAGE_LOADED` line.

### Connect computer is unavailable on iOS

- **Steps:** Sign in to Hovel on iOS and open the Connect computer flow.
- **Expected:** Hovel stays a remote UI. The Mac companion (JCEF `gameroQuery`
  bridge) does not exist on iOS, and game-platform's `nativeCompanion.ts`
  detects that. Nothing crashes and nothing asks to register this device as a
  computer. This is an intentional limitation.

### Secure-cookie sign-in over HTTP loopback

- **Steps:** With server-platform's identity service running, sign in through
  `http://127.0.0.1:8080/hovel.html`.
- **Expected:** This may fail. If the identity service sets `Secure` cookies,
  WKWebView can drop them over cleartext HTTP, so the session does not persist.
  Fallback: serve over HTTPS (game-platform's Vite dev server on
  `https://127.0.0.1:8123`), trust its root certificate in the simulator with
  `xcrun simctl keychain booted add-root-cert <cert.pem>`, and launch with that
  URL.

### Physical device

- **Steps:** Run on a real iPhone with the default URL.
- **Expected:** The page fails to load, because `127.0.0.1` on the phone is the
  phone itself, not the Mac. Use an HTTPS URL the device can reach. Signing for
  device builds is not configured in this repo (`TEAM_ID` is empty in
  `Config.xcconfig`).

### Firebase configuration file removed

- **Steps:** Delete or rename `iosApp/iosApp/GoogleService-Info.plist`, build,
  and run.
- **Expected:** The app crashes at launch in `FirebaseApp.configure()`. Keep the
  file in place. It is required, not optional.

### Late page title

- **Steps:** Watch the console during any successful load.
- **Expected:** `GAMERO_PAGE_LOADED` may appear first with `title=null` and then
  with `title=Hovel`, because WKWebView reports the title after load completes.
  Only the line with the title counts as a pass.

### Intel Mac or x86_64 simulator

- **Steps:** Build for an x86_64 simulator.
- **Expected:** This is unsupported. There is no `iosX64` Kotlin target, and
  `Config.xcconfig` excludes x86_64 for simulator builds.
