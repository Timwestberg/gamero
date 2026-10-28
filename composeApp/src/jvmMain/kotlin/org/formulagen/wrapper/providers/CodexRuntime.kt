package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.formulagen.wrapper.bridge.SharedRules

/**
 * The player's installed Codex on the player's own sign-in, as every Codex child runs it: the
 * Providers card, companion chat and Hovel tasks. Codex keeps its login, configuration and history
 * in its default home (`$CODEX_HOME` when the player set it, else `~/.codex`); Hovel never writes
 * there and never signs in or out. [root] is the providers root, where Hovel keeps the empty
 * folders its own Codex children start in.
 */
class CodexRuntime(val executable: Path, val root: Path) {
    init { require(executable.isAbsolute) }

    /** `<providers root>/codex`: where account and model checks run. They belong to no profile: every profile shares the one Codex login. */
    fun statusFolder(): Path = private(root.resolve("codex"))

    /**
     * `<providers root>/<profile>/codex-chat`: empty and native-owned, so a companion's Codex
     * threads start apart from the player's projects.
     */
    fun conversationFolder(profile: String): Path {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        return private(private(root.resolve(profile)).resolve("codex-chat"))
    }

    /** A Codex child by [codexProcess]'s rules, started in [cwd]. */
    fun builder(arguments: List<String>, cwd: Path = statusFolder()): ProcessBuilder = codexProcess(executable, arguments, cwd)

    private fun private(folder: Path): Path = folder.also {
        Files.createDirectories(it)
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
    }
}

/**
 * The one place a Codex child process is built: the player's Codex, unmodified, on its default
 * home. Hovel adds no `-c` setting of its own here, on purpose:
 *
 * - `cli_auth_credentials_store="keyring"` would make Codex look for the login where the player's
 *   own setup may not keep it (a file-based `auth.json` then reads as signed out).
 * - `forced_login_method="chatgpt"` makes Codex *log out* any API-key login it finds ("ChatGPT
 *   login is required, but an API key is currently being used. Logging out."), which would sign
 *   the player out of their own Codex. Hovel checks the account type instead (`account/read`,
 *   `codex login status`) and refuses anything but a ChatGPT login.
 *
 * Talk-only chat and Hovel tasks add their own lockdown arguments on top (see `chatCodex` and
 * `CodexCliProvider`).
 */
internal fun codexProcess(executable: Path, arguments: List<String>, workspace: Path): ProcessBuilder =
    ProcessBuilder(listOf(executable.toString()) + arguments)
        .directory(workspace.toFile()).apply { scrubCodexEnvironment(environment()) }

/**
 * The environment half of [codexProcess], separate so it can be checked without a real environment.
 * Removed: `OPENAI_*`, `ACCESS_TOKEN` and `CODEX_API_KEY` (API-key billing instead of the player's
 * subscription), and every other `CODEX_*` variable, which only another Codex session sets
 * (`CODEX_SANDBOX*`, `CODEX_THREAD_ID`, …) and would make the child believe it runs inside that
 * session. Kept: `CODEX_HOME`, because the player's own Codex is wherever they pointed it (that is
 * Codex's default home), and `CODEX_CA_CERTIFICATE`, the player's network setup.
 */
internal fun scrubCodexEnvironment(environment: MutableMap<String, String>) {
    environment.keys.removeIf { it.startsWith("OPENAI_") || it == "ACCESS_TOKEN" ||
        (it.startsWith("CODEX_") && it !in KEPT_CODEX_VARIABLES) }
}

private val KEPT_CODEX_VARIABLES = setOf("CODEX_HOME", "CODEX_CA_CERTIFICATE")

/**
 * Hovel's old per-profile Codex home, `<providers root>/<profile>/codex`, from when Hovel signed
 * Codex in itself (its login lived in the keyring under this folder). Nothing signs in there any
 * more and Hovel leaves it on disk untouched; it is read only to migrate a version 1 chat record
 * that still lives inside it. A Codex thread started there cannot be resumed on the player's own
 * login (see `NativeConversation.playerLogin`).
 */
internal fun legacyCodexProfileHome(root: Path, profile: String): Path {
    require(SharedRules.isProfileId(profile)) { "Invalid profile" }
    return root.resolve(profile).resolve("codex")
}

/** Prefer explicit configuration, then an installed executable; never download a runtime. */
internal fun findCodexExecutable(environment: Map<String, String>): Path? {
    val configured = environment["GAMERO_CODEX_PATH"]
    val candidates = if (configured != null) listOf(configured) else
        environment["PATH"].orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }
            .map { Path.of(it).resolve("codex").toString() } + listOf(
            "/opt/homebrew/bin/codex", "/usr/local/bin/codex",
            "/Applications/ChatGPT.app/Contents/Resources/codex-cli/CodexCLI.app/Contents/MacOS/codex")
    return candidates.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
}
