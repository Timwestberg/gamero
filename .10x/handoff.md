# Handoff

**From:** SDE (A3). **To:** whoever runs A4 on Tim's Mac.

A4: run `docs/hovel-native-validation.md` section 1 with Remote Control on the Mac, both buttons, with and without the companion configured. Record the `GAMERO_BRIDGE_V2_SMOKE` lines. Also run `./gradlew :composeApp:jvmTest :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid` to check that the new common code compiles on every target.
Then phase B: the Android and iOS transports and the web client.

## bridge-kit

**From:** brainstorming. **To:** Tim, then Architect.
Read `specs/2026-10-14-bridge-kit-design.md` and ADR-002. Tim chose the product set for G5 (2026-10-14). After Tim approves the whole plan, set ADR-002 to Accepted and write the architect and EM decision files. Mobile test commands need `-Pgamero.mobileTargets=true`, `ANDROID_HOME=~/Library/Android/sdk` and `DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer`.
