package org.formulagen.wrapper.pet

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.formulagen.wrapper.bridge.PetCapability
import org.formulagen.wrapper.bridge.PetCompanion
import org.formulagen.wrapper.bridge.PetLimits
import org.formulagen.wrapper.bridge.PetState
import org.formulagen.wrapper.bridge.PetStatus
import org.formulagen.wrapper.companion.terminateProcessTree

/** The `pet.*` capability: owns the desktop overlay independently of browser navigation and embedded games. */
internal class DesktopPetLauncher(
    private val environment: Map<String, String>,
    private val log: Path,
) : PetCapability, AutoCloseable {
    private val mutex = Mutex()
    private var process: Process? = null
    private var closed = false
    private val appearance = CompanionAppearanceStore(log.resolveSibling("companion-appearance.json"))

    /** Save the companion the pet shows; a hidden pet stays hidden. */
    override suspend fun configure(companion: PetCompanion): PetStatus = mutex.withLock {
        check(!closed) { "Pet launcher is closed" }
        appearance.save(companion)
        reply(if (process?.isAlive == true) PetState.RUNNING else PetState.STOPPED)
    }

    override suspend fun status(): PetStatus = inspect(start = false)
    override suspend fun show(): PetStatus = inspect(start = true)

    override suspend fun hide(): PetStatus = mutex.withLock {
        check(!closed) { "Pet launcher is closed" }
        stop()
        reply(PetState.STOPPED)
    }

    /** Report the installation's state, and start the pet when [start] asks for it. Native configuration picks every path. */
    private suspend fun inspect(start: Boolean): PetStatus = mutex.withLock {
        check(!closed) { "Pet launcher is closed" }
        if (process?.isAlive == true) return@withLock reply(PetState.RUNNING)
        process = null
        val project = environment["HOVEL_PET_PROJECT"]?.let(Path::of)
        val executable = environment["HOVEL_PET_GODOT"]?.let(Path::of)
        if (!System.getProperty("os.name").contains("Mac")) {
            return@withLock reply(PetState.UNAVAILABLE, "Ember desktop launch currently requires macOS.")
        }
        if (project == null || executable == null || !executable.toFile().canExecute() ||
            !project.resolve("project.godot").toFile().isFile ||
            !project.resolve("addons/jvm/jvm.gdextension").toFile().isFile ||
            !project.resolve("build/libs/ember-desktop-pet.jar").toFile().isFile) {
            return@withLock reply(PetState.UNAVAILABLE, "Prepare the Ember desktop pet, then restart Hovel.")
        }
        if (!start) return@withLock reply(PetState.STOPPED)
        try {
            val version = ProcessBuilder(executable.toString(), "--version").redirectErrorStream(true).start()
            try {
                check(version.waitFor(2, TimeUnit.SECONDS) && version.exitValue() == 0 &&
                    version.inputStream.bufferedReader().readText().trim().startsWith("4.7.2."))
                { "Ember requires its Godot 4.7.2 runtime." }
            } finally { if (version.isAlive) version.destroyForcibly() }
            log.parent.toFile().mkdirs()
            val child = ProcessBuilder(executable.toString(), "--path", project.toString(),
                "--", "-ApplePersistenceIgnoreState", "YES")
                .directory(project.toFile()).redirectErrorStream(true).redirectOutput(log.toFile())
                .apply {
                    environment().putAll(this@DesktopPetLauncher.environment)
                    environment()["HOVEL_COMPANION_APPEARANCE"] = appearance.path.toAbsolutePath().toString()
                }.start()
            process = child
            withTimeout(8_000) {
                while (true) {
                    check(child.isAlive) { "Ember could not start. Check the desktop pet log." }
                    if (log.toFile().readText().lineSequence().any { it.trim() == "EMBER_PET_READY" }) break
                    delay(50)
                }
            }
            reply(PetState.RUNNING)
        } catch (error: CancellationException) {
            stop()
            if (error is TimeoutCancellationException) reply(PetState.ERROR, "Ember did not become ready. Check the desktop pet log.")
            else throw error
        } catch (error: Exception) {
            stop()
            reply(PetState.ERROR, if (error is IllegalStateException) error.message ?: "Ember could not start."
                else "Ember could not start. Check the desktop pet log.")
        }
    }

    /** Stop only the child owned by this host and anything it started, including after incomplete startup. */
    private fun stop() {
        process?.let { terminateProcessTree(it) }
        process = null
    }

    /** Dispose after native requests are cancelled, outside the UI thread. */
    override fun close() = runBlocking { mutex.withLock { closed = true; stop() } }

    /** Keep process and filesystem details inside the native host. */
    private fun reply(state: PetState, message: String? = null): PetStatus {
        val saved = appearance.saved()
        return PetStatus(state, companionId = saved?.id, name = saved?.name, message = message?.take(PetLimits.MESSAGE))
    }
}
