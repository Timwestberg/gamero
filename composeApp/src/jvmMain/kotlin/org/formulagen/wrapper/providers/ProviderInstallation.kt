package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.ProviderSetup
import org.formulagen.wrapper.bridge.ProviderSetupState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.process.runBounded
import org.formulagen.wrapper.providers.grok.findGrokExecutable
import org.formulagen.wrapper.providers.grok.grokVersionSupported

/**
 * Provider-owned login, explicitly requested by the player. Discovery is repeated after installation;
 * no account reader, model request or login command runs during [setup]. Paths and arguments never
 * come from the page. Opening Terminal acknowledges a launch, not a successful sign-in.
 */
internal class ProviderInstallation(
    private val root: Path,
    private val environment: Map<String, String>,
    private val discover: (RuntimeId) -> Path? = { id -> id.setup.find(environment) },
    private val openTerminal: (Path) -> Boolean = { script ->
        runBounded(ProcessBuilder("/usr/bin/open", "-a", "Terminal", script.toString()), 5.seconds)?.exitCode == 0
    },
    private val mac: Boolean = System.getProperty("os.name").contains("Mac"),
) {
    fun setup(id: RuntimeId): ProviderSetup = inspect(id).first

    /** Recheck immediately before launch, so removal or an unsupported replacement cannot use stale readiness. */
    fun startLogin(id: RuntimeId): Boolean {
        val (status, executable) = inspect(id)
        if (status.state != ProviderSetupState.READY || executable == null) return false
        return guarded(false) {
            val folder = folder()
            val script = Files.createTempFile(folder, "sign-in-", ".command",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
            try {
                Files.writeString(script, loginScript(executable, id.setup.login, loginEnvironment(id, executable), folder))
                if (openTerminal(script)) true else { Files.deleteIfExists(script); false }
            } catch (error: Exception) {
                Files.deleteIfExists(script)
                throw error
            }
        }
    }

    private fun inspect(id: RuntimeId): Pair<ProviderSetup, Path?> = guarded(ProviderSetup(id, ProviderSetupState.UNAVAILABLE) to null) {
        if (!mac) return@guarded ProviderSetup(id, ProviderSetupState.UNAVAILABLE) to null
        val executable = discover(id)?.takeIf { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
            ?: return@guarded ProviderSetup(id, ProviderSetupState.NOT_INSTALLED) to null
        val setup = id.setup
        val folder = folder()
        fun probe(args: List<String>) = runBounded(ProcessBuilder(listOf(executable.toString()) + args).apply {
            directory(folder.toFile())
            environment().clear()
            environment().putAll(loginEnvironment(id, executable))
        }, 5.seconds)
        setup.accepts?.let { accepts ->
            val accepted = probe(listOf("--version"))?.takeIf { it.exitCode == 0 }?.let { accepts(it.output) }
                ?: return@guarded ProviderSetup(id, ProviderSetupState.UNAVAILABLE) to null
            if (!accepted) return@guarded ProviderSetup(id, ProviderSetupState.OUTDATED) to null
        }
        val help = probe(setup.helpFlags + setup.login + "--help")
            ?: return@guarded ProviderSetup(id, ProviderSetupState.UNAVAILABLE) to null
        val command = (listOf(setup.program) + setup.login).joinToString(" ")
        val supported = help.exitCode == 0 && Regex("(?im)^Usage: " + Regex.escape(command) + "(?: |$)").containsMatchIn(help.output)
        ProviderSetup(id, if (supported) ProviderSetupState.READY else ProviderSetupState.OUTDATED) to executable
    }

    private fun folder(): Path = root.resolve("sign-in").also {
        Files.createDirectories(it, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        require(!Files.isSymbolicLink(it))
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
    }

    /** Match each account reader's credential home. No API keys, tokens or endpoint overrides are inherited. */
    private fun loginEnvironment(id: RuntimeId, executable: Path): Map<String, String> {
        val setup = id.setup
        val keys = CHILD_ENV + setup.keep
        return environment.filterKeys { it in keys } + mapOf(
            "PATH" to (listOf(executable.parent.toString()) + environment["PATH"].orEmpty().split(":") +
                listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")).filter { it.isNotBlank() }.distinct().joinToString(":"),
            "TERM" to "xterm-256color",
        ) + setup.fixed
    }
}

/** Everything setup knows about one runtime; supporting another runtime is one entry in [setup]. */
private class RuntimeSetup(
    /** The installed executable: explicit configuration, then `PATH`, then the installers' locations. */
    val find: (Map<String, String>) -> Path?,
    /** The program name as the login's usage line prints it. */
    val program: String,
    /** The provider-owned login subcommand. */
    val login: List<String>,
    /** Variables kept from Hovel's environment besides [CHILD_ENV]: where the provider keeps its login. */
    val keep: List<String> = emptyList(),
    /** Variables set for every probe and for the login itself. */
    val fixed: Map<String, String> = emptyMap(),
    /** Global flags placed before the subcommand in the login help probe. */
    val helpFlags: List<String> = emptyList(),
    /** Whether `--version` printed an accepted release, or null when it cannot be read. Null accepts any release. */
    val accepts: ((String) -> Boolean?)? = null,
)

private val RuntimeId.setup: RuntimeSetup get() = when (this) {
    RuntimeId.CODEX -> RuntimeSetup({ findCodexExecutable(it) }, "codex", listOf("login"),
        keep = listOf("CODEX_HOME", "CODEX_CA_CERTIFICATE"))
    RuntimeId.CLAUDE_CODE -> RuntimeSetup({ findClaudeExecutable(it) }, "claude", listOf("auth", "login"),
        accepts = { output -> ClaudeVersion.parse(output)?.let { it >= MIN_CLAUDE_VERSION } })
    // Every Grok talk and work launch requires the exact accepted release, so another one cannot sign in here,
    // and neither the probes nor the login may update Grok away from it. The global flag goes before the
    // subcommand, so the help still reads "Usage: grok login".
    RuntimeId.GROK_BUILD -> RuntimeSetup({ findGrokExecutable(it) }, "grok", listOf("login"),
        keep = listOf("GROK_HOME"), fixed = mapOf("GROK_DISABLE_AUTOUPDATER" to "1"),
        helpFlags = listOf("--no-auto-update"), accepts = ::grokVersionSupported)
}

/** One shell-quoted argument; metacharacters and apostrophes in local paths cannot become shell code. */
private fun quoteArgument(value: String) = "'" + value.replace("'", "'\"'\"'") + "'"

/** Private self-removing launcher. The provider retains Terminal's TTY and owns all credential handling. */
internal fun loginScript(executable: Path, args: List<String>, environment: Map<String, String>, cwd: Path): String {
    val command = listOf("/usr/bin/env", "-i") + environment.map { (key, value) -> "$key=$value" } + executable.toString() + args
    return "#!/bin/sh\n/bin/rm -- \"\$0\"\ncd ${quoteArgument(cwd.toString())} || exit 1\nexec " +
        command.joinToString(" ", transform = ::quoteArgument) + "\n"
}

private inline fun <T> guarded(fallback: T, block: () -> T): T = try { block() } catch (error: Exception) {
    if (error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error
    fallback
}
