package org.formulagen.wrapper.pet

import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.formulagen.wrapper.companion.terminateProcessTree

/** Owns the desktop overlay independently of browser navigation and embedded games. */
internal class DesktopPetLauncher(
    private val environment: Map<String, String>,
    private val log: Path,
) : AutoCloseable {
    private val mutex = Mutex()
    private var process: Process? = null
    private var closed = false
    private val appearance = CompanionAppearanceStore(log.resolveSibling("companion-appearance.json"))

    /** Page requests select an action, never an executable, project or command line. */
    suspend fun dispatch(method: String, args: JsonObject): JsonObject = mutex.withLock {
        check(!closed) { "Pet launcher is closed" }
        if (method == "pet.configure") {
            appearance.save(args)
            return@withLock reply(if (process?.isAlive == true) "running" else "stopped")
        }
        require(method in setOf("pet.status", "pet.show", "pet.hide") && args.isEmpty()) { "Unknown pet action" }
        if (method == "pet.hide") {
            stop()
            return@withLock reply("stopped")
        }
        if (process?.isAlive == true) return@withLock reply("running")
        process = null
        val project = environment["HOVEL_PET_PROJECT"]?.let(Path::of)
        val executable = environment["HOVEL_PET_GODOT"]?.let(Path::of)
        if (!System.getProperty("os.name").contains("Mac")) {
            return@withLock reply("unavailable", "Ember desktop launch currently requires macOS.")
        }
        if (project == null || executable == null || !executable.toFile().canExecute() ||
            !project.resolve("project.godot").toFile().isFile ||
            !project.resolve("addons/jvm/jvm.gdextension").toFile().isFile ||
            !project.resolve("build/libs/ember-desktop-pet.jar").toFile().isFile) {
            return@withLock reply("unavailable", "Prepare the Ember desktop pet, then restart Hovel.")
        }
        if (method == "pet.status") return@withLock reply("stopped")
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
            reply("running")
        } catch (error: CancellationException) {
            stop()
            if (error is TimeoutCancellationException) reply("error", "Ember did not become ready. Check the desktop pet log.")
            else throw error
        } catch (error: Exception) {
            stop()
            reply("error", if (error is IllegalStateException) error.message ?: "Ember could not start."
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
    private fun reply(state: String, message: String? = null) = buildJsonObject {
        put("state", state)
        appearance.identity()?.let { profile ->
            profile["id"]?.let { put("companionId", it) }
            profile["name"]?.let { put("name", it) }
        }
        message?.let { put("message", it) }
    }
}
