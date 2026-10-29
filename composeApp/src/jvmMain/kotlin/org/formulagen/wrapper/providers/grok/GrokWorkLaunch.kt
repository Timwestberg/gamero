package org.formulagen.wrapper.providers.grok

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.tomlj.Toml

/** A per-turn tool profile over the player's original home, login, preferences and project rules. */
internal class GrokWorkLaunch private constructor(
    executable: Path,
    environment: Map<String, String>,
    workspace: Path,
    profile: Path,
    private val config: Path,
    private val home: Path,
) : GrokLaunchPolicy(executable, environment, workspace, profile) {
    /** The folder Grok trusts for [workspace]: its reported project root, which can be an enclosing repository. */
    var trustFolder: Path = workspace
        private set

    /** Grok's trust root is the player's home folder, a folder above it, or the disk: Hovel never asks to trust that. */
    val trustTooBroad: Boolean get() = trustFolder.parent == null || home.startsWith(trustFolder)

    /** As [verifyTrusted]; whether Grok trusts the project is a separate decision for the caller. */
    override fun verify(timeout: Duration) { verifyTrusted(timeout) }

    /**
     * Inspect using the runtime's resolver; unsupported configuration is refused, never rewritten. Sets [trustFolder]
     * to the root Grok reports, and is true when Grok already trusts it.
     */
    fun verifyTrusted(timeout: Duration): Boolean {
        val budget = GrokBudget(timeout)
        recheck()
        val report = inspectAcceptedRelease(budget)
        verifyGrokWorkInspection(report, config)
        trustFolder = projectRoot(report)
        check(workspace.startsWith(trustFolder)) { "Grok project trust scope could not be verified" }
        recheck()
        return report["projectTrusted"] == JsonPrimitive(true)
    }

    /**
     * Called only after an explicit native confirmation that names Grok's persistent trust of [confirmed].
     * Grok must report that same root afterwards; trust that landed on another folder is a failure.
     */
    fun trust(confirmed: Path, timeout: Duration) {
        recheck()
        val report = inspect(GrokBudget(timeout), listOf("--trust"), failure = "Grok folder trust could not be saved")
        verifyGrokWorkInspection(report, config)
        check(projectRoot(report) == confirmed) { "Grok trusted a different folder than the one confirmed" }
        check(report["projectTrusted"] == JsonPrimitive(true)) { "Grok folder trust was not established" }
    }

    /** The canonical root Grok reports, or the workspace itself when it reports none. */
    private fun projectRoot(report: JsonObject): Path =
        (report["projectRoot"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Path::of)?.toRealPath() ?: workspace

    /**
     * The player can edit their configuration at any time, so its accepted settings, including the parsed auth pin,
     * are read again between startup steps and inside every [verifyTrusted] before a turn.
     */
    override fun recheck() = verifyGrokWorkConfig(readGrokWorkConfig(config))

    /** The owning session must reap its process before removing the disposable profile ([closeAfter]). */
    override fun close() { Files.deleteIfExists(profile) }

    companion object {
        /**
         * Create only Hovel's temporary profile; never copy credentials or alter provider settings.
         * [loginPath] is the player's login-shell `PATH` ([grokWorkEnvironment]).
         */
        fun create(executable: Path, parent: Map<String, String>, workspace: Path, instructions: String,
            loginPath: String? = null): GrokWorkLaunch {
            val env = grokWorkEnvironment(parent, loginPath, executable)
            val home = Path.of(env.getValue("HOME"))
            val provider = Path.of(env.getValue("GROK_HOME"))
            val auth = Path.of(env.getValue("GROK_AUTH_PATH"))
            require(listOf(executable, home, provider, auth, workspace).all { it.isAbsolute })
            val config = grokWorkConfig(parent)
            verifyGrokWorkConfig(readGrokWorkConfig(config))
            val profile = Files.createTempFile("hovel-grok-work-", ".md",
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
            try {
                Files.writeString(profile, GROK_WORK_PROFILE + "\n" + instructions)
                return GrokWorkLaunch(executable, env, workspace.toRealPath(), profile, config,
                    runCatching { home.toRealPath() }.getOrDefault(home.normalize()))
            } catch (error: Exception) { Files.deleteIfExists(profile); throw error }
        }
    }
}

/**
 * A working child's whole environment: the player's own ([parent]), so their tools, locale, shell and
 * temporary folder work as in Terminal, with their login shell's `PATH` ([loginPath]: a Mac app started
 * from Finder has only the system folders), the Grok executable's folder first and the system folders
 * last. Removed: every `XAI_*` (an API key or another endpoint instead of the subscription) and every
 * inherited `GROK_*` (credentials, endpoints, overlays, auto-approval). The player's own `GROK_HOME`
 * and `GROK_AUTH_PATH` still choose where their setup and login are; Hovel then sets its own `GROK_*`.
 */
internal fun grokWorkEnvironment(parent: Map<String, String>, loginPath: String?, executable: Path): Map<String, String> {
    val home = parent["HOME"] ?: System.getProperty("user.home")
    val provider = grokProviderHome(parent)
    val auth = parent["GROK_AUTH_PATH"] ?: Path.of(provider).resolve("auth.json").toString()
    val kept = parent.filterKeys { !it.startsWith("XAI_") && !it.startsWith("GROK_") }
    val path = (listOfNotNull(executable.parent?.toString()) + (loginPath ?: parent["PATH"]).orEmpty().split(File.pathSeparator) +
        listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")).filter { it.isNotBlank() }.distinct()
    return kept + mapOf("HOME" to home, "PATH" to path.joinToString(File.pathSeparator), "GROK_HOME" to provider, "GROK_AUTH_PATH" to auth,
        "BROWSER" to "/usr/bin/false", GROK_DISABLE_AUTOUPDATER to "1", "GROK_SUBAGENTS" to "0", "GROK_GOAL" to "0", "GROK_WORKFLOWS" to "0")
}

/** The player's own Grok configuration, which only they edit: `$GROK_HOME/config.toml`, by default `~/.grok/config.toml`. */
internal fun grokWorkConfig(parent: Map<String, String>): Path = Path.of(grokProviderHome(parent)).resolve("config.toml")

private fun grokProviderHome(parent: Map<String, String>): String =
    parent["GROK_HOME"] ?: Path.of(parent["HOME"] ?: System.getProperty("user.home")).resolve(".grok").toString()

/** The player's Grok configuration does not pin account sign-in, and only the player may add that setting. */
internal class GrokWorkAuthUnpinned : IllegalStateException("Grok work requires an explicit OIDC auth preference")

/** Configuration contents stay local and are never included in errors or diagnostics. */
private fun readGrokWorkConfig(path: Path): String {
    Files.newInputStream(path).use { input ->
        val bytes = input.readNBytes(262_145)
        check(bytes.size <= 262_144) { "Grok configuration exceeds the supported size" }
        return bytes.toString(Charsets.UTF_8)
    }
}

/** Parse TOML properly: comments, quoted keys and multiline strings cannot masquerade as an auth pin. */
internal fun verifyGrokWorkConfig(text: String) {
    val config = Toml.parse(text)
    check(!config.hasErrors()) { "Grok configuration could not be verified" }
    if (config.getString("auth.preferred_method") != "oidc") throw GrokWorkAuthUnpinned()
    for (key in listOf("model", "model_providers", "auth_provider", "endpoints", "grok_com_config", "version_overrides", "campaigns"))
        check(!config.contains(key)) { "Grok configuration needs a separate work acceptance" }
    check(config.getBoolean("relay.enabled") != true) { "Grok relay needs a separate work acceptance" }
    check(!config.contains("toolset.bash.cmd_prefix")) { "Grok command prefix cannot be represented by native approvals" }
}

/** This slice preserves ordinary user preferences/rules, and refuses extra execution/configuration layers. */
internal fun verifyGrokWorkInspection(report: JsonObject, config: Path) {
    check(report["projectTrusted"] in setOf(JsonPrimitive(true), JsonPrimitive(false))) { "Missing Grok folder trust state" }
    for (key in listOf("hooks", "plugins", "mcpServers", "lspServers"))
        check((report[key] as? JsonArray)?.isEmpty() == true) { "Grok startup integration needs a separate work acceptance" }
    for (key in listOf("configWarnings", "mcpConfigProblems"))
        check(report[key] == null || (report[key] as? JsonArray)?.isEmpty() == true) { "Grok configuration warning" }
    check(report["projectInstructions"] is JsonArray && report["skills"] is JsonArray)
    verifyGrokConfigLayers(report, config)
    val permissions = report["permissions"] as? JsonObject ?: error("Missing Grok policy inspection")
    check(permissions["managedSettingsExists"] == JsonPrimitive(false))
    check(permissions["enforced"] == null || (permissions["enforced"] as? JsonArray)?.isEmpty() == true)
}

/** A nonempty tools allowlist without Agent directives forces background execution off in 1.0.70. */
private const val GROK_WORK_PROFILE = """---
name: hovel-work
description: Hovel project work with native approvals
promptMode: extend
agentsMd: true
discoverSkills: true
injectDefaultTools: false
tools: [read_file, list_dir, grep, search_replace, run_terminal_cmd]
toolConfig:
  tools:
    - id: GrokBuild:read_file
    - id: GrokBuild:list_dir
    - id: GrokBuild:grep
    - id: GrokBuild:search_replace
    - id: GrokBuild:run_terminal_cmd
      params:
        enabled_background: false
        auto_background_on_timeout: false
        allow_background_operator: false
maxTurns: 40
mcpInheritance: none
---
Work only on the person's request in the selected project. Commands must run in the foreground.
Do not start daemons, background processes, remote jobs or subagents. Respect denied operations.
"""
