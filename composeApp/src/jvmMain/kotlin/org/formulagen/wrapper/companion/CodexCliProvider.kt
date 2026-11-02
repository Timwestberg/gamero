package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.providers.codexProcess

/**
 * Memory jobs on the player's own Codex sign-in (Codex's default home), read-only in an empty private [workspace].
 * Authentication stays wholly in the player's installed Codex: Hovel checks `codex login status` before every job and
 * runs one only on a ChatGPT login. The job itself ignores the player's `config.toml` (`--ignore-user-config`), so its
 * read-only lockdown is the same whatever the player configured.
 *
 * Ignoring the configuration would also ignore where the player keeps the login
 * (`cli_auth_credentials_store`, `file` by default): a keyring-only login that `login status`
 * reports would be invisible to the job. So readiness asks under each store explicitly (the file,
 * then the keyring) and the job runs with the store its login was found in.
 */
class CodexCliProvider(private val executable: Path, workspace: Path, outputDirectory: Path) : TaskProvider {
    private val outputDirectory = outputDirectory.toAbsolutePath().normalize()
    private val workspace = workspace.toRealPath()
    init {
        require(Files.isExecutable(executable) && executable.isAbsolute)
        require(Files.isDirectory(this.workspace))
        Files.createDirectories(this.outputDirectory)
        Files.setPosixFilePermissions(this.outputDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
    }

    enum class Readiness { UNAVAILABLE, AUTH_REQUIRED, READY }

    /** Where the ChatGPT login was last found (`file` or `keyring`), which the job then reads. */
    @Volatile private var credentialStore: String? = null

    fun readiness(): Readiness {
        var answer = Readiness.UNAVAILABLE
        for (store in CREDENTIAL_STORES) {
            answer = loginStatus(store)
            if (answer == Readiness.READY) { credentialStore = store; return answer }
            if (answer == Readiness.UNAVAILABLE) break
        }
        credentialStore = null
        return answer
    }

    /** `codex login status` reading the login from [store]. */
    private fun loginStatus(store: String): Readiness {
        var process: Process? = null
        return try {
            val probe = builder(listOf("-c", "cli_auth_credentials_store=\"$store\"", "login", "status")).redirectErrorStream(true).start()
            process = probe
            val text = StringBuilder()
            val reader = Thread.ofPlatform().daemon().start {
                probe.inputStream.bufferedReader().use { input ->
                    val buffer = CharArray(512)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (text.length + count <= 4096) text.append(buffer, 0, count)
                    }
                }
            }
            if (!probe.waitFor(10, TimeUnit.SECONDS)) {
                Readiness.UNAVAILABLE
            } else {
                reader.join(1000)
                if (reader.isAlive) Readiness.UNAVAILABLE
                else if (probe.exitValue() == 0 && text.contains("Logged in using ChatGPT")) Readiness.READY
                else Readiness.AUTH_REQUIRED
            }
        } catch (_: Exception) { Readiness.UNAVAILABLE }
        finally { process?.let { terminateProcessTree(it) } }
    }

    override fun execute(request: ExecutionRequest): ProviderResult {
        check(readiness() == Readiness.READY) { "Codex ChatGPT sign-in is required" }
        val store = checkNotNull(credentialStore)
        require(java.util.UUID.fromString(request.id).toString() == request.id)
        val output = outputDirectory.resolve("${request.id}.txt")
        Files.createFile(output, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        run {
            // The login check above is the ChatGPT-only rule; builder() adds the environment rules.
            val process = builder(codexExecArguments(request, output, store)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val descendants = mutableSetOf<ProcessHandle>()
            try {
                val inputFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
                val writer = Thread.ofPlatform().daemon().start {
                    try {
                        process.outputStream.bufferedWriter().use {
                            it.write(request.prompt)
                        }
                    } catch (error: Exception) { inputFailure.set(error) }
                }
                val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(10)
                while (!process.waitFor(100, TimeUnit.MILLISECONDS)) {
                    process.descendants().use { handles -> handles.forEach { descendants.add(it) } }
                    check(System.nanoTime() < deadline) { "Provider execution timed out; reconcile on this computer" }
                    check(Files.size(output) <= 8L * 1024 * 1024) { "Provider output exceeds local journal limit" }
                }
                writer.join(1000)
                check(!writer.isAlive && inputFailure.get() == null) { "Provider stdin delivery failed" }
                check(Files.size(output) <= 8L * 1024 * 1024) { "Provider output exceeds local journal limit" }
                val text = Files.readString(output)
                return ProviderResult(process.exitValue() == 0,
                    text.ifEmpty { if (process.exitValue() == 0) "" else "Codex exited with code ${process.exitValue()}" })
            } finally {
                terminateProcessTree(process, descendants)
            }
        } // Retain output even on ambiguous failure for explicit local reconciliation.
    }

    private companion object {
        /** Where Codex may keep the login, in the order asked: its default, then the macOS keyring. */
        val CREDENTIAL_STORES = listOf("file", "keyring")
    }

    /** The player's own Codex login; [codexProcess] owns the rules. */
    internal fun builder(arguments: List<String>): ProcessBuilder = codexProcess(executable, arguments, workspace)
}

/**
 * Arguments for `codex exec`, deliberately read-only, reading the login from [store] (`file` or
 * `keyring`); [CodexCliProvider.builder] adds the environment rules in front. A request with a
 * [ProviderChoice] names its model (`exec -m`) and effort (`model_reasoning_effort`) unless they are
 * `runtime-default`; others run on Codex's own.
 */
internal fun codexExecArguments(request: ExecutionRequest, output: Path, store: String): List<String> {
    val choice = request.choice
    val effort = choice?.effort?.takeIf { it != ProviderChoice.RUNTIME_DEFAULT }
    val model = choice?.model?.takeIf { it != ProviderChoice.RUNTIME_DEFAULT }
    return listOf("-c", "cli_auth_credentials_store=\"$store\"", "-a", "never") +
        (if (effort != null) listOf("-c", "model_reasoning_effort=\"$effort\"") else emptyList()) +
        listOf("exec") + (if (model != null) listOf("-m", model) else emptyList()) +
        listOf("--ignore-user-config", "--ephemeral", "--sandbox", "read-only", "--skip-git-repo-check",
            "--color", "never", "--output-last-message", output.toString(), "-")
}
