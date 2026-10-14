# Gamero

Mac desktop development: [build and launch guide](docs/desktop.md). Desktop is the default build target; mobile targets are opt-in and outside the current verification scope.

Android: build with `./gradlew -Pgamero.mobileTargets=true :composeApp:assembleDebug`
(Gradle 9.3.1, AGP 9.1.1, compileSdk 37; Compose 1.12 requires them). Launch the Hovel
start screen with `adb shell am start -n org.formulagen.wrapper/.MainActivity -e GAMERO_PRODUCT hovel -e GAMERO_WEB_URL <https url>`.
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