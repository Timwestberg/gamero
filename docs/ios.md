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

`CODE_SIGNING_ALLOWED=NO` makes an unsigned app. An unsigned simulator app
cannot use the Keychain: the call answers -34018 (a missing entitlement). That
is enough for `tools/ios-smoke.sh`, which loads a page as the `gamero` product.
It is not enough for Hovel: `listsLocal.lock` fails with -34018, so the page's
session restore stops with "Unable to restore your account session." **To test
Hovel in the simulator, build without that flag**, as Xcode's own Run does:

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug -derivedDataPath build/ios build
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

## A sign-in code's link

A QR code for sign-in is an https link on the web app's own address, under
`/link/`. The phone's camera hands such a link to the app, and the web view
opens its page. The pieces:

- `iosApp/iosApp.entitlements` claims `applinks:$(GAMERO_LINK_HOST)`.
  `GAMERO_LINK_HOST` is in `Config.xcconfig`; a build sets it to the host name
  of its web address. `tools/build-release.sh` does this from
  `GAMERO_WEB_URL`.
- `ContentView.swift` passes an opened link to `openIncomingLink`
  (`MainViewController.kt`).
- `commonMain/.../links/IncomingLink.kt` decides. `incomingLinkTarget` accepts
  an https link on the web address's own origin under `/link/` and nothing
  else, and drops the query and the fragment. `rememberLinkedPage` makes the
  accepted link the web view's page. A link that arrives before the web view
  exists is loaded as soon as it exists.
- The web address must name the app in
  `/.well-known/apple-app-site-association` (game-platform `infra/README.md`,
  "App links").

iOS hands a link over only for a host name on the default HTTPS port. With a
port, with `localhost` or with a number address it reads the address's link
file and still opens the link in Safari. So a local build that takes links
uses `https://<this Mac's name>.local/hovel`:

```sh
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator \
  -configuration Debug DEVELOPMENT_TEAM=<team> GAMERO_PRODUCT=hovel \
  'GAMERO_WEB_URL=https://<name>.local/hovel' GAMERO_LINK_HOST=<name>.local build
```

A Debug build adds `?mode=developer` to the entitlement (`GAMERO_LINK_MODE`),
so iOS reads the link file from the address itself. A real phone also needs
Settings > Developer > Associated Domains Development. `DEVELOPMENT_TEAM` is
needed even on a simulator: the address names the app as `<team>.<bundle id>`.

`xcrun simctl openurl` does not test this. It sends every https link to
Safari. The whole record, with what was tried, is game-platform
`docs/qr-sign-in/qr-sign-in.LOCAL.md`.

Android does the same with a `VIEW` filter in the manifest, whose host is the
`gamero.linkHost` Gradle property (by default the host of `gamero.webUrl`),
and `MainActivity` reads the link at start and in `onNewIntent`.

## Choose the product

The app reads the product once at launch, in the same order as the URL:

1. The `GAMERO_PRODUCT` launch environment variable (`SIMCTL_CHILD_GAMERO_PRODUCT`
   from `simctl`).
2. The `GameroProduct` Info.plist key, fed from `GAMERO_PRODUCT` in
   `iosApp/Configuration/Config.xcconfig`. Debug sets `hovel`. Release leaves it
   empty, and `tools/build-release.sh` passes its own `GAMERO_PRODUCT` (default
   `hovel`) to the archive, as it does for desktop and Android.

The first non-blank value is used, trimmed. If it is `hovel`, the app opens the
web app at once with Hovel's host bridge and without the legacy native bridge;
the page draws its own launch screen. Any other value loads the web app with
the other product's bridge. `tools/ios-smoke.sh` sets `GAMERO_PRODUCT=gamero`.

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
   code, asks "Pair <name> (iPhone, registered <n> min ago)?", and pairs on a
   second click, on that Mac's own host route; every open page then says the
   phone was paired. The phone then answers that Mac's approvals, and only that
   Mac's; pair it on each Mac it should answer for. A phone that loses its last
   Mac stays registered for 10 more minutes to be paired again.
   The alert closes once a Mac confirms it (`paired`), when the code runs out
   (`expired`), or when the person closes it.
   A phone that a Mac app added with a QR code (Settings → Add a phone) skips
   the typing: the page reads the code with `approvalDevice.pairingCode`, which
   shows no alert, and hands it to that Mac app. The Mac still asks its own
   person before it pairs; the code pairs nothing by itself.
