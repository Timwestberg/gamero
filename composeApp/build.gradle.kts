import java.io.File
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask
import org.jetbrains.compose.desktop.application.tasks.AbstractJvmToolOperationTask
import org.jetbrains.compose.desktop.application.tasks.AbstractRunDistributableTask
import org.jetbrains.compose.desktop.application.tasks.AbstractProguardTask
import org.jetbrains.compose.desktop.application.tasks.AbstractSuggestModulesTask
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
        // Despite its name this task validates java, jlink and jpackage and emits tool-JDK metadata.
        it.jdkHome.set(packagingHome.absolutePath)
        it.jdkHome.disallowChanges()
    }
    tasks.withType<AbstractJvmToolOperationTask>().forEach {
        it.javaHome.set(packagingHome.absolutePath)
        it.javaHome.disallowChanges()
    }
    tasks.withType<AbstractProguardTask>().forEach {
        it.javaHome.set(packagingHome.absolutePath)
        it.javaHome.disallowChanges()
    }
    tasks.withType<AbstractSuggestModulesTask>().forEach {
        it.javaHome.set(packagingHome.absolutePath)
        it.javaHome.disallowChanges()
    }
    val verifyBrowserRuntime = tasks.register("verifyInstalledBrowserRuntime") {
        group = "verification"
        description = "Validate the installed browser runtime without requiring packaging tools in it."
        inputs.property("runtimeHome", runtimeHome.absolutePath)
        inputs.property("macBundleRequired", isMac)
        doLast {
            val java = runtimeHome.resolve("bin/java")
            require(java.canExecute()) { "Installed browser runtime is missing executable bin/java" }
            // Only the approved runtime's read-only module probe; never launch the app or provision a JDK.
            val probe = ProcessBuilder(java.absolutePath, "--list-modules").redirectErrorStream(true).start()
            val modules = probe.inputStream.bufferedReader().use { it.readText() }
            check(probe.waitFor() == 0) { "Installed browser module probe failed: $modules" }
            check(modules.lineSequence().any { it.startsWith("java.base@21.") }) { "Browser runtime must be JDK21" }
            if (isMac) {
                require(runtimeHome.name == "Home" && runtimeHome.parentFile.name == "Contents")
                require(runtimeHome.resolve("lib/libjcef.dylib").isFile)
                require(runtimeBundle!!.resolve("Contents/Frameworks").isDirectory) { "Complete JBR/JCEF Frameworks are required" }
                check(modules.lineSequence().any { it.startsWith("jcef@") || it == "jcef" }) { "Installed JBR is missing the jcef module" }
            }
            println("GAMERO_BROWSER_RUNTIME_VERIFIED runtimeHome=${runtimeHome.absolutePath}")
        }
    }
    tasks.withType<AbstractJLinkTask>().forEach {
        // Never trim the installed runtime or require its missing jlink executable.
        it.enabled = false
    }
    tasks.withType<AbstractJPackageTask>().toList().forEach { task -> task.apply {
        dependsOn(verifyBrowserRuntime)
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
            val finalizer = rootProject.file("tools/finalize_mac_app.py")
            val app = destinationDir.get().asFile.resolve("${packageName.get()}.app")
            val outputRoot = destinationDir.get().asFile
            val manifest = layout.buildDirectory.file("compose/seals/${name}.json").get().asFile
            val entitlements = macEntitlementsFile.orNull?.asFile
            val arguments = listOf("--app", app.absolutePath, "--source-runtime", runtimeBundle!!.absolutePath,
                "--output-root", outputRoot.absolutePath, "--manifest", manifest.absolutePath)
            inputs.file(finalizer)
            outputs.file(manifest)
            doLast {
                // Compose's staged image is complete. Restore vendor runtime, then seal ONLY our outer app.
                val command = mutableListOf("python3", finalizer.absolutePath, "finalize")
                command.addAll(arguments)
                entitlements?.let { command.addAll(listOf("--entitlements", it.absolutePath)) }
                val finalize = ProcessBuilder(command).inheritIO().start()
                check(finalize.waitFor() == 0) { "Final app signing/verification failed; stop before launch" }
            }
            val verificationName = if (name.contains("Release")) "verifyFinalizedReleaseDistributable" else "verifyFinalizedDistributable"
            tasks.register(verificationName) {
                group = "verification"
                description = "Reject changes after final signing before consuming the app image."
                dependsOn(task)
                inputs.file(finalizer)
                inputs.file(manifest)
                inputs.dir(app)
                doLast {
                    val command = listOf("python3", finalizer.absolutePath, "verify") + arguments
                    val verify = ProcessBuilder(command).inheritIO().start()
                    check(verify.waitFor() == 0) { "Finalized app changed or signature verification failed; stop before launch" }
                }
            }
        } else if (isMac && targetFormat != TargetFormat.AppImage) {
            // Installers must consume the already-completed app image, not repackage a Home-only runtime.
            val imageName = if (name.contains("Release")) "createReleaseDistributable" else "createDistributable"
            dependsOn(if (name.contains("Release")) "verifyFinalizedReleaseDistributable" else "verifyFinalizedDistributable")
            val image = tasks.named<AbstractJPackageTask>(imageName)
            appImage.set(image.flatMap { it.destinationDir })
            appImage.disallowChanges()
        }
    } }
    if (isMac) tasks.withType<AbstractRunDistributableTask>().forEach {
        it.dependsOn(if (it.name.contains("Release")) "verifyFinalizedReleaseDistributable" else "verifyFinalizedDistributable")
    }
    // These final properties are frozen after Compose's registration actions have run.
    val effectiveImages = tasks.withType<AbstractJPackageTask>().associate { it.name to it.runtimeImage.get().asFile.absolutePath }
    val effectiveTools = linkedMapOf<String, String>().apply {
        tasks.withType<AbstractCheckNativeDistributionRuntime>().forEach { put("${it.name}.jdkHome", it.jdkHome.get()) }
        tasks.withType<AbstractJvmToolOperationTask>().forEach { put("${it.name}.javaHome", it.javaHome.get()) }
        tasks.withType<AbstractProguardTask>().forEach { put("${it.name}.javaHome", it.javaHome.get()) }
        tasks.withType<AbstractSuggestModulesTask>().forEach { put("${it.name}.javaHome", it.javaHome.get()) }
    }
    val effectiveLaunchers = tasks.withType<JavaExec>().filter { it.name == "run" || it.name == "runRelease" }
        .associate { it.name to it.executable }
    val checkedMetadata = tasks.withType<AbstractCheckNativeDistributionRuntime>()
        .map { it.javaRuntimePropertiesFile.get().asFile.absolutePath }.toSet()
    val consumedMetadata = linkedMapOf<String, String>().apply {
        tasks.withType<AbstractJPackageTask>().forEach { put(it.name, it.javaRuntimePropertiesFile.get().asFile.absolutePath) }
        tasks.withType<AbstractJLinkTask>().forEach { put(it.name, it.javaRuntimePropertiesFile.get().asFile.absolutePath) }
    }
    val packageDependencies = tasks.withType<AbstractJPackageTask>().toList().associate { task ->
        task.name to task.taskDependencies.getDependencies(task).map { it.name }.sorted()
    }
    val finalizedConsumers = linkedMapOf<String, List<String>>()
    if (isMac) {
        tasks.withType<AbstractJPackageTask>().toList().filter { it.targetFormat != TargetFormat.AppImage }.forEach {
            finalizedConsumers[it.name] = it.taskDependencies.getDependencies(it).map { dependency -> dependency.name }
        }
        tasks.withType<AbstractRunDistributableTask>().toList().forEach {
            finalizedConsumers[it.name] = it.taskDependencies.getDependencies(it).map { dependency -> dependency.name }
        }
    }
    val checkNames = tasks.withType<AbstractCheckNativeDistributionRuntime>().map { it.name }.toSet()
    val enabledLinkTasks = tasks.withType<AbstractJLinkTask>().filter { it.enabled }.map { it.name }
    tasks.register("verifyDesktopRuntimeWiring") {
        group = "verification"
        description = "Check the effective installed-runtime wiring without packaging or launching."
        inputs.property("expectedRuntime", runtimeHome.absolutePath)
        inputs.property("expectedPackagingJdk", packagingHome.absolutePath)
        inputs.property("effectiveRuntimeImages", effectiveImages)
        inputs.property("effectivePackagingJdks", effectiveTools)
        inputs.property("enabledLinkTasks", enabledLinkTasks)
        inputs.property("effectiveLaunchers", effectiveLaunchers)
        inputs.property("checkedMetadata", checkedMetadata)
        inputs.property("consumedMetadata", consumedMetadata)
        inputs.property("packageDependencies", packageDependencies)
        inputs.property("toolCheckTasks", checkNames)
        inputs.property("finalizedConsumers", finalizedConsumers)
        doLast {
            for (tool in listOf("java", "jlink", "jpackage", "jdeps")) {
                check(packagingHome.resolve("bin/$tool").canExecute()) { "Packaging JDK is missing $tool" }
            }
            check(packagingHome.resolve("jmods").isDirectory) { "Packaging JDK needs jmods for release processing" }
            check(effectiveImages.isNotEmpty()) { "Compose packaging tasks are missing" }
            check(effectiveImages.values.all { it == runtimeHome.absolutePath }) { "Compose replaced the installed runtime: $effectiveImages" }
            check(effectiveTools.values.all { it == packagingHome.absolutePath }) { "Wrong packaging JDK: $effectiveTools" }
            check(effectiveLaunchers.isNotEmpty() && effectiveLaunchers.values.all { it == runtimeHome.resolve("bin/java").absolutePath }) {
                "Wrong browser launch executable: $effectiveLaunchers"
            }
            check(checkedMetadata.isNotEmpty() && consumedMetadata.values.all { it in checkedMetadata }) {
                "Packaging metadata must come from the packaging JDK check: $consumedMetadata"
            }
            check(packageDependencies.values.all { dependencies ->
                "verifyInstalledBrowserRuntime" in dependencies && dependencies.any { it in checkNames }
            }) { "Packaging must depend on both tool and browser validation: $packageDependencies" }
            check(finalizedConsumers.all { (name, dependencies) ->
                (if (name.contains("Release")) "verifyFinalizedReleaseDistributable" else "verifyFinalizedDistributable") in dependencies
            }) { "Installer/launcher must verify the final app seal: $finalizedConsumers" }
            check(enabledLinkTasks.isEmpty()) { "Installed runtime must not depend on enabled jlink tasks" }
            effectiveImages.forEach { (name, path) -> println("$name runtimeImage=$path") }
            effectiveTools.forEach { (property, path) -> println("$property=$path") }
            effectiveLaunchers.forEach { (name, path) -> println("$name executable=$path") }
            consumedMetadata.forEach { (name, path) -> println("$name toolMetadata=$path") }
            packageDependencies.forEach { (name, dependencies) -> println("$name dependencies=$dependencies") }
            println("GAMERO_RUNTIME_WIRING_VERIFIED packagingJdk=${packagingHome.absolutePath}")
        }
    }
}
