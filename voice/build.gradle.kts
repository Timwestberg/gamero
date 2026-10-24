plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary) apply false
}

// Same opt-in as :composeApp. The default build is the JVM Mac companion.
// -Pgamero.mobileTargets adds Android and iOS. -Pgamero.iosTargets adds only iOS.
val mobileTargets = providers.gradleProperty("gamero.mobileTargets").orNull == "true"
val iosTargets = mobileTargets || providers.gradleProperty("gamero.iosTargets").orNull == "true"

if (mobileTargets) {
    apply(plugin = "com.android.library")
}

kotlin {
    jvmToolchain(21)
    jvm()
    if (mobileTargets) {
        androidTarget {
            compilations.all {
                compileTaskProvider.configure {
                    compilerOptions {
                        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
                    }
                }
            }
        }
    }
    if (iosTargets) {
        listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
            iosTarget.compilations.getByName("main").cinterops.create("ember_voice") {
                definitionFile.set(file("src/nativeInterop/cinterop/ember_voice.def"))
                includeDirs(file("ios"))
            }
            iosTarget.binaries.framework {
                baseName = "EmberVoice"
                isStatic = true
            }
        }
    }

    sourceSets {
        val sherpaMain by creating {
            dependsOn(commonMain.get())
        }
        jvmMain {
            dependsOn(sherpaMain)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            val jar = rootProject.file("spikes/ember-voice/mac/deps/sherpa/sherpa-onnx-jvm-2.0.0.jar")
            if (jar.isFile) {
                implementation(files(jar))
            }
        }
        if (mobileTargets) {
            androidMain {
                dependsOn(sherpaMain)
            }
            androidMain.dependencies {
                val aar = file("libs/sherpa-onnx.aar")
                if (aar.isFile) {
                    implementation(files(aar))
                }
            }
        }
    }
}

if (mobileTargets) {
    extensions.configure<com.android.build.gradle.LibraryExtension> {
        namespace = "org.formulagen.wrapper.voice"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        defaultConfig {
            minSdk = libs.versions.android.minSdk.get().toInt()
        }
        compileOptions {
            sourceCompatibility = JavaVersion.VERSION_11
            targetCompatibility = JavaVersion.VERSION_11
        }
    }
}