3. **Answer.** While a Hovel page runs, each open approval on the owner stream
   calls `approvals.present(id)`. The app reads it from
   `/api/hovel/v1/hovel/devices/<owner>/<device>/approvals/<id>` under the
   configured web app's origin and shows it: its Mac first in the alert's
   title, then the kind and where it comes from (names cleaned to one line, so
   a narrow alert cuts the end rather than the Mac), and only the server's lines in
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

## What the iPhone app gives the page

The Mac stays the engine: it runs every conversation turn on its own provider
sign-ins, and the iPhone is a remote for it. What the phone adds is what only a
phone has. The page asks `host.capabilities` and uses what is listed
([host-bridge.md](host-bridge.md#device-speech-and-notifications) has each
method's exact behaviour).

| Capability | Methods and events | Code (`iosMain/.../bridge/` unless it says otherwise) |
| --- | --- | --- |
| Dictation | `voiceInput.availability`, `.start`, `.stop`, `.status` | `IosVoiceInput.kt` |
| The companion's voice | `voicePlayback.speak`, `.append`, `.stop`, `.status`, `.voices`, `.choose` | `IosSpeechPlayback.kt`, `IosKokoroSpeaker.kt`, `IosAudioSession.kt` |
| Offline Lists | `listsLocal.*` | `iosMain/.../lists/IosListsReplica.kt` |
| Reminders and Focus phase ends | `notifications.status`, `.request`, `.sync` | `IosNotifications.kt`, rules in `commonMain/.../NotificationsCapability.kt` |
| Links that leave the app | `link.open`, and any `target="_blank"` link | `IosHostCapabilities.kt`, `IosMediaGrant.kt` |
| Haptics | `haptic.play` | `IosHostCapabilities.kt` |
| Background and foreground | `lifecycle.pause`, `lifecycle.resume` | `IosHostCapabilities.kt` |
| Phone approvals | `approvals.present`, `approvalDevice.*` | `iosMain/.../phone/IosPhoneApprovals.kt` |

Speech, Lists, notifications and approvals are Hovel's own, so only the Hovel
product registers them. Links, haptics and lifecycle are the platform's, so
every product on iOS has them.

## Dictation and the companion's voice

Dictation uses Apple's speech framework. The voice is Kokoro where the build
carries it, and Apple's otherwise. Both share one audio session (`IosVoice` in
`IosVoiceInput.kt` owns both).

**Dictation** (`voiceInput.*`, `SFSpeechRecognizer`):

- `voiceInput.start` takes an optional `locale`, the page's language. The app
  picks the recognizer with `bestSpeechLocale`: that locale, else the device's
  region of the same language, else the language's home region, else the
  device's own language.
- Words are recognized **on the phone** when iOS has an on-device model for the
  language, and `voiceInput.availability` then answers `offline: true`. For a
  language without one, iOS sends the audio to Apple's speech service and
  `offline` is `false`; with no network that recognizer is `unavailable` with
  reason `offline`.
- The first start shows two iOS prompts, speech recognition and the microphone
  (`NSSpeechRecognitionUsageDescription`, `NSMicrophoneUsageDescription`). The
  30-second start limit does not run while a prompt is on screen.
- A capture ends by itself after 120 seconds, when the app goes to the
  background, on an audio interruption (a call, Siri), and when the page goes
  away. A route change (headphones on or off) restarts the engine on the new
  route; if the new route cannot record, the words heard so far are kept.
- Starting a capture stops the companion's voice, so the person can talk over
  it.

**The companion's voice** (`voicePlayback.*`):

`IosSpeechPlayback` holds the queue's rules and the audio session. An
`IosUtteranceSpeaker` makes the sound, and there are two.

- **Kokoro** (`IosKokoroSpeaker`), the Mac's voice, when the build links
  sherpa-onnx and the app bundle holds the model. Each sentence goes through
  the Mac's own pipeline (`EmberVoice` in `:voice`: the same normalizing,
  lexicons, voice blends and silence trimming) on a background thread, and its
  sound plays through an `AVAudioPlayerNode` while the next sentence is
  generated. The default voice is Sage.
  - `voicePlayback.voices` lists the Kokoro blends and the one in use, and
    `voicePlayback.choose` changes it. The model holds every voice, so a change
    loads nothing: the next utterance uses it. The choice is kept in
    `NSUserDefaults` (`hovel.voice.kokoro`). Choosing stops what was being said
    and says "Hi, I'm …" in the new voice; a capture or a meeting that holds
    the audio session keeps that hello silent. On Apple's voice there is
    nothing to choose, and the list is empty.
  - When nothing is being said, a first sentence longer than 70 characters is
    said as two utterances: its opening clause, then the rest (`openingClause`
    in `:voice`). The first sound then waits on half the sentence.
  - The model (about 330 MB) loads at the first sentence, or when a capture
    starts, so it is ready for the reply. It unloads when iOS warns that memory
    is short and nothing is being said.
  - It generates on 4 threads, as on the Mac. `GAMERO_KOKORO_THREADS` overrides
    that at launch.
  - Each sentence logs its speed: `Kokoro: 3.3 s of speech in 1.6 s, 2.1x real
    time, on 4 threads`. That line is from an iPhone 14 Pro Max, where two
    threads make 1.7 times real time. The first sentence of a reply therefore
    starts about a second and a half after it arrives, and each later one is
    ready before the one before it ends.
  - If the model cannot load, Apple's voice speaks from then on.
