package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

/** Shared authentication settings for the account UI and approved Codex task execution. */
class CodexRuntime(val executable: Path, val home: Path) {
    /** Keep provider state private to the selected Hovel profile. */
    fun prepare() {
        require(executable.isAbsolute && Files.isExecutable(executable))
        Files.createDirectories(home)
        Files.setPosixFilePermissions(home, PosixFilePermissions.fromString("rwx------"))
    }

    /** Apply supported keyring storage without inheriting API billing or another Codex login. */
    fun builder(arguments: List<String>, workspace: Path = home): ProcessBuilder =
        ProcessBuilder(listOf(executable.toString(), "-c", "cli_auth_credentials_store=\"keyring\"",
            "-c", "forced_login_method=\"chatgpt\"") + arguments).directory(workspace.toFile()).apply {
            environment().keys.removeIf { it.startsWith("OPENAI_") ||
                (it.startsWith("CODEX_") && it != "CODEX_CA_CERTIFICATE") || it == "ACCESS_TOKEN" }
            environment()["CODEX_HOME"] = home.toAbsolutePath().toString()
        }
}

/** Profiles select fixed native-owned folders, never browser-supplied paths. */
internal fun codexProfileHome(root: Path, profile: String): Path {
    require(profile == "local" || runCatching { UUID.fromString(profile).toString() == profile }.getOrDefault(false))
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
