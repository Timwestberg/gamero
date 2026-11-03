rootProject.name = "WestbergWrapper"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JediTerm (the workspace terminal) is published only to JetBrains' IntelliJ dependencies
        // repository. Nothing else may resolve from it.
        exclusiveContent {
            forRepository {
                maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") { name = "jetbrainsIntellijDependencies" }
            }
            filter { includeGroup("org.jetbrains.jediterm") }
        }
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include(":composeApp")
include(":voice")