# Handoff

**From:** SDE (A3). **To:** whoever runs A4 on Tim's Mac.

A4: run `docs/hovel-native-validation.md` section 1 with Remote Control on the Mac, both buttons, with and without the companion configured. Record the `GAMERO_BRIDGE_V2_SMOKE` lines. Also run `./gradlew :composeApp:jvmTest :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid` to check that the new common code compiles on every target.
Then phase B: the Android and iOS transports and the web client.

## bridge-kit

**From:** Architect. **To:** SDE.
Tim approved the plan on 2026-10-14; ADR-002 is Accepted. Work the tasks in `decisions/engineering-manager/bridge-kit.md`, one stacked PR each. Mobile test commands need `-Pgamero.mobileTargets=true`, `ANDROID_HOME=~/Library/Android/sdk` and `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.

## meetings

**From:** SDE (Meetings 3c). **To:** whoever runs the meetings test on real devices.
Meetings v1 is FOR-232. Gamero only lets its webviews give the camera and microphone to the trusted Hovel page; the call itself runs in the web page. 3a (FOR-237) adds `MediaGrant` in `commonMain` and the Android adapter. 3b (iOS, FOR-238) and 3c (Mac, FOR-239) ask the same `MediaGrant`. The rule and the platform table are in `docs/host-bridge.md`, "Camera and microphone". Never use `--enable-media-stream` on the Mac. 3a, 3b and 3c (#103, #104, #105) were merged on 2026-11-06. Real phones and a real meeting are not tested yet. They need a LiveKit Cloud project. 3b adds `MediaUIDelegate` and `mediaWebViewFactory` in `iosMain/.../bridge/IosMediaGrant.kt`, and the plist usage strings. The Hovel grant path on iOS (a tap through the start screen, then iOS's own prompt) still needs a run in the simulator or on a phone. 3c adds `JcefMediaGrant` (`jvmMain/.../bridge/JcefMediaGrant.kt`), added to each WebView's client in `persistentDesktopBrowser`, and the bundle usage strings; `docs/desktop.md` says where each launch gets them. A signed release still needs the camera and audio-input entitlements.