- **Apple's voice** (`AppleSpeaker`, `AVSpeechSynthesizer`) otherwise. The app
  picks the best voice **installed** for the device language: premium, then
  enhanced, of the device's own locale first, then of another region of the
  language. With only default voices it uses the system's default, never a
  novelty voice. To get a better voice, download one in Settings >
  Accessibility > Spoken Content > Voices; the app picks it up on the next
  sentence.

To give a build Kokoro:

```sh
scripts/fetch-voice.sh                               # the model, as for the Mac
scripts/ios-voice.sh /path/to/sherpa-onnx/build-ios  # link the engine, bundle the model
```

- sherpa-onnx 2.0.0 ships no iOS build. Build it from the `v2.0.0` tag with
  upstream's `build-ios.sh` (needs `cmake`); the script takes that `build-ios`
  directory.
- The model directory also needs `voices-blends.bin` and `lexicon-chat.txt`,
  which the Mac writes the first time Ember speaks.
- The script writes `iosApp/Configuration/Voice.local.xcconfig`, which is not
  committed. `scripts/ios-voice.sh --off` removes it. Without it the app links
  no sherpa, carries no model, and speaks with Apple's voice.
- The app grows by about 360 MB.

To hear a build's voice without a conversation, launch it with
`GAMERO_VOICE_SAY="A sentence. And another."`. The app speaks the text once,
sentence by sentence.

Both speakers follow the same rules:

- `speak` starts over, `append` queues behind what is being said, so a reply is
  spoken sentence by sentence while it streams. `status` is `speaking` from the
  call on, not from the first sound, and `done` only when the whole queue has
  been said. State comes from the speaker's reports of each sentence's start
  and end.
- While it speaks, other apps' audio is ducked (`playback` category,
  `spokenAudio` mode). About 1.5 seconds after the last sentence the app
  deactivates the session and tells other apps, so music comes back. The next
  sentence of a streaming reply arrives sooner than that and keeps the session.
- It stops, as `cancelled`, on `stop`, in the background, on an audio
  interruption, when headphones come off, and when the page goes away. A
  speaker that stops reporting is dropped as `error` after a budget
  (`speechBudgetMs`), so `status` never stays `speaking` for ever.
- **During a meeting** in the page (the web view holds the camera or the
  microphone), the app leaves the audio session alone: dictation answers
  `busy` and the voice answers `error`. The app also never deactivates a session
  it did not activate itself.

## Offline Lists

`listsLocal.*` keeps one account's Lists snapshot on the phone, so Lists open
and take edits with no network. `IosListsReplica` proves the account with a
one-use server proof (`listsLocal.authorize`), keeps the owner id in a
this-device-only Keychain item (`org.formulagen.hovel.offline-lists`), and
stores the snapshot with SQLite in `Application Support/HovelOfflineLists`,
with complete file protection and no backup. The page moves the snapshot in
bounded chunks (`read`, then `begin`, `append`, `commit`), and a commit is
atomic. `listsLocal.lock` (sign-out) forgets the owner and closes the store.

## Notifications

The iPhone schedules list reminders and Focus phase ends itself, with
`UNUserNotificationCenter`. Nothing comes from a server, and there is no push,
so the app needs no entitlement and no `Info.plist` key for it.

- The page states what should be pending: `notifications.sync({scope, items})`
  makes one scope's pending set exactly `items`. The scopes are
  `lists.reminders` and `lists.focus`; each holds at most 32, and iOS keeps at
  most 64 pending requests for an app.
- A request's identifier is `<scope>/<id>` (`NotificationPlan.identifier`), and
  its thread identifier is the scope, so iOS groups them.
