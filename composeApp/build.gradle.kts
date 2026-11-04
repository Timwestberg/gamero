import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

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
// A packaged app marked as a development build (-Dgamero.dev=true), so development-gated features
// such as project workspaces can be checked signed. Never a release.
val developmentPackage = releaseSetting("gamero.developmentPackage") == "true"
check(!(developmentPackage && releaseBuild)) { "gamero.developmentPackage and gamero.release cannot be combined" }
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
            // The versions pty4j and JediTerm raised these to, named so a release, which has neither,
            // still resolves them.
            implementation("org.jetbrains:annotations:26.1.0")
            implementation("org.slf4j:slf4j-api:2.0.19")
            if (!releaseBuild) {
                // Project workspace terminal, development builds only (src/jvmDev). pty4j (EPL-1.0,
                // Maven Central) runs each tab's process on a pseudo-terminal; JediTerm (dual
                // LGPLv3/Apache-2.0, used under Apache-2.0; JetBrains' repository, see
                // settings.gradle.kts) draws it in Swing.
                implementation("org.jetbrains.pty4j:pty4j:0.14.0")
                // pty4j's own JNA (runtime scope in its POM), named here to call tcgetpgrp for a Shell
                // tab's busy check. Keep it at the version pty4j brings.
                implementation("net.java.dev.jna:jna:5.19.1")
                implementation("org.jetbrains.jediterm:jediterm-core:3.76")
                implementation("org.jetbrains.jediterm:jediterm-ui:3.76")
            }
        }
        // Development builds also compile src/jvmDev: the project workspace, its terminal, the probe
        // and their entry points. A release compiles src/jvmRelease instead, so none of it, and none of
        // the terminal libraries above, can reach a release (docs/desktop.md, "Build boundaries").
        getByName("jvmMain") {
            kotlin.srcDir(if (releaseBuild) "src/jvmRelease/kotlin" else "src/jvmDev/kotlin")
            if (!releaseBuild) resources.srcDir("src/jvmDev/resources")
        }
        if (!releaseBuild) getByName("jvmTest").kotlin.srcDir("src/jvmDevTest/kotlin")
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

/**
 * pty4j and JNA unpack their macOS natives from their jars to a temporary folder at run time, where
 * the hardened runtime can refuse them. A packaged development build ships them as app resources
 * instead (macos-arm64/ becomes Contents/app/resources/), signed with the app, and
 * `usePackagedNatives` points both libraries there. pty4j's spawn helper must stay executable.
 * Apple Silicon only: Compose packages macos-arm64/ only on an arm64 build machine, and JNA's copy is
 * its darwin-aarch64 library. A release build (gamero.release) has none of them: it does not depend
 * on pty4j, JNA or JediTerm at all (see the source split above, and docs/release.md).
 */
if (!releaseBuild) {
    val workspaceNatives = tasks.register<Sync>("workspaceNatives") {
        val runtime = configurations.named("jvmRuntimeClasspath").flatMap { it.elements }
        fun jar(prefix: String) = runtime.map { files -> files.map { it.asFile }.single { it.name.startsWith(prefix) && it.extension == "jar" } }
        from(zipTree(jar("pty4j-"))) {
            include("resources/com/pty4j/native/darwin/**")
            eachFile { path = "macos-arm64/pty4j/darwin/$name" }
            filesMatching("**/pty4j-unix-spawn-helper") { permissions { unix("rwxr-xr-x") } }
        }
        from(zipTree(jar("jna-5."))) {
            include("com/sun/jna/darwin-aarch64/libjnidispatch.jnilib")
            eachFile { path = "macos-arm64/jna/$name" }
        }
        includeEmptyDirs = false
        into(layout.buildDirectory.dir("workspace-natives"))
    }
    afterEvaluate { tasks.named("prepareAppResources") { dependsOn(workspaceNatives) } }
    // Compose copies app resources into the image without their file modes (Kotlin's File.copyTo), so
    // the spawn helper arrives as a plain file and pty4j cannot start it. A mode is not part of the seal.
    tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
        val apps = layout.buildDirectory.dir("compose/binaries")
        doLast {
            apps.get().asFileTree.matching { include("**/Contents/app/resources/pty4j/darwin/pty4j-unix-spawn-helper") }
                .forEach { check(it.setExecutable(true, false)) { "Could not make $it executable" } }
        }
    }
}

/*
 * A release app must hold no entry listed in release-excluded.txt: the probe and the development entry
 * points, the workspace terminal, pty4j, JNA, JediTerm and their natives. Each distributable a release
 * build makes is scanned when it is made, and the build fails on the first one found (docs/release.md, "Desktop").
 */
if (releaseBuild) {
    val excluded = layout.projectDirectory.file("release-excluded.txt")
    tasks.matching { it.name == "createDistributable" || it.name == "createReleaseDistributable" }.configureEach {
        val app = layout.buildDirectory.dir(if (name == "createReleaseDistributable") "compose/binaries/main-release/app" else "compose/binaries/main/app")
        inputs.file(excluded)
        doLast {
            val prefixes = excluded.asFile.readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
            val jars = app.get().asFileTree.matching { include("**/*.jar") }.files
            check(jars.isNotEmpty()) { "No jars under ${app.get().asFile}, so the release check would pass by finding nothing" }
            val found = jars.flatMap { jar ->
                ZipFile(jar).use { zip ->
                    zip.entries().asSequence().map { it.name }.filter { entry -> prefixes.any(entry::startsWith) }.map { "${jar.name}!$it" }.toList()
                }
            }
            check(found.isEmpty()) { "This release app holds development-only code (release-excluded.txt):\n" + found.take(20).joinToString("\n") }
            logger.lifecycle("Release check: ${jars.size} jars, none holds an entry from release-excluded.txt")
        }
    }
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
        if (developmentPackage) jvmArgs += "-Dgamero.dev=true"

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
            // The workspace terminal's natives, in Contents/app/resources so they are signed with the
            // app. Development packages only (workspaceNatives).
            if (!releaseBuild) appResourcesRootDir.set(layout.buildDirectory.dir("workspace-natives"))
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

// The plain JavaExec entry points (registerJvmMain) and the local jpackage prototypes are marked as development
// launches, so features gated to development builds may turn on. Compose's own run task takes compose.desktop's
// jvmArgs instead, as packaging does: they carry the marker only with -Pgamero.developmentPackage=true (never with
// gamero.release), so a plain ./gradlew run is not a development build.
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
