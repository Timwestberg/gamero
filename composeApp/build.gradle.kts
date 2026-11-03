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
// Release builds keep gated features off whatever the environment says (Claude: claudeProviderEnabled).
val releaseBuild = releaseSetting("gamero.release") == "true"
val releaseVersion = releaseSetting("gamero.version") ?: "1.0.0"
val releaseBuildNumber = releaseSetting("gamero.buildNumber")?.toInt() ?: 1
fun signingSetting(name: String): String? = providers.environmentVariable(name).orNull?.takeIf { it.isNotBlank() }

// KCEF's desktop WebView and the embedded game's JAWT bridge reach into AWT internals. The one list
// for Gradle runs, the packaged app, and the jpackage scripts (via desktopJvmOptions below).
val desktopAddOpens = buildList {
    add("--add-opens=java.desktop/sun.awt=ALL-UNNAMED")
    add("--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED")
    if (System.getProperty("os.name").contains("Mac")) {
        add("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED")
        add("--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
    }
}
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
            implementation("org.jetbrains.compose.ui:ui-test:${libs.versions.composeMultiplatform.get()}")
        }
        jvmMain.dependencies {
            implementation(project(":voice"))
            implementation("org.tomlj:tomlj:1.1.1")
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
        jvmArgs += desktopAddOpens

        // A packaged app gets no shell environment, so the release URL and product are baked in.
        releaseProduct?.let { jvmArgs += "-Dgamero.product=$it" }
        releaseWebUrl?.let { jvmArgs += "-Dgamero.webUrl=$it" }
        if (releaseBuild) jvmArgs += "-Dgamero.release=true"

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

// Development launches (Gradle run tasks and the local jpackage prototypes) are marked, so features gated to
// development builds may turn on; release packaging uses compose.desktop's jvmArgs and is never marked.
val developmentJvmOptions = listOf("-Dgamero.dev=true")

// Required by the existing KCEF desktop WebView implementation.
tasks.withType<JavaExec>().configureEach { jvmArgs(desktopAddOpens + developmentJvmOptions) }

val jvmMainCompilation = kotlin.targets.getByName("jvm").compilations.getByName("main")

/** A development entry point run from the JVM main classes on Java 21. */
fun registerJvmMain(name: String, mainClass: String) = tasks.register<JavaExec>(name) {
    dependsOn("jvmMainClasses")
    this.mainClass.set(mainClass)
    classpath = files(jvmMainCompilation.output.allOutputs, jvmMainCompilation.runtimeDependencyFiles)
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// Native companion prototype: common Compose screens without starting JCEF.
registerJvmMain("runCompanions", "org.formulagen.wrapper.progression.CompanionMainKt")
// Inspect the real material without launching a game or modifying a companion.
registerJvmMain("runCompanionGlassPreview", "org.formulagen.wrapper.progression.GlassPreviewMainKt")

// jpackage inputs for the local macOS prototypes. Runtime jars get an index prefix so two
// dependencies with the same file name cannot overwrite each other.
val runtimeJarNames = providers.provider { requireNotNull(jvmMainCompilation.runtimeDependencyFiles).files
    .filter { it.extension == "jar" }.sortedBy { it.absolutePath }
    .mapIndexed { index, file -> file to "$index-${file.name}" }.toMap() }

// desktopAddOpens and the development marker, one option per line, for the local jpackage scripts
// (scripts/dev-env.sh reads it). tools/build-release.sh packages through compose.desktop instead.
val desktopJvmOptions = tasks.register("desktopJvmOptions") {
    val options = desktopAddOpens + developmentJvmOptions
    val output = layout.buildDirectory.file("desktop-app/jvm-options.txt")
    inputs.property("options", options)
    outputs.file(output)
    doLast { output.get().asFile.writeText(options.joinToString("\n", postfix = "\n")) }
}

/**
 * `<id>Jar` builds [archive] with [mainClass] and a manifest Class-Path of the runtime jars;
 * `prepare<Id>App` syncs it and those jars into build/<dir>-input for jpackage.
 */
fun registerDesktopAppInput(id: String, dir: String, archive: String, mainClass: String) {
    val jar = tasks.register<Jar>("${id}Jar") {
        dependsOn("jvmMainClasses")
        archiveFileName.set(archive)
        destinationDirectory.set(layout.buildDirectory.dir("$dir-package"))
        from(jvmMainCompilation.output.allOutputs)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        manifest.attributes["Main-Class"] = mainClass
        manifest.attributes["Class-Path"] = runtimeJarNames.get().values.joinToString(" ")
    }
    tasks.register<Sync>("prepare${id.replaceFirstChar(Char::uppercase)}App") {
        dependsOn(jar, desktopJvmOptions)
        into(layout.buildDirectory.dir("$dir-input"))
        from(jar.flatMap { it.archiveFile })
        runtimeJarNames.get().forEach { (file, uniqueName) -> from(file) { rename { uniqueName } } }
    }
}

// A named local app makes native-window inspection and manual testing unambiguous.
registerDesktopAppInput("embeddedCompanion", "embedded", "hovel-embedded.jar",
    "org.formulagen.wrapper.progression.embedded.EmbeddedCompanionMainKt")
registerDesktopAppInput("catalogLauncher", "catalog", "gamero-catalog.jar", "org.formulagen.wrapper.MainKt")

// Explicit live QA must never replay cached test results.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (providers.environmentVariable("HOVEL_CONSENT_SMOKE").isPresent) {
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Local consent acceptance uses live services") { true }
    }
}
