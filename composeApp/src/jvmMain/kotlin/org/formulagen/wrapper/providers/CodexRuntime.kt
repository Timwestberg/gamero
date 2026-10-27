package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.formulagen.wrapper.bridge.SharedRules

/** Shared authentication settings for the account UI and approved Codex task execution. */
class CodexRuntime(val executable: Path, val home: Path) {
    /** Keep provider state private to the selected Hovel profile. */
    fun prepare() {
        require(executable.isAbsolute && Files.isExecutable(executable))
        Files.createDirectories(home)
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
    }

    /** A Codex child that keeps this profile's login in the keyring under [home]. */
    fun builder(arguments: List<String>, workspace: Path = home): ProcessBuilder = codexProcess(executable, arguments, workspace, home)
}

/**
 * The one place a Codex child process is built. Every child is ChatGPT sign-in only
 * (`forced_login_method`), and never inherits API-key billing (`OPENAI_*`, `ACCESS_TOKEN`) or
 * another Codex configuration (`CODEX_*`, except the CA certificate). With a [home] the login is
 * private to that folder and stored in the keyring; without one, the user's own `CODEX_HOME` is kept.
 */
internal fun codexProcess(executable: Path, arguments: List<String>, workspace: Path, home: Path? = null): ProcessBuilder =
    ProcessBuilder(listOf(executable.toString(), "-c", "forced_login_method=\"chatgpt\"") +
        (if (home != null) listOf("-c", "cli_auth_credentials_store=\"keyring\"") else emptyList()) + arguments)
        .directory(workspace.toFile()).apply { scrubCodexEnvironment(environment(), home) }

/** The environment half of [codexProcess], separate so it can be checked without a real environment. */
internal fun scrubCodexEnvironment(environment: MutableMap<String, String>, home: Path?) {
    environment.keys.removeIf { it.startsWith("OPENAI_") || it == "ACCESS_TOKEN" ||
        (it.startsWith("CODEX_") && it != "CODEX_CA_CERTIFICATE" && (home != null || it != "CODEX_HOME")) }
    home?.let { environment["CODEX_HOME"] = it.toAbsolutePath().toString() }
}

/** Profiles select fixed native-owned folders, never browser-supplied paths. */
internal fun codexProfileHome(root: Path, profile: String): Path {
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
