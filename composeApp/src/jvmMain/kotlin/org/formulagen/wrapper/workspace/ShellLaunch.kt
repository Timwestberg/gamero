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

/** The shells Hovel integrates with. Any other shell starts as a plain login shell. */
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

/** The system folders every tab's base `PATH` ends with. The login shell adds the rest. */
internal val BASE_SYSTEM_PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")

/** The fixed script a [Launch.Tool] runs in an sh-family shell: exec its first argument, which is the tool. */
internal const val TOOL_SCRIPT = "exec \"\$1\""

/** The same for fish, whose arguments after `-c` arrive in `$argv`. */
internal const val FISH_TOOL_SCRIPT = "exec \$argv[1]"

/** `$0` for [TOOL_SCRIPT], so the tool path is `$1` and never part of the script. */
internal const val TOOL_ARG0 = "hovel-tool"

/**
 * The startup-file variables Hovel's files read and then unset: the entries to keep first (joined
 * with `:`), and for zsh the player's own `ZDOTDIR` when Hovel was started with one.
 */
internal const val PATH_PREPEND_VARIABLE = "HOVEL_PATH_PREPEND"
internal const val USER_ZDOTDIR_VARIABLE = "HOVEL_USER_ZDOTDIR"

/**
 * Builds each tab's [ShellCommand].
 *
 * [shell] is the player's login shell ([loginShell]); [parent] is Hovel's own environment, read
 * only for the few variables a tab inherits; [shellFiles] is where [ShellFiles.install] wrote
 * Hovel's startup files; [pathFirst] are Hovel's entries, in the spec's lookup order (the project's
 * trusted tool bins, then `workspace/bin`). For the spike, `pathFirst` is one test `bin` folder.
 */
class ShellLaunch(
    val shell: String,
    private val parent: Map<String, String>,
    private val shellFiles: Path,
    private val pathFirst: List<String>,
    private val userName: String = System.getProperty("user.name"),
    private val userHome: String = System.getProperty("user.home"),
) {
    val kind: ShellKind = ShellKind.of(shell)

    init {
        require(File(shell).isAbsolute) { "The login shell must be an absolute path" }
        // fish takes its startup file inside a command string. The Caches folder has no spaces or quotes,
        // and refusing anything else here keeps that string fixed.
        require(SAFE_PATH.matches(shellFiles.toString())) { "Hovel's shell folder must be a plain path: $shellFiles" }
        require(pathFirst.none { it.isEmpty() || ':' in it || !File(it).isAbsolute }) { "Hovel's PATH entries must be absolute and contain no ':'" }
    }

    /** The argv and environment for [launch]. The working folder is the session's, set by the host. */
    fun command(launch: Launch): ShellCommand = when (launch) {
        Launch.Shell -> ShellCommand(shellArgv(), environment())
        is Launch.Tool -> ShellCommand(toolArgv(toolPath(launch.tool)), environment())
        is Launch.Diff -> throw UnsupportedOperationException("Diff tabs arrive with the thread changes view")
    }

    private fun shellArgv(): List<String> = when (kind) {
        ShellKind.ZSH -> listOf(shell, "-l")
        ShellKind.BASH -> listOf(shell, "--init-file", bashFile(), "-i")
        ShellKind.FISH -> listOf(shell, "--login", "--init-command", fishInit())
        ShellKind.OTHER -> listOf(shell, "-l")
    }

    private fun toolArgv(tool: String): List<String> = when (kind) {
        ShellKind.ZSH, ShellKind.OTHER -> listOf(shell, "-l", "-i", "-c", TOOL_SCRIPT, TOOL_ARG0, tool)
        ShellKind.BASH -> listOf(shell, "--init-file", bashFile(), "-i", "-c", TOOL_SCRIPT, TOOL_ARG0, tool)
        ShellKind.FISH -> listOf(shell, "--login", "--interactive", "--init-command", fishInit(), "-c", FISH_TOOL_SCRIPT, "--", tool)
    }

    /**
     * The tool's wrapper in the first of Hovel's folders that has one; otherwise its command name,
     * found on the player's `PATH`. The tool manager writes a wrapper for every command, so the
     * fallback is the spike's way to run the player's installed tools.
     */
    private fun toolPath(tool: WorkspaceTool): String {
        val command = tool.commands.first()
        return pathFirst.map { Path.of(it, command) }.firstOrNull { it.isRegularFile() && it.isExecutable() }?.toString() ?: command
    }

    private fun bashFile() = shellFiles.resolve(ShellFiles.BASH).toString()

    private fun fishInit() = "source '${shellFiles.resolve(ShellFiles.FISH)}'"

    /** The whole environment of a tab. Nothing else from Hovel's process reaches it. */
    internal fun environment(): Map<String, String> = buildMap {
        put("HOME", parent["HOME"]?.takeIf { File(it).isAbsolute } ?: userHome)
        put("USER", parent["USER"]?.takeIf { it.isNotBlank() } ?: userName)
        put("LOGNAME", parent["LOGNAME"]?.takeIf { it.isNotBlank() } ?: userName)
        put("SHELL", shell)
        parent["TMPDIR"]?.takeIf { File(it).isAbsolute }?.let { put("TMPDIR", it) }
        put("LANG", parent["LANG"]?.takeIf { it.isNotBlank() } ?: "en_US.UTF-8")
        put("PATH", (pathFirst + BASE_SYSTEM_PATH).distinct().joinToString(File.pathSeparator))
        put("TERM", "xterm-256color")
        put("COLORTERM", "truecolor")
        put("TERM_PROGRAM", "Hovel")
        put("HOVEL_WORKSPACE", "1")
        if (kind != ShellKind.OTHER) put(PATH_PREPEND_VARIABLE, pathFirst.joinToString(File.pathSeparator))
        if (kind == ShellKind.ZSH) {
            put("ZDOTDIR", shellFiles.resolve(ShellFiles.ZSH_FOLDER).toString())
            parent["ZDOTDIR"]?.takeIf { File(it).isAbsolute }?.let { put(USER_ZDOTDIR_VARIABLE, it) }
        }
    }

    private companion object {
        val SAFE_PATH = Regex("/[A-Za-z0-9._/-]+")
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

private fun isUsableShell(path: String): Boolean = Path.of(path).let { it.isRegularFile() && it.isExecutable() }

/**
 * Hovel's shell startup files, copied from resources `workspace/shell/` into a folder with no spaces
 * in its path (`~/Library/Caches/<bundle id>/workspace/shell`, not Application Support).
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

    /** The bundle id of the packaged app (`compose.desktop.nativeDistributions.macOS.bundleID`). */
    private const val BUNDLE_ID = "org.formulagen.wrapper"

    /** `~/Library/Caches/<bundle id>/workspace/shell`. */
    fun defaultFolder(home: String = System.getProperty("user.home")): Path =
        Path.of(home, "Library", "Caches", BUNDLE_ID, "workspace", "shell")

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
