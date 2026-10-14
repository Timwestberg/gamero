# Handoff

**From:** SDE (A3). **To:** whoever runs A4 on Tim's Mac.

A4: run `docs/hovel-native-validation.md` section 1 with Remote Control on the Mac, both buttons, with and without the companion configured. Record the `GAMERO_BRIDGE_V2_SMOKE` lines. Also run `./gradlew :composeApp:jvmTest :composeApp:compileKotlinIosSimulatorArm64 :composeApp:compileDebugKotlinAndroid` to check that the new common code compiles on every target.
Then phase B: the Android and iOS transports and the web client.
