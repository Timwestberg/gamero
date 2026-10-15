# Gamero

Production releases for web, Android, desktop and iOS: `tools/build-release.sh`, see [docs/release.md](docs/release.md).

Tests on every target: `tools/test-all-targets.sh` runs the JVM, Android unit and iOS simulator tests and prints one count line per target (`jvm`, `android` or `ios` to run fewer).

Mac desktop development: [build and launch guide](docs/desktop.md). Desktop is the default build target; mobile targets are opt-in.

Hovel Mac companion and native bridge trust rules: [docs/hovel-companion.md](docs/hovel-companion.md).

Host bridge (`host-bridge.v2`) on desktop, Android and iOS, and the recipe to add a capability: [docs/host-bridge.md](docs/host-bridge.md).

iOS (simulator): [build and run guide](docs/ios.md). Smoke test: `tools/ios-smoke.sh` builds the app, loads Hovel on a throwaway simulator, and fails unless the page loads and the host bridge answers. `tools/android-smoke.sh` does the same on an emulator (docs/desktop.md). Android remains outside the current verification scope.

Android: build with `./gradlew -Pgamero.mobileTargets=true :composeApp:assembleDebug`
(Gradle 9.3.1, AGP 9.1.1, compileSdk 37; Compose 1.12 requires them). Launch the Hovel
start screen with `adb shell am start -n org.formulagen.wrapper/.MainActivity -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL <url>` (debug builds only, which accept HTTPS or http on loopback, so `adb reverse tcp:8080 tcp:8080` reaches the local edge; release builds read the `gamero_product` and `gamero_web_url` string resources, set from `-Pgamero.product` and `-Pgamero.webUrl`).
`gradle.properties` sets `android.builtInKotlin=false` and `android.newDsl=false` only
because AGP 9 rejects the Android app plugin inside this multiplatform module. They are
temporary: remove them once the Android app moves to its own module.

This is a Kotlin Multiplatform project targeting Android, iOS, Desktop (JVM).

* [/composeApp](./composeApp/src) is for code that will be shared across your Compose Multiplatform applications.
  It contains several subfolders:
  - [commonMain](./composeApp/src/commonMain/kotlin) is for code that’s common for all targets.
  - Other folders are for Kotlin code that will be compiled for only the platform indicated in the folder name.
    For example, if you want to use Apple’s CoreCrypto for the iOS part of your Kotlin app,
    the [iosMain](./composeApp/src/iosMain/kotlin) folder would be the right place for such calls.
    Similarly, if you want to edit the Desktop (JVM) specific part, the [jvmMain](./composeApp/src/jvmMain/kotlin)
    folder is the appropriate location.

* [/iosApp](./iosApp/iosApp) contains iOS applications. Even if you’re sharing your UI with Compose Multiplatform,
  you need this entry point for your iOS app. This is also where you should add SwiftUI code for your project.


Learn more about [Kotlin Multiplatform](https://www.jetbrains.com/help/kotlin-multiplatform-dev/get-started.html)…