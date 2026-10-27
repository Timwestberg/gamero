import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeHotReload)
    alias(libs.plugins.serialization)
    alias(libs.plugins.google.gms.service) apply false
    }

// Desktop (JVM) is the default build. `gamero.mobileTargets` adds Android and iOS;
// `gamero.iosTargets` adds only iOS, so Xcode builds need no Android SDK.
val mobileTargets = providers.gradleProperty("gamero.mobileTargets").orNull == "true"
val iosTargets = mobileTargets || providers.gradleProperty("gamero.iosTargets").orNull == "true"

// Release settings, passed by tools/build-release.sh. Blank values keep the development defaults.
fun releaseSetting(name: String): String? = providers.gradleProperty(name).orNull?.takeIf { it.isNotBlank() }
val releaseProduct = releaseSetting("gamero.product")
val releaseWebUrl = releaseSetting("gamero.webUrl")
val releaseVersion = releaseSetting("gamero.version") ?: "1.0.0"
val releaseBuildNumber = releaseSetting("gamero.buildNumber")?.toInt() ?: 1
fun signingSetting(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }
if (mobileTargets) {
    apply(plugin = "com.android.application")
    apply(plugin = "com.google.gms.google-services")
}

kotlin {
    jvmToolchain(21)
    if (mobileTargets) {
    androidTarget {
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }
    }
    if (iosTargets) {
    // No iosX64: androidx lifecycle-runtime-compose 2.11 does not publish it (Intel simulators unsupported).
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }
    
    }
    jvm()
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            group("skia") {
                withJvm()
                withIos()
            }
        }
    }
    
    sourceSets {
        if (mobileTargets) getByName("androidMain").dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
            // addWebMessageListener for the host bridge
            implementation(libs.androidx.webkit)
            implementation(libs.google.firebase.config)
        }
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.12.1")
            implementation("org.jetbrains.compose.foundation:foundation:1.12.1")
            implementation("org.jetbrains.compose.material3:material3:1.9.0")
            implementation("org.jetbrains.compose.ui:ui:1.12.1")
            implementation(compose.components.resources)
            implementation(compose.components.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            // Compose Multiplatform WebView
            implementation(libs.compose.webview.multiplatform)
            // Kotlinx serialization JSON
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.koin.core)
            implementation(libs.multiplatform.settings)


        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutinesTest)
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.testJunit)
            implementation("org.jetbrains.compose.ui:ui-test:1.12.1")
        }
        jvmMain.dependencies {
            implementation(project(":voice"))
            // Upgrade the WebView's old 2.5.0 transitive dependencies to the stable Maven Central release.
            implementation("org.jogamp.gluegen:gluegen-rt:2.6.0")
            implementation("org.jogamp.jogl:jogl-all:2.6.0")
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}

if (mobileTargets) extensions.configure<com.android.build.gradle.AppExtension> {
    namespace = "org.formulagen.wrapper"
    compileSdkVersion(libs.versions.android.compileSdk.get().toInt())

    defaultConfig {
        applicationId = "org.formulagen.wrapper"
        minSdkVersion(libs.versions.android.minSdk.get().toInt())
        targetSdkVersion(libs.versions.android.targetSdk.get().toInt())
        versionCode = releaseBuildNumber
        versionName = releaseVersion
        // Read by HovelAndroid.kt. Debuggable builds also accept launch extras.
        resValue("string", "gamero_product", releaseProduct.orEmpty())
        resValue("string", "gamero_web_url", releaseWebUrl.orEmpty())
    }
    buildFeatures.resValues = true
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    // Without a keystore, release builds come out unsigned for signing elsewhere.
    val keystore = signingSetting("GAMERO_ANDROID_KEYSTORE")
    if (keystore != null) signingConfigs.create("release") {
        storeFile = file(keystore)
        storePassword = signingSetting("GAMERO_ANDROID_KEYSTORE_PASSWORD")
        keyAlias = signingSetting("GAMERO_ANDROID_KEY_ALIAS")
        keyPassword = signingSetting("GAMERO_ANDROID_KEY_PASSWORD")
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

if (mobileTargets) dependencies {
    add("debugImplementation", compose.uiTooling)
}

compose.desktop {
    application {
        mainClass = "org.formulagen.wrapper.MainKt"
        providers.environmentVariable("GAMERO_BROWSER_JAVA_HOME").orNull?.let { javaHome = it }
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs += listOf("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }

        // A packaged app gets no shell environment, so the release URL and product are baked in.
        releaseProduct?.let { jvmArgs += "-Dgamero.product=$it" }
        releaseWebUrl?.let { jvmArgs += "-Dgamero.webUrl=$it" }

        buildTypes.release.proguard {
            // Shrink only, and keep names: KCEF, JCEF and the WebView bridge use reflection.
            // ProGuard's optimizer needs a complete class hierarchy, and dev.datlag:jcef and JOGL
            // extend Thrift, JavaFX and SWT classes the app never loads or ships.
            obfuscate.set(false)
            optimize.set(false)
            configurationFiles.from(project.file("proguard-desktop.pro"))
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "org.formulagen.wrapper"
            packageVersion = releaseVersion
            // The bundled runtime is GAMERO_BROWSER_JAVA_HOME's JetBrains Runtime, whose jcef module
            // the browser needs. Every module is kept until a Mac run of suggestRuntimeModules trims it.
            includeAllModules = true
            macOS {
                bundleID = "org.formulagen.wrapper"
                signingSetting("GAMERO_MAC_SIGN_IDENTITY")?.let { identity ->
                    signing {
                        sign.set(true)
                        this.identity.set(identity)
                    }
                }
                signingSetting("GAMERO_NOTARIZE_APPLE_ID")?.let { appleId ->
                    notarization {
                        appleID.set(appleId)
                        password.set(signingSetting("GAMERO_NOTARIZE_PASSWORD"))
                        teamID.set(signingSetting("GAMERO_NOTARIZE_TEAM_ID"))
                    }
                }
            }
        }
    }
}

// Required by the existing KCEF desktop WebView implementation.
tasks.withType<JavaExec>().configureEach {
    jvmArgs("--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED")
    if (System.getProperty("os.name").contains("Mac")) {
        jvmArgs("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
}

// Native companion prototype: common Compose screens without starting JCEF.
tasks.register<JavaExec>("runCompanions") {
    val target = kotlin.targets.getByName("jvm")
    val compilation = target.compilations.getByName("main")
    dependsOn("jvmMainClasses")
    mainClass.set("org.formulagen.wrapper.progression.CompanionMainKt")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Inspect the real material without launching a game or modifying a companion.
tasks.register<JavaExec>("runCompanionGlassPreview") {
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn("jvmMainClasses")
    mainClass.set("org.formulagen.wrapper.progression.GlassPreviewMainKt")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// macOS development proof: Godot's remote layer inside the Compose host window.
tasks.register<JavaExec>("runEmbeddedCompanions") {
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn("jvmMainClasses")
    mainClass.set("org.formulagen.wrapper.progression.embedded.EmbeddedCompanionMainKt")
    classpath = files(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// A named local app makes native-window inspection and manual testing unambiguous.
val embeddedCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
val embeddedRuntimeNames = providers.provider { requireNotNull(embeddedCompilation.runtimeDependencyFiles).files
    .filter { it.extension == "jar" }.sortedBy { it.absolutePath }
    .mapIndexed { index, file -> file to "$index-${file.name}" }.toMap() }
val embeddedJar = tasks.register<Jar>("embeddedCompanionJar") {
    dependsOn("jvmMainClasses")
    archiveFileName.set("hovel-embedded.jar")
    destinationDirectory.set(layout.buildDirectory.dir("embedded-package"))
    from(embeddedCompilation.output.allOutputs)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "org.formulagen.wrapper.progression.embedded.EmbeddedCompanionMainKt"
    manifest.attributes["Class-Path"] = embeddedRuntimeNames.get().values.joinToString(" ")
}
tasks.register<Sync>("prepareEmbeddedCompanionApp") {
    dependsOn(embeddedJar)
    into(layout.buildDirectory.dir("embedded-input"))
    from(embeddedJar.flatMap { it.archiveFile })
    embeddedRuntimeNames.get().forEach { (file, uniqueName) -> from(file) { rename { uniqueName } } }
}

val catalogJar = tasks.register<Jar>("catalogLauncherJar") {
    dependsOn("jvmMainClasses")
    archiveFileName.set("gamero-catalog.jar")
    destinationDirectory.set(layout.buildDirectory.dir("catalog-package"))
    from(embeddedCompilation.output.allOutputs)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest.attributes["Main-Class"] = "org.formulagen.wrapper.MainKt"
    manifest.attributes["Class-Path"] = embeddedRuntimeNames.get().values.joinToString(" ")
}
tasks.register<Sync>("prepareCatalogLauncherApp") {
    dependsOn(catalogJar)
    into(layout.buildDirectory.dir("catalog-input"))
    from(catalogJar.flatMap { it.archiveFile })
    embeddedRuntimeNames.get().forEach { (file, uniqueName) -> from(file) { rename { uniqueName } } }
}