- `notifications.request` shows the iOS prompt once (banners and sound, no
  badge). `sync` and `status` never prompt. Without permission a sync schedules
  nothing and answers `scheduled: 0`.
- **In the foreground iOS shows nothing.** The page shows its own notice for a
  reminder that comes due while Hovel is on screen. `QuietInForeground` in
  `IosNotifications.kt` says so outright; it is also what iOS does for an app
  with no delegate.
- **Signing out clears them.** `listsLocal.lock`, and another account
  unlocking Lists on the phone, remove every scheduled and shown notification
  (`forgettingOnLock`), so a signed-out phone does not show the last account's
  reminders.
- A tap on a notification opens the app. It carries no link.

## Links, haptics and lifecycle

- `link.open({url})` hands an HTTPS URL to iOS, which opens Safari, or the
  site's own app for a universal link ("Open ChatGPT").
- A `target="_blank"` link or `window.open` used to do nothing, because the web
  view has one window. `MediaUIDelegate` now answers it with `newWindowTarget`:
  Hovel's own page loads in the web view, another HTTPS site opens outside the
  app, and anything else (`http:`, `tel:`, a link from another origin's frame)
  still does nothing.
- `haptic.play({effect})` plays the named effect with UIKit's feedback
  generators. The simulator has no Taptic Engine and does nothing.
- `lifecycle.pause` is sent when the app goes to the background and
  `lifecycle.resume` when it comes back. A system prompt or the app switcher
  over the app is not a pause.

## Edge to edge

In the Hovel product the page is the whole screen: it draws under the status
bar and the Dynamic Island, and over the home indicator. The page keeps its own
content clear of them with `env(safe-area-inset-top)` and
`env(safe-area-inset-bottom)`. `MainViewController.kt` asks for this with
`mediaWebViewFactory(edgeToEdge = hovel)`, so another product's page is laid
out as before.

Two things in `iosMain/.../bridge/IosMediaGrant.kt` make it so:

- **`keepViewportFit()`** adds `KEEP_VIEWPORT_FIT_SCRIPT` to the main frame of
  every page, at document start. The web view library adds a second
  `<meta name="viewport">` after the page's, and the last one wins, so the
  page's `viewport-fit=cover` was lost and `env(safe-area-inset-*)` read 0. The
  script puts `viewport-fit=cover` back on the tag that wins, whenever the page
  asked for it, and leaves the rest of that tag as it is. It watches `<head>`,
  so a tag added later is handled too.
- **`layOutToTheEdges()`** sets the scroll view's
  `contentInsetAdjustmentBehavior` to `never`. Left to itself the scroll view
  insets the page by the safe area and WebKit sizes the page's layout to what
  is left, which left a blank bar above and below the page. WebKit lifts that
  inset by itself only when it sees `viewport-fit=cover` at the first layout,
  and here the tag arrives after it.

Measured in the iPhone 17 simulator (874 pt tall):

| What the page reads | Value |
| --- | --- |
| `innerHeight`, the document's client height, `100vh`, `100dvh`, `100svh` | 874 px, the whole screen |
| `env(safe-area-inset-top)` | 62 px |
| `env(safe-area-inset-bottom)` | 34 px |
| A `position: sticky; bottom: 0` dock | Sits on the screen's bottom edge |

The rule for a page: it is the whole screen, so pad the top and the bottom
with `env(safe-area-inset-top)` and `env(safe-area-inset-bottom)`. The page
must declare `viewport-fit=cover` itself; game-platform's `index.html` does.

Where no page has painted (before the first paint, and past a page's end),
the web view shows the web app's boot colour, `#071013`, not
white: `backgroundColor` and `underPageBackgroundColor` are set to `PAGE_INK`
through the library's settings in `commonMain/.../managers/WebViewManager.kt`.

To check it, open Hovel signed out, then a conversation: no blank bar shows
above or below the page, the title starts below the clock, and the composer
sits above the home indicator. The web side's rules are in game-platform's
`docs/launcher/companion-projects.SPEC.md` ("Edge to edge on the iPhone app").

## The keyboard

In the Hovel product the page ends where the keyboard begins. `HovelIosApp`
(`iosMain/.../hovel/HovelIos.kt`) puts `Modifier.imePadding()` on the web view,
so the web view itself gets shorter while the keyboard is up, the keyboard's own
∧ ∨ ✓ bar included.

Left under the keyboard, the page stays the height of the screen and iOS only
pans it far enough to show the caret. A header that sticks to the top then
slides off the screen, and a bar at the bottom is half covered by the keyboard's
bar. Resized, the page's top and bottom are the visible top and bottom.

What the page sees while the keyboard is up:

- `innerHeight`, `100dvh` and `visualViewport.height` all shrink together, and
  `window` fires `resize`. `visualViewport.offsetTop` stays 0.
- `env(safe-area-inset-bottom)` is 0, since the web view no longer reaches the
  home indicator.
- A check that compares `innerHeight` with `visualViewport.height` reads
  "keyboard closed". Only a plain mobile browser still shows the difference.

## The app's name

The name under the icon and in the system's permission prompts is
`CFBundleDisplayName`, from `APP_DISPLAY_NAME` in
`iosApp/Configuration/Config.xcconfig`: "Hovel" in Debug, the target's name in
Release. `tools/ios-info.py` sets "Hovel" for a Hovel release archive.

## Camera and microphone

Hovel meetings use the camera and the microphone in the web page. The app
gives them only to the trusted Hovel page, with the rule in
[host-bridge.md](host-bridge.md#camera-and-microphone).

- `MediaUIDelegate` (`iosMain/.../bridge/IosMediaGrant.kt`) is the WKWebView's
  UI delegate. It answers `requestMediaCapturePermissionFor` with grant only
  when `MediaGrant` grants for the requesting frame. It denies all other
  requests.
- After a grant, iOS shows its own prompt once, with the usage strings in
  `iosApp/iosApp/Info.plist` (`NSCameraUsageDescription` and
  `NSMicrophoneUsageDescription`).
- `mediaWebViewFactory` makes the WKWebView, through the `webViewFactory` that
  `App` takes. It sets `allowsInlineMediaPlayback`. Without it, the camera
  video shows black ([WebKit bug 220184](https://bugs.webkit.org/show_bug.cgi?id=220184)).
- WKWebView keeps its UI delegate weakly. `MainViewController` keeps the
  delegate for the life of the screen.
- Another product (`GAMERO_PRODUCT` is not `hovel`) gets a delegate with no
  trusted page, so every request is denied. `tools/ios-smoke.sh` runs that
  product.

## Known limitations

- **Connect computer is unavailable on iOS.** The Mac-only Codex companion
  (`jvmMain/.../hovel/*` and the JCEF `gameroQuery` bridge) has no iOS
  equivalent. game-platform's `nativeCompanion.ts` detects that the bridge is
  missing and runs Hovel as a remote UI. This is intentional.
- **Physical devices cannot reach `127.0.0.1` on the Mac.** Point
  `GAMERO_WEB_URL` at an HTTPS origin the device can reach.
- **Sign-in needs HTTPS.** Over HTTP loopback, WKWebView drops identity's
  `Secure` cookies. See [Sign in (HTTPS)](#sign-in-https).
- **An unsigned simulator build cannot run Hovel signed in.** Built with
  `CODE_SIGNING_ALLOWED=NO` it has no Keychain (-34018), and the page shows
  "Unable to restore your account session." Build without the flag; see
  [Build and run from the command line](#build-and-run-from-the-command-line).
- `iosApp/iosApp/GoogleService-Info.plist` must stay in place, because
  `FirebaseApp.configure()` runs at launch.
- **Local LiveKit does not reach the simulator's HTTPS page.** WKWebView blocks
  `ws://127.0.0.1:7880` from an `https` page as mixed content (iOS 26.2
  simulator, 2026-11-05). The same socket connects from an `http://127.0.0.1`
  page, but sign-in needs HTTPS. So meetings in the simulator use the LiveKit
  Cloud dev project.
- **Approvals need the page open.** There are no push notifications, so the
  phone shows an approval only while a Hovel page runs. The notifications the
  app does have are local ones, for reminders and Focus phase ends.
- **Reminders are scheduled only while the app runs.** A reminder made on
  another device reaches this phone's schedule the next time Hovel opens here
  and syncs.
- **The companion's voice is Apple's, not Kokoro.** It sounds best with a
  premium or enhanced voice downloaded in Settings.
- **Speech and notifications cannot be unit-tested end to end.** Their rules
  are tested (`LocalNotificationsTest`, `LocalSpeechQueueTest`,
  `NewWindowTargetTest`, `IosNotificationsTest`, `IosSpeechVoiceTest`). The
  audio, the prompts and the banners need the manual cases in
  [TEST.md](../TEST.md), some of them on a real phone.

See [TEST.md](../TEST.md) for manual test cases.
