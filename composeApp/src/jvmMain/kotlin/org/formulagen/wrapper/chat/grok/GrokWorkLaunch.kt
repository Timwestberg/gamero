package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.formulagen.wrapper.process.runBounded
import org.tomlj.Toml

/** A per-turn tool profile over the player's original home, login, preferences and project rules. */
internal class GrokWorkLaunch private constructor(
    private val executable: Path,
    private val environment: Map<String, String>,
    val workspace: Path,
    private val profile: Path,
    private val config: Path,
    private val home: Path,
) : AutoCloseable {
    /** The folder Grok trusts for [workspace]: its reported project root, which can be an enclosing repository. */
    var trustFolder: Path = workspace
        private set

    /** Grok's trust root is the player's home folder, a folder above it, or the disk: Hovel never asks to trust that. */
    val trustTooBroad: Boolean get() = trustFolder.parent == null || home.startsWith(trustFolder)

    /** Inspect using the runtime's resolver; unsupported configuration is refused, never rewritten. */
    fun verify(timeout: Duration): Boolean {
        val budget = GrokBudget(timeout)
        checkConfig()
        val version = runBounded(builder(listOf("--version")), budget.remaining(), 256)
        check(version?.exitCode == 0 && grokVersionSupported(version.output)) { "Untested Grok version" }
        val result = runBounded(builder(listOf("--no-auto-update", "inspect", "--json")), budget.remaining())
        check(result?.exitCode == 0) { "Grok inspection unavailable" }
        val report = Json.parseToJsonElement(result.output).jsonObject
        verifyGrokWorkInspection(report, config)
        trustFolder = projectRoot(report)
        check(workspace.startsWith(trustFolder)) { "Grok project trust scope could not be verified" }
        checkConfig()
        return report["projectTrusted"] == JsonPrimitive(true)
    }

    /**
     * Called only after an explicit native confirmation that names Grok's persistent trust of [confirmed].
     * Grok must report that same root afterwards; trust that landed on another folder is a failure.
     */
    fun trust(confirmed: Path, timeout: Duration) {
        checkConfig()
        val result = runBounded(builder(listOf("--no-auto-update", "--trust", "inspect", "--json")), timeout)
        check(result?.exitCode == 0) { "Grok folder trust could not be saved" }
        val report = Json.parseToJsonElement(result.output).jsonObject
        verifyGrokWorkInspection(report, config)
        check(projectRoot(report) == confirmed) { "Grok trusted a different folder than the one confirmed" }
        check(report["projectTrusted"] == JsonPrimitive(true)) { "Grok folder trust was not established" }
    }

    /** The canonical root Grok reports, or the workspace itself when it reports none. */
    private fun projectRoot(report: JsonObject): Path =
        (report["projectRoot"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.let(Path::of)?.toRealPath() ?: workspace

    /** The parsed auth pin is checked before startup and again immediately before submitting a turn. */
    fun checkConfig() = verifyGrokWorkConfig(readGrokWorkConfig(config))

    /** A fixed tool allowlist also disables Grok's background shell and task lifecycle tooling. */
    fun agentBuilder() = builder(listOf("--no-auto-update", "--no-subagents", "--disable-web-search",
        "agent", "--no-leader", "--agent-profile", profile.toString(), "stdio"))

    /** Deliberately omit inherited credential, endpoint, overlay and auto-approval variables. */
    private fun builder(args: List<String>) = ProcessBuilder(listOf(executable.toString()) + args)
        .directory(workspace.toFile()).apply { environment().clear(); environment().putAll(environment) }

    /** The owning session must reap its process before removing the disposable profile. */
    override fun close() { Files.deleteIfExists(profile) }

    companion object {
        /** Create only Hovel's temporary profile; never copy credentials or alter provider settings. */
        fun create(executable: Path, parent: Map<String, String>, workspace: Path, instructions: String): GrokWorkLaunch {
            val home = Path.of(parent["HOME"] ?: System.getProperty("user.home"))
            val provider = Path.of(parent["GROK_HOME"] ?: home.resolve(".grok").toString())
            val auth = Path.of(parent["GROK_AUTH_PATH"] ?: provider.resolve("auth.json").toString())
            require(listOf(executable, home, provider, auth, workspace).all { it.isAbsolute })
            val config = provider.resolve("config.toml")
            verifyGrokWorkConfig(readGrokWorkConfig(config))
            val env = buildMap {
                put("HOME", home.toString()); put("GROK_HOME", provider.toString()); put("GROK_AUTH_PATH", auth.toString())
                put("PATH", parent["PATH"] ?: "/usr/bin:/bin:/usr/sbin:/sbin")
                put("BROWSER", "/usr/bin/false"); put("GROK_DISABLE_AUTOUPDATER", "1")
                put("GROK_SUBAGENTS", "0"); put("GROK_GOAL", "0"); put("GROK_WORKFLOWS", "0")
            }
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
    check(config.getString("auth.preferred_method") == "oidc") { "Grok work requires an explicit OIDC auth preference" }
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
