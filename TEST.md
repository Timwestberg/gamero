# Gamero iOS: manual test cases

Covers running Hovel in the iOS app. Setup, build, and URL configuration are in
[docs/ios.md](docs/ios.md). Automated coverage: `tools/ios-smoke.sh` (case 1)
and `IosWebUrlTest` (URL rules in cases 3 and 4, product rule in case 3b).

Common preconditions: an Apple Silicon Mac with Xcode 26 and an iOS simulator
runtime. Unless a case says otherwise, Hovel is served at
`http://127.0.0.1:8080/hovel` with `pnpm serve:edge`. Signed out, `/hovel`
moves to the app's one sign-in at `/auth?next=%2Fhovel`, which keeps the title
**Hovel** and returns to `/hovel` after the PIN.
Debug builds open on the Hovel start screen: tap "Open the door" first, or
launch with `SIMCTL_CHILD_GAMERO_PRODUCT=gamero` to open the web app directly.

## Happy paths

The Mac app's manual path is in [docs/hovel-native-validation.md](docs/hovel-native-validation.md).
Launch it as [docs/desktop.md](docs/desktop.md#run-hovel) shows, with
`GAMERO_PRODUCT=hovel`; without it Connect computer stays disabled.

### 1. Smoke test passes

- **Preconditions:** Hovel is served. No simulator is required in advance.
- **Steps:** Run `tools/ios-smoke.sh`.
- **Expected:** The script prints
  `PASS: GAMERO_PAGE_LOADED origin=http://127.0.0.1:8080 title=Hovel` and exits
  0. `build/ios/smoke.png` shows the **Welcome** sign-in card at
  `/auth?next=%2Fhovel`, because the throwaway simulator is signed out. No
  `gamero-smoke-*` simulator is left in `xcrun simctl list devices`.

### 1b. Smoke test over HTTPS

- **Preconditions:** The HTTPS dev server and extracted certificate from
  [docs/ios.md](docs/ios.md#sign-in-https).
- **Steps:** Run
  `GAMERO_WEB_URL=https://localhost:8123/hovel GAMERO_SMOKE_ROOT_CERT=/tmp/vite-dev-cert.pem tools/ios-smoke.sh`.
- **Expected:** `PASS: GAMERO_PAGE_LOADED origin=https://localhost:8123 title=Hovel`,
  from `/hovel` or the sign-in page it moves to. Without
  `GAMERO_SMOKE_ROOT_CERT`, it fails at the reachability check.

### 2. Run from Xcode with the default URL

- **Preconditions:** Hovel is served.
- **Steps:** Open `iosApp/iosApp.xcodeproj`, pick the `iosApp` scheme and an
  iPhone simulator, then Run.
- **Expected:** The build succeeds without an Android SDK. The app plays the
  Hovel launch and settles on the start screen. After you tap "Open the door",
  it opens `/hovel` full screen, which moves to the **Welcome** sign-in card at
  `/auth?next=%2Fhovel`. The Xcode console shows
  `GAMERO_PAGE_LOADED origin=http://127.0.0.1:8080 title=Hovel` for both pages.

### 3. Launch environment overrides Info.plist

- **Preconditions:** A second page is served on loopback, e.g.
  `http://localhost:8080/index.html`.
- **Steps:** Launch with
  `SIMCTL_CHILD_GAMERO_PRODUCT=gamero SIMCTL_CHILD_GAMERO_WEB_URL=http://localhost:8080/index.html xcrun simctl launch --console-pty booted org.formulagen.wrapper.WestbergWrapper`.
- **Expected:** The app loads that page, not `/hovel`. The log shows
  `origin=http://localhost:8080` with that page's title.

### 3b. Product selects the Hovel start screen

- **Preconditions:** Hovel is served. A simulator is booted with a Debug build installed.
- **Steps:**
  1. Launch with `xcrun simctl launch --console-pty booted org.formulagen.wrapper.WestbergWrapper`.
     Rotate the simulator during the launch animation.
  2. Launch again with `SIMCTL_CHILD_GAMERO_PRODUCT=gamero` before the command.
- **Expected:** Step 1 shows the Hovel launch and start screen, because Debug
  sets `GAMERO_PRODUCT=hovel`. Step 2 loads the web app directly, because the
  launch environment wins over Info.plist. After the rotation, the start screen
  stays. A Release build from Xcode loads the web app directly; an archive from
  `tools/build-release.sh ios` shows the start screen.

### 4. HTTPS URL from the xcconfig

- **Preconditions:** A reachable HTTPS deployment of Hovel.
- **Steps:** Set `GAMERO_WEB_URL=https:/$()/<host>/hovel` in
  `iosApp/Configuration/Config.xcconfig`, build, and run without a launch
  override. Tap "Open the door" on the start screen.
- **Expected:** `/hovel` loads from that origin. Signed out, it moves to
  `/auth?next=%2Fhovel` on the same origin. The log shows
  `origin=https://<host> title=Hovel`.

## Known Edge Cases

### Missing or invalid URL

- **Steps:** Launch with `SIMCTL_CHILD_GAMERO_WEB_URL=http://example.com/hovel`
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

### Sign-in over HTTP loopback fails

- **Steps:** With server-platform's identity service running, sign in through
  `http://127.0.0.1:8080/hovel`.
- **Expected:** Sign-in fails, as observed with identity on `main`. Verify
  returns 200 and the page returns to `/hovel`, but its `/auth/refresh` fails
  with "Missing refresh token", because WKWebView drops identity's `Secure`
  cookies over HTTP. Hovel then sends the app back to the sign-in card at
  `/auth?next=%2Fhovel`, and retrying the same PIN shows "No active PIN
  challenge for this phone" because the PIN was already used. Use the HTTPS path in
  [docs/ios.md](docs/ios.md#sign-in-https) for anything that needs sign-in.

### Sign-in over HTTPS

- **Preconditions:** The setup in [docs/ios.md](docs/ios.md#sign-in-https):
  identity running, `pnpm dev` on `https://localhost:8123`, and the dev
  certificate trusted in this simulator.
- **Steps:** Launch with
  `SIMCTL_CHILD_GAMERO_PRODUCT=gamero SIMCTL_CHILD_GAMERO_WEB_URL=https://localhost:8123/hovel`, enter a phone
  number, then enter the PIN from the identity log once.
- **Expected:** The log shows `GAMERO_PAGE_LOADED origin=https://localhost:8123 title=Hovel`
  for `/hovel` and for the sign-in page at `/auth?next=%2Fhovel`. After the PIN,
  the app returns to `/hovel` and stays signed in. Identity
  logs `/auth/verify` and `/auth/refresh` with no errors. Without the trusted
  certificate, the page does not load and no `GAMERO_PAGE_LOADED` line appears.
- **Status:** Verified on an iOS 26.2 simulator. Identity logged
  `/auth/verify` and `/auth/refresh` with no errors.

### Relaunching drops the HTTPS URL

- **Steps:** After an HTTPS launch through `simctl`, quit the app and open it
  from the home screen.
- **Expected:** It loads the Info.plist default
  (`http://127.0.0.1:8080/hovel`), not the HTTPS URL, because
  `SIMCTL_CHILD_*` applies to one launch. Relaunch through `simctl`, or set the
  URL in the scheme or `Config.xcconfig`.

### Release build URL

- **Steps:** Run `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -showBuildSettings | grep GAMERO_WEB_URL`.
- **Expected:** `https://web.worldwinner.com`, the URL used before Hovel. Only
  Debug defaults to the loopback Hovel URL, which a device cannot reach.

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
  Signed out, the lines repeat for `/auth?next=%2Fhovel`, which also has the
  title Hovel. Only a line with the title counts as a pass.

### Intel Mac or x86_64 simulator

- **Steps:** Build for an x86_64 simulator.
- **Expected:** This is unsupported. There is no `iosX64` Kotlin target, and
  `Config.xcconfig` excludes x86_64 for simulator builds.
