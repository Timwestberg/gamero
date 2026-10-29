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
        val sherpaJar = rootProject.file("spikes/ember-voice/mac/deps/sherpa/sherpa-onnx-jvm-2.0.0.jar")
        val sherpaAar = file("libs/sherpa-onnx.aar")
        jvmMain {
            if (sherpaJar.isFile) {
                dependsOn(sherpaMain)
            } else {
                kotlin.srcDir("src/jvmNoSherpa/kotlin")
            }
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        jvmMain.dependencies {
            if (sherpaJar.isFile) {
                implementation(files(sherpaJar))
                runtimeOnly(fileTree(rootProject.file("spikes/ember-voice/mac/deps/sherpa")) {
                    include("sherpa-onnx-native-lib-*.jar")
                })
            }
        }
        if (mobileTargets) {
            androidMain {
                if (sherpaAar.isFile) {
                    dependsOn(sherpaMain)
                } else {
                    kotlin.srcDir("src/androidNoSherpa/kotlin")
                }
            }
            androidMain.dependencies {
                if (sherpaAar.isFile) {
                    implementation(files(sherpaAar))
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

tasks.register<JavaExec>("echoSelfTest") {
    group = "verification"
    description = "Play a clip through the Mac voice-processing graph and write ~/.gamero/hearing-self-check."
    val compilation = kotlin.targets.getByName("jvm").compilations.getByName("main")
    dependsOn(compilation.compileTaskProvider)
    classpath(compilation.output.allOutputs, compilation.runtimeDependencyFiles)
    mainClass.set("org.formulagen.wrapper.voice.EchoSelfTestKt")
}
