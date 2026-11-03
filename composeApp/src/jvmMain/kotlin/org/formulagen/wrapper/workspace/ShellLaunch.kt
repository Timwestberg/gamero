package org.formulagen.wrapper.workspace

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.isExecutable
import kotlin.io.path.isRegularFile
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.process.SYSTEM_PATH
import org.formulagen.wrapper.process.runBounded
import org.formulagen.wrapper.storage.privateFolder

/*
 * The argv and environment for each workspace tab (project-workspace.ARCH.md, "Shell integration").
 *
 * A tab starts from a minimal environment, never Hovel's own: the player's login shell then builds
 * the rest, as it does in Terminal. Hovel's PATH entries go first and stay first after the player's
 * startup files and before each prompt, through small startup files of Hovel's
 * (resources `workspace/shell/`, copied by [ShellFiles]). Hovel never edits the player's files.
 */

/** What one tab runs: the whole argv and the whole environment. */
data class ShellCommand(val argv: List<String>, val environment: Map<String, String>)

/**
 * The shells Hovel integrates with. Any other shell starts as a plain login shell, and its tool tabs
 * run through `/bin/sh`: its `-c` syntax is unknown.
 */
enum class ShellKind {
    ZSH, BASH, FISH, OTHER;

    companion object {
        /** By the executable's name, so `/opt/homebrew/bin/bash` (bash 5) is bash like `/bin/bash`. */
        fun of(shell: String): ShellKind = when (File(shell).name) {
            "zsh" -> ZSH
            "bash" -> BASH
            "fish" -> FISH
            else -> OTHER
        }
    }
}

/**
 * The fixed script a [Launch.Tool] runs in an sh-family shell: exec its arguments, which are the
 * tool and the arguments Hovel adds to it (Neovim's `--listen <socket>`).
 */
internal const val TOOL_SCRIPT = "exec \"\$@\""

/** The same for fish, whose arguments after `-c` arrive in `$argv`. */
internal const val FISH_TOOL_SCRIPT = "exec \$argv"

/** `$0` for [TOOL_SCRIPT], so the tool path is `$1` and nothing of it is part of the script. */
internal const val TOOL_ARG0 = "hovel-tool"

/** Runs [TOOL_SCRIPT] for a shell Hovel does not integrate with. Not a login shell: the tab's own environment as is. */
internal const val PLAIN_SH = "/bin/sh"

/**
 * The startup-file variables Hovel's files read and then unset: the entries to keep first (joined
 * with `:`); for zsh the player's own `ZDOTDIR` when Hovel was started with one; for fish Hovel's
 * startup file, so [FISH_INIT_COMMAND] stays a fixed string whatever the path holds.
 */
internal const val PATH_PREPEND_VARIABLE = "HOVEL_PATH_PREPEND"
internal const val USER_ZDOTDIR_VARIABLE = "HOVEL_USER_ZDOTDIR"
internal const val FISH_INIT_VARIABLE = "HOVEL_FISH_INIT"

/** fish's `--init-command`. Quoted, so an empty value is an error rather than `source` reading the terminal. */
internal const val FISH_INIT_COMMAND = "source \"\$$FISH_INIT_VARIABLE\""

/**
 * Builds each tab's [ShellCommand].
 *
 * [shell] is the player's login shell ([loginShell]); [parent] is Hovel's own environment, read
 * only for the few variables a tab inherits; [shellFiles] is where [ShellFiles.install] wrote
 * Hovel's startup files. Hovel's `PATH` entries come with each [command], since they differ by project.
 */
