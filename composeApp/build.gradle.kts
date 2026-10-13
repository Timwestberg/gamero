import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJLinkTask
import org.jetbrains.compose.desktop.application.tasks.AbstractCheckNativeDistributionRuntime
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

val mobileTargets = providers.gradleProperty("gamero.mobileTargets").orNull == "true"
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
    
    listOf(
        iosX64(),
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
    
    sourceSets {
        if (mobileTargets) getByName("androidMain").dependencies {
            implementation(compose.preview)
            implementation(libs.androidx.activity.compose)
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
        }
        jvmTest.dependencies { implementation(libs.kotlin.testJunit) }
        jvmMain.dependencies {
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
        versionCode = 1
        versionName = "1.0"
    }
    packagingOptions {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
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

val browserJavaHome = providers.environmentVariable("GAMERO_BROWSER_JAVA_HOME")
val packagingJavaHome = providers.environmentVariable("GAMERO_PACKAGING_JAVA_HOME")
    .orElse(providers.systemProperty("java.home"))
val simulatedSmoke = providers.gradleProperty("gamero.simulatedSmoke").map {
    require(it == "true" || it == "false") { "gamero.simulatedSmoke must be true or false" }
    it == "true"
}.getOrElse(false)

// Compiled into the artifact: inherited JVM flags cannot turn a smoke build into a live one.
val generateProviderMode = tasks.register("generateProviderMode") {
    val output = layout.buildDirectory.dir("generated/providerMode")
    inputs.property("simulatedSmoke", simulatedSmoke)
    outputs.dir(output)
    val fixedSimulation = simulatedSmoke
    doLast {
        val source = output.get().file("org/formulagen/wrapper/companion/PackagedProviderMode.kt").asFile
        source.parentFile.mkdirs()
        source.writeText("package org.formulagen.wrapper.companion\ninternal object PackagedProviderMode { const val simulated = $fixedSimulation }\n")
    }
}
kotlin.sourceSets.getByName("jvmMain").kotlin.srcDir(generateProviderMode)

compose.desktop {
    application {
        mainClass = "org.formulagen.wrapper.MainKt"
        javaHome = packagingJavaHome.get()
        if (simulatedSmoke) jvmArgs += "-Dgamero.provider.mode=simulated"
        jvmArgs += listOf("--add-opens=java.desktop/sun.awt=ALL-UNNAMED", "--add-opens=java.desktop/java.awt.peer=ALL-UNNAMED")
        if (System.getProperty("os.name").contains("Mac")) {
            jvmArgs += listOf("--add-opens=java.desktop/sun.lwawt=ALL-UNNAMED", "--add-opens=java.desktop/sun.lwawt.macosx=ALL-UNNAMED")
        }

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = if (simulatedSmoke) "GameroSimulatedSmoke" else "org.formulagen.wrapper"
            packageVersion = "1.0.0"
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

// Compose registers and configures these tasks after project evaluation. Realize those
// registration actions first; an earlier configureEach is overwritten by Compose's defaults.
afterEvaluate {
    val isMac = System.getProperty("os.name").contains("Mac")
    val runtimeHome = browserJavaHome.orNull?.let(::file)
    if (runtimeHome == null) {
        if (isMac) {
            val requireInstalledRuntime = tasks.register("requireInstalledBrowserRuntime") {
                doLast { error("GAMERO_BROWSER_JAVA_HOME must identify the verified installed JBR bundle's Contents/Home") }
            }
            // Fail before packaging/input validation or jlink; unrelated JVM tasks remain available.
            tasks.withType<AbstractJPackageTask>().forEach { it.dependsOn(requireInstalledRuntime) }
            tasks.withType<AbstractJLinkTask>().forEach { it.dependsOn(requireInstalledRuntime) }
        }
        return@afterEvaluate
    }
    val runtimeBundle = runtimeHome.parentFile?.parentFile
    val packagingHome = file(packagingJavaHome.get())
    tasks.withType<JavaExec>().forEach {
        if (it.name == "run" || it.name == "runRelease") it.executable = runtimeHome.resolve("bin/java").absolutePath
    }
    tasks.withType<AbstractCheckNativeDistributionRuntime>().forEach {
        it.jdkHome.set(runtimeHome.absolutePath)
        it.jdkHome.disallowChanges()
    }
    tasks.withType<AbstractJLinkTask>().forEach {
        // Never trim the installed runtime or require its missing jlink executable.
        it.enabled = false
    }
    tasks.withType<AbstractJPackageTask>().forEach { task -> task.apply {
        javaHome.set(packagingHome.absolutePath)
        javaHome.disallowChanges()
        runtimeImage.set(runtimeHome)
        runtimeImage.disallowChanges()
        if (isMac) doFirst {
            require(runtimeHome.name == "Home" && runtimeHome.parentFile.name == "Contents") {
                "GAMERO_BROWSER_JAVA_HOME must identify the verified JBR bundle's Contents/Home"
            }
            require(runtimeHome.resolve("bin/java").canExecute() && runtimeHome.resolve("lib/libjcef.dylib").isFile)
            require(runtimeBundle!!.resolve("Contents/Frameworks").isDirectory) { "Complete JBR/JCEF Frameworks are required" }
            require(packagingHome.resolve("bin/jpackage").canExecute() && packagingHome.resolve("bin/jlink").canExecute()) {
                "GAMERO_PACKAGING_JAVA_HOME must provide approved jpackage and jlink tools"
            }
            require(File("/usr/bin/ditto").canExecute()) { "macOS ditto is required to preserve the runtime bundle" }
        }
        if (isMac && targetFormat == TargetFormat.AppImage) {
            runtimeBundle?.let { inputs.dir(it) }
            doLast {
                val app = destinationDir.get().asFile.listFiles().orEmpty().single { it.extension == "app" }
                val runtime = app.resolve("Contents/runtime")
                require(runtime.isDirectory) { "Packaged runtime directory is missing" }
                // Replace only this task's generated runtime; merging would retain files outside the source signature.
                check(runtime.deleteRecursively()) { "Could not remove generated Home-only runtime" }
                // jpackage copies only Home. Restore the complete verified bundle, preserving framework links and metadata.
                val copy = ProcessBuilder("/usr/bin/ditto", "--rsrc", "--extattr", "--acl",
                    runtimeBundle!!.absolutePath, runtime.absolutePath).inheritIO().start()
                check(copy.waitFor() == 0) { "Could not preserve the complete JBR/JCEF runtime" }
                check(runtime.resolve("Contents/Frameworks").isDirectory)
                check(runtime.resolve("Contents/Home/lib/libjcef.dylib").isFile)
                // Verification only: never strip signatures, change quarantine or access signing identities.
                for (bundle in listOf(runtimeBundle, runtime, app)) {
                    val verify = ProcessBuilder("/usr/bin/codesign", "--verify", "--deep", "--strict", bundle!!.absolutePath)
                        .inheritIO().start()
                    check(verify.waitFor() == 0) { "Runtime/app signature verification failed; stop before launch" }
                }
            }
        } else if (isMac && targetFormat != TargetFormat.AppImage) {
            // Installers must consume the already-completed app image, not repackage a Home-only runtime.
            val imageName = if (name.contains("Release")) "createReleaseDistributable" else "createDistributable"
            val image = tasks.named<AbstractJPackageTask>(imageName)
            appImage.set(image.flatMap { it.destinationDir })
            appImage.disallowChanges()
        }
    } }
    // These final properties are frozen after Compose's registration actions have run.
    val effectiveImages = tasks.withType<AbstractJPackageTask>().associate { it.name to it.runtimeImage.get().asFile.absolutePath }
    val effectiveTools = tasks.withType<AbstractJPackageTask>().associate { it.name to it.javaHome.get() }
    val enabledLinkTasks = tasks.withType<AbstractJLinkTask>().filter { it.enabled }.map { it.name }
    tasks.register("verifyDesktopRuntimeWiring") {
        group = "verification"
        description = "Check the effective installed-runtime wiring without packaging or launching."
        inputs.property("expectedRuntime", runtimeHome.absolutePath)
        inputs.property("expectedPackagingJdk", packagingHome.absolutePath)
        inputs.property("effectiveRuntimeImages", effectiveImages)
        inputs.property("effectivePackagingJdks", effectiveTools)
        inputs.property("enabledLinkTasks", enabledLinkTasks)
        doLast {
            check(effectiveImages.isNotEmpty()) { "Compose packaging tasks are missing" }
            check(effectiveImages.values.all { it == runtimeHome.absolutePath }) { "Compose replaced the installed runtime: $effectiveImages" }
            check(effectiveTools.values.all { it == packagingHome.absolutePath }) { "Wrong packaging JDK: $effectiveTools" }
            check(enabledLinkTasks.isEmpty()) { "Installed runtime must not depend on enabled jlink tasks" }
            effectiveImages.forEach { (name, path) -> println("$name runtimeImage=$path") }
            println("GAMERO_RUNTIME_WIRING_VERIFIED packagingJdk=${packagingHome.absolutePath}")
        }
    }
}
