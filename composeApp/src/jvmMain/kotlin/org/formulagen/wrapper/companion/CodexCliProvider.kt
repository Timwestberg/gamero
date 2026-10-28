package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.providers.CodexRuntime
import org.formulagen.wrapper.providers.codexProcess

/** Provider authentication remains wholly in supported, user-installed Codex tooling. */
class CodexCliProvider(private val executable: Path, workspace: Path, outputDirectory: Path, private val codexHome: Path? = null) : TaskProvider {
    private val outputDirectory = outputDirectory.toAbsolutePath().normalize()
    private val workspace = workspace.toRealPath()
    init {
        require(Files.isExecutable(executable) && executable.isAbsolute)
        require(Files.isDirectory(this.workspace))
        Files.createDirectories(this.outputDirectory)
        Files.setPosixFilePermissions(this.outputDirectory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"))
    }

    enum class Readiness { UNAVAILABLE, AUTH_REQUIRED, READY }

    fun readiness(): Readiness {
        var process: Process? = null
        return try {
            val probe = builder(listOf("login", "status")).redirectErrorStream(true).start()
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
        require(java.util.UUID.fromString(request.id).toString() == request.id)
        val output = outputDirectory.resolve("${request.id}.txt")
        Files.createFile(output, java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")))
        run {
            val process = builder(codexExecArguments(request, output)).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val descendants = mutableSetOf<ProcessHandle>()
            try {
                val inputFailure = java.util.concurrent.atomic.AtomicReference<Exception?>()
                val writer = Thread.ofPlatform().daemon().start {
                    try {
                        process.outputStream.bufferedWriter().use {
                            it.write(providerPrompt(request))
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

    /** The profile's isolated login when there is one, else the user's own Codex login; [codexProcess] owns the rules. */
    internal fun builder(arguments: List<String>): ProcessBuilder = codexHome?.let {
        CodexRuntime(executable, it).also { runtime -> runtime.prepare() }.builder(arguments, workspace)
    } ?: codexProcess(executable, arguments, workspace)
}

/**
 * The text a provider is given on stdin. A task's is byte for byte event-contracts `hovelProviderPrompt`:
 * the character section, then recalled memory when there is some, then the task (pinned by
 * HovelProviderPromptTest). A memory job's input is already the whole text.
 */
internal fun providerPrompt(request: ExecutionRequest): String =
    if (request.extraction != null) request.prompt
    else "Platform-owned character context (user-authored):\n${request.context}\n\n" +
        request.recalledMemory?.let { "$it\n\n" }.orEmpty() + "User task:\n${request.prompt}"

/**
 * Arguments for `codex exec`; [CodexCliProvider.builder] adds the ChatGPT-only login and environment
 * rules in front. Deliberately read-only. A task runs on Codex's own model and effort; a memory job
 * names its model (`exec -m`) and effort (`model_reasoning_effort`) unless they are `runtime-default`.
 */
internal fun codexExecArguments(request: ExecutionRequest, output: Path): List<String> {
    val choice = request.extraction?.also { require(it.isValid()) { "Memory job model or effort is invalid" } }
    val effort = choice?.effort?.takeIf { it != ProviderChoice.RUNTIME_DEFAULT }
    val model = choice?.model?.takeIf { it != ProviderChoice.RUNTIME_DEFAULT }
    return listOf("-a", "never") +
        (if (effort != null) listOf("-c", "model_reasoning_effort=\"$effort\"") else emptyList()) +
        listOf("exec") + (if (model != null) listOf("-m", model) else emptyList()) +
        listOf("--ignore-user-config", "--ephemeral", "--sandbox", "read-only", "--skip-git-repo-check",
            "--color", "never", "--output-last-message", output.toString(), "-")
}