class ShellLaunch(
    val shell: String,
    private val parent: Map<String, String>,
    private val shellFiles: Path,
    private val userName: String = System.getProperty("user.name"),
    private val userHome: String = System.getProperty("user.home"),
) {
    val kind: ShellKind = ShellKind.of(shell)

    init {
        require(File(shell).isAbsolute) { "The login shell must be an absolute path" }
    }

    /**
     * The argv and environment for [launch]. [pathFirst] are Hovel's `PATH` entries for the tab's
     * project, in lookup order (the spike's one `bin` folder). [toolArguments] follow a tool's path as
     * separate arguments, never inside the script: the host's own (`--listen <socket>`), never a page's.
     */
    fun command(launch: Launch, pathFirst: List<String>, toolArguments: List<String> = emptyList()): ShellCommand {
        require(pathFirst.none { it.isEmpty() || ':' in it || !File(it).isAbsolute }) { "Hovel's PATH entries must be absolute and contain no ':'" }
        return when (launch) {
            Launch.Shell -> ShellCommand(shellArgv(), environment(pathFirst))
            is Launch.Tool -> ShellCommand(toolArgv(listOf(toolPath(launch.tool, pathFirst)) + toolArguments), environment(pathFirst))
            is Launch.Diff -> throw UnsupportedOperationException("Diff tabs arrive with the thread changes view")
        }
    }

    /** A Shell tab's argv: the login shell, with Hovel's startup file where it takes one. */
    private fun shellArgv(): List<String> = when (kind) {
        ShellKind.ZSH -> listOf(shell, "-l")
        ShellKind.BASH -> listOf(shell, "--init-file", bashFile(), "-i")
        ShellKind.FISH -> listOf(shell, "--login", "--init-command", FISH_INIT_COMMAND)
        ShellKind.OTHER -> listOf(shell, "-l")
    }

    /** [tool] is the tool's path and then its arguments. */
    private fun toolArgv(tool: List<String>): List<String> = when (kind) {
        ShellKind.ZSH -> listOf(shell, "-l", "-i", "-c", TOOL_SCRIPT, TOOL_ARG0) + tool
        ShellKind.BASH -> listOf(shell, "--init-file", bashFile(), "-i", "-c", TOOL_SCRIPT, TOOL_ARG0) + tool
        ShellKind.FISH -> listOf(shell, "--login", "--interactive", "--init-command", FISH_INIT_COMMAND, "-c", FISH_TOOL_SCRIPT, "--") + tool
        // No startup files: the base PATH only.
        ShellKind.OTHER -> listOf(PLAIN_SH, "-c", TOOL_SCRIPT, TOOL_ARG0) + tool
    }

    /**
     * The tool's wrapper in the first of Hovel's folders that has one; otherwise its command name,
     * found on the player's `PATH`. The tool manager writes a wrapper for every command, so the
     * fallback is the spike's way to run the player's installed tools.
     */
    private fun toolPath(tool: WorkspaceTool, pathFirst: List<String>): String {
        val command = tool.commands.first()
        return pathFirst.map { Path.of(it, command) }.firstOrNull { it.isRegularFile() && it.isExecutable() }?.toString() ?: command
    }

    /** Hovel's bash startup file. */
    private fun bashFile() = shellFiles.resolve(ShellFiles.BASH).toString()

    /** The whole environment of a tab. Nothing else from Hovel's process reaches it. */
    internal fun environment(pathFirst: List<String>): Map<String, String> = buildMap {
        put("HOME", parent["HOME"]?.takeIf { File(it).isAbsolute } ?: userHome)
        put("USER", parent["USER"]?.takeIf { it.isNotBlank() } ?: userName)
        put("LOGNAME", parent["LOGNAME"]?.takeIf { it.isNotBlank() } ?: userName)
        put("SHELL", shell)
        parent["TMPDIR"]?.takeIf { File(it).isAbsolute }?.let { put("TMPDIR", it) }
        // launchd's ssh-agent, as Terminal passes.
        parent["SSH_AUTH_SOCK"]?.takeIf { File(it).isAbsolute }?.let { put("SSH_AUTH_SOCK", it) }
        put("LANG", parent["LANG"]?.takeIf { it.isNotBlank() } ?: "en_US.UTF-8")
        put("PATH", (pathFirst + SYSTEM_PATH).distinct().joinToString(File.pathSeparator))
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("TERM_PROGRAM", "Hovel")
        put("HOVEL_WORKSPACE", "1")
        if (kind != ShellKind.OTHER) put(PATH_PREPEND_VARIABLE, pathFirst.joinToString(File.pathSeparator))
        when (kind) {
            ShellKind.ZSH -> {
                put("ZDOTDIR", shellFiles.resolve(ShellFiles.ZSH_FOLDER).toString())
                parent["ZDOTDIR"]?.takeIf { File(it).isAbsolute }?.let { put(USER_ZDOTDIR_VARIABLE, it) }
            }
            ShellKind.FISH -> put(FISH_INIT_VARIABLE, shellFiles.resolve(ShellFiles.FISH).toString())
            ShellKind.BASH, ShellKind.OTHER -> Unit
        }
    }
}

/**
 * The player's login shell: the directory service's `UserShell` ([directory], the output of
 * `dscl . -read /Users/<name> UserShell`), else `$SHELL`, else `/bin/zsh`. A candidate counts only
 * when it is an absolute path to an executable file.
 */
internal fun loginShell(directory: String?, environment: Map<String, String>, usable: (String) -> Boolean = ::isUsableShell): String =
    listOfNotNull(directory?.let(::parseUserShell), environment["SHELL"]).firstOrNull { File(it).isAbsolute && usable(it) } ?: "/bin/zsh"

/** The shell in `dscl` output: `UserShell: /bin/zsh`, or the value on the next line when dscl wraps it. */
internal fun parseUserShell(output: String): String? {
    val lines = output.lines()
    val index = lines.indexOfFirst { it.startsWith("UserShell:") }.takeIf { it >= 0 } ?: return null
    return lines[index].removePrefix("UserShell:").trim().ifEmpty { lines.getOrNull(index + 1)?.trim() }?.takeIf { it.isNotEmpty() }
}

/** Ask the directory service for this user's login shell. Null when dscl fails or takes too long. */
internal fun readDirectoryShell(userName: String = System.getProperty("user.name")): String? =
    runBounded(ProcessBuilder("/usr/bin/dscl", ".", "-read", "/Users/$userName", "UserShell"), 5.seconds, limit = 4 * 1024)
        ?.takeIf { it.exitCode == 0 }?.output

/** An executable file at an absolute path. */
internal fun isUsableShell(path: String): Boolean = File(path).isAbsolute && Path.of(path).let { it.isRegularFile() && it.isExecutable() }

/** `~/Library/Caches/<bundle id>/workspace`: Hovel's workspace files that need a path with no spaces. */
internal fun workspaceCacheFolder(home: String = System.getProperty("user.home")): Path =
    Path.of(home, "Library", "Caches", WORKSPACE_BUNDLE_ID, "workspace")

/** The bundle id of the packaged app (`compose.desktop.nativeDistributions.macOS.bundleID`). */
internal const val WORKSPACE_BUNDLE_ID = "org.formulagen.wrapper"

/**
 * Hovel's shell startup files, copied from resources `workspace/shell/` into
 * `~/Library/Caches/<bundle id>/workspace/shell`. Each shell gets its file's path as an argument or a
 * variable, never inside a command string, so any folder works.
 */
object ShellFiles {
    /** zsh's `ZDOTDIR`: Hovel's `.zshenv`, `.zprofile`, `.zshrc` and `.zlogin`. */
    const val ZSH_FOLDER = "zsh"
    const val BASH = "hovel.bash"
    const val FISH = "hovel.fish"

    /** Resource name to installed name. Resources carry no leading dot, so none are hidden in the repository. */
    private val FILES = mapOf(
        "zsh/zshenv" to "$ZSH_FOLDER/.zshenv",
        "zsh/zprofile" to "$ZSH_FOLDER/.zprofile",
        "zsh/zshrc" to "$ZSH_FOLDER/.zshrc",
        "zsh/zlogin" to "$ZSH_FOLDER/.zlogin",
        BASH to BASH,
        FISH to FISH,
    )

    /** `~/Library/Caches/<bundle id>/workspace/shell`. */
    fun defaultFolder(home: String = System.getProperty("user.home")): Path = workspaceCacheFolder(home).resolve("shell")

    /**
     * Write each file into [folder] unless it already holds the same bytes. A changed file is
     * replaced atomically, so a shell starting in another tab never reads half of one.
     */
    fun install(folder: Path): Path {
        privateFolder(folder.resolve(ZSH_FOLDER))
        for ((resource, name) in FILES) {
            val bytes = requireNotNull(ShellFiles::class.java.getResourceAsStream("/workspace/shell/$resource")) {
                "Missing resource workspace/shell/$resource"
            }.use { it.readBytes() }
            val target = folder.resolve(name)
            if (Files.isRegularFile(target) && Files.readAllBytes(target).contentEquals(bytes)) continue
            val temp = Files.createTempFile(target.parent, ".shell-", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.write(temp, bytes)
                Files.move(temp, target, ATOMIC_MOVE, REPLACE_EXISTING)
            } finally { Files.deleteIfExists(temp) }
        }
        return folder
    }
}
