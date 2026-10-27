package org.formulagen.wrapper.pet

import java.nio.file.Files
import kotlinx.coroutines.*
import org.formulagen.wrapper.bridge.PetState
import kotlin.test.*

class DesktopPetLauncherTest {
    /** The production adapter currently owns macOS overlays only. */
    @org.junit.Before fun requireMacOS() {
        org.junit.Assume.assumeTrue(System.getProperty("os.name").contains("Mac"))
    }

    /** Create an isolated fake engine that exercises actual process ownership. */
    private fun installation(body: String, version: String = "4.7.2.stable.test") = Files.createTempDirectory("pet-launch-test").also { root ->
        Files.createDirectories(root.resolve("addons/jvm"))
        Files.createDirectories(root.resolve("build/libs"))
        listOf("project.godot", "addons/jvm/jvm.gdextension", "build/libs/ember-desktop-pet.jar")
            .forEach { Files.createFile(root.resolve(it)) }
        root.resolve("engine").toFile().apply {
            writeText("#!/bin/sh\nif [ \"\$1\" = '--version' ]; then echo '$version'; exit 0; fi\n$body\n")
            setExecutable(true)
        }
    }

    /** Show is idempotent, external exit is observed, and hide/close own cleanup. */
    @Test fun managesOnePetAndAllowsRelaunch() = runBlocking<Unit> {
        val root = installation("echo \$\$ >> starts\necho EMBER_PET_READY\nexec sleep 120")
        val launcher = DesktopPetLauncher(mapOf("HOVEL_PET_PROJECT" to root.toString(),
            "HOVEL_PET_GODOT" to root.resolve("engine").toString()), root.resolve("pet.log"))
        try {
            assertEquals(PetState.STOPPED, launcher.status().state)
            assertEquals(PetState.RUNNING, launcher.show().state)
            launcher.show()
            assertEquals(1, Files.readAllLines(root.resolve("starts")).size)
            val first = ProcessHandle.of(Files.readAllLines(root.resolve("starts")).first().toLong()).get()
            first.destroy()
            withTimeout(3000) { while (first.isAlive) delay(20) }
            assertEquals(PetState.STOPPED, launcher.status().state)
            launcher.show()
            val second = ProcessHandle.of(Files.readAllLines(root.resolve("starts")).last().toLong()).get()
            assertEquals(PetState.STOPPED, launcher.hide().state)
            assertFalse(second.isAlive)
            launcher.show()
            val third = ProcessHandle.of(Files.readAllLines(root.resolve("starts")).last().toLong()).get()
            launcher.close()
            assertFalse(third.isAlive)
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }

    /** A missing native installation never starts a process; page fields are the router's to reject (pet-show-with-path). */
    @Test fun reportsUnavailableInstallations() = runBlocking<Unit> {
        val root = Files.createTempDirectory("pet-unavailable-test")
        val launcher = DesktopPetLauncher(emptyMap(), root.resolve("pet.log"))
        try {
            assertEquals(PetState.UNAVAILABLE, launcher.show().state)
            assertEquals(PetState.UNAVAILABLE, launcher.status().state)
            assertEquals(PetState.STOPPED, launcher.hide().state)
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }

    /** Wrong runtime and startup failure produce a stopped child and retryable feedback. */
    @Test fun reportsStartupFailures() = runBlocking<Unit> {
        for ((version, body) in listOf("4.8.beta1" to "echo EMBER_PET_READY", "4.7.2.stable.test" to "exit 1")) {
            val root = installation(body, version)
            val launcher = DesktopPetLauncher(mapOf("HOVEL_PET_PROJECT" to root.toString(),
                "HOVEL_PET_GODOT" to root.resolve("engine").toString()), root.resolve("pet.log"))
            try {
                assertEquals(PetState.ERROR, launcher.show().state)
                assertEquals(PetState.STOPPED, launcher.status().state)
            } finally { launcher.close(); root.toFile().deleteRecursively() }
        }
    }

    /** Navigating away during startup cancels and reaps the unfinished pet process. */
    @Test fun cancelsIncompleteLaunch() = runBlocking<Unit> {
        val root = installation("echo \$\$ > starts\nexec sleep 120")
        val launcher = DesktopPetLauncher(mapOf("HOVEL_PET_PROJECT" to root.toString(),
            "HOVEL_PET_GODOT" to root.resolve("engine").toString()), root.resolve("pet.log"))
        try {
            val job = launch(Dispatchers.IO) { launcher.show() }
            withTimeout(3000) { while (!Files.exists(root.resolve("starts"))) delay(20) }
            val child = ProcessHandle.of(Files.readString(root.resolve("starts")).trim().toLong()).get()
            job.cancelAndJoin()
            assertFalse(child.isAlive)
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }

    /** Hiding stops the pet's own child processes too, not only the engine. */
    @Test fun hidingStopsEverythingThePetStarted() = runBlocking<Unit> {
        val root = installation("sleep 120 &\necho \$! > helper\necho EMBER_PET_READY\nexec sleep 120")
        val launcher = DesktopPetLauncher(mapOf("HOVEL_PET_PROJECT" to root.toString(),
            "HOVEL_PET_GODOT" to root.resolve("engine").toString()), root.resolve("pet.log"))
        try {
            assertEquals(PetState.RUNNING, launcher.show().state)
            val helper = ProcessHandle.of(Files.readString(root.resolve("helper")).trim().toLong()).get()
            assertTrue(helper.isAlive)
            launcher.hide()
            withTimeout(3000) { while (helper.isAlive) delay(20) }
        } finally { launcher.close(); root.toFile().deleteRecursively() }
    }
}
