package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.process.runBounded

/** A private Grok configuration, referencing the runtime-owned login without reading or copying it. */
internal class GrokLaunch private constructor(
    val folder: Path,
    private val executable: Path,
    private val environment: Map<String, String>,
    private val policyHomes: List<Path>,
    val workspace: Path,
) : AutoCloseable {
    private val removed = java.util.concurrent.atomic.AtomicBoolean()
    private val home = folder.resolve("home")
    private val grokHome = home.resolve(".grok")
    private val profile = folder.resolve("talk.md")

    /** Only the tested runtime can use this auth pin and profile; future versions need a fresh acceptance run. */
    fun verify(timeout: Duration = 10.seconds) {
        val budget = GrokBudget(timeout)
        checkPolicies()
        val version = runBounded(builder(listOf("--version")), budget.remaining(), 256)
        check(version?.exitCode == 0 && grokVersionSupported(version.output)) { "Untested Grok version" }
        val inspected = runBounded(builder(listOf("--no-auto-update", "inspect", "--json")), budget.remaining())
        check(inspected?.exitCode == 0) { "Grok inspection unavailable" }
        verifyGrokInspection(Json.parseToJsonElement(inspected.output).jsonObject)
        checkPolicies()
    }

    /** A local ACP process, with explicit profile and no shared leader or client execution capabilities. */
    fun agentBuilder(): ProcessBuilder = builder(listOf("--no-auto-update", "--no-subagents", "--disable-web-search",
        "agent", "--no-leader", "--agent-profile", profile.toString(), "stdio"))

    /** Refuse managed installations rather than silently dropping their configuration from the isolated home. */
    fun checkPolicies() {
        check((policyHomes + grokHome).all { base -> POLICY_FILES.all { Files.notExists(base.resolve(it), NOFOLLOW_LINKS) } }) {
            "Grok managed configuration needs an isolation policy"
        }
    }

    /** Clear inherited endpoints, credentials, startup imports and shell customization. */
    private fun builder(arguments: List<String>): ProcessBuilder = ProcessBuilder(listOf(executable.toString()) + arguments)
        .directory(workspace.toFile()).apply { environment().clear(); environment().putAll(environment) }

    /** The owner must close and reap its ACP process before removing the private configuration. */
    override fun close() { if (removed.compareAndSet(false, true)) removeGrokLaunchFolder(folder) }

    /** An interrupted startup still reaps the child before removing its configuration. */
    fun closeAfter(rpc: GrokAcp?) {
        val interrupted = Thread.interrupted()
        try { rpc?.close(); rpc?.awaitClosed(); close() }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    companion object {
        /** Create a new isolated session home; neither this factory nor its cleanup opens the credential store. */
        fun create(executable: Path, parent: Map<String, String>, instructions: String,
            systemPolicyHome: Path = Path.of("/etc/grok"), persistentFolder: Path? = null): GrokLaunch {
            val realHome = Path.of(parent["HOME"] ?: System.getProperty("user.home"))
            val providerHome = Path.of(parent["GROK_HOME"] ?: realHome.resolve(".grok").toString())
            val auth = Path.of(parent["GROK_AUTH_PATH"]?.takeIf(String::isNotBlank) ?: providerHome.resolve("auth.json").toString())
            require(executable.isAbsolute && realHome.isAbsolute && providerHome.isAbsolute && auth.isAbsolute)
            persistentFolder?.also { require(it.isAbsolute); privateDirectory(it) }
            val folder = Files.createTempDirectory("hovel-grok-isolated-", directoryPermissions)
            try {
                val home = privateDirectory(folder.resolve("home"))
                val grokHome = privateDirectory(home.resolve(".grok"))
                val workspace = privateDirectory((persistentFolder ?: folder).resolve("workspace"))
                // Persist only provider session data. Downloaded bundles and changed preferences are per process.
                if (persistentFolder != null) Files.createSymbolicLink(grokHome.resolve("sessions"), privateDirectory(persistentFolder.resolve("sessions")))
                Files.writeString(grokHome.resolve("config.toml"), GROK_ISOLATED_CONFIG)
                Files.writeString(folder.resolve("talk.md"), GROK_TALK_PROFILE + "\n" + instructions, java.nio.file.StandardOpenOption.CREATE_NEW)
                val env = buildMap {
                    put("HOME", home.toString()); put("GROK_HOME", grokHome.toString()); put("GROK_AUTH_PATH", auth.toString())
                    put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin"); put("BROWSER", "/usr/bin/false")
                    put("GROK_DISABLE_AUTOUPDATER", "1"); put("GROK_MEMORY", "0"); put("GROK_GOAL", "0"); put("GROK_WORKFLOWS", "0")
                    for (brand in listOf("CURSOR", "CLAUDE")) for (feature in listOf("SKILLS", "RULES", "AGENTS", "MCPS", "HOOKS", "SESSIONS"))
                        put("GROK_${brand}_${feature}_ENABLED", "0")
                    put("GROK_CODEX_SESSIONS_ENABLED", "0")
                }
                return GrokLaunch(folder, executable, env, listOf(providerHome, systemPolicyHome), workspace).also { it.checkPolicies() }
            } catch (error: Exception) { removeGrokLaunchFolder(folder); throw error }
        }

        /** Never follow a replaced private directory or silently overwrite unexpected runtime configuration. */
        private fun privateDirectory(path: Path): Path = path.also {
            Files.createDirectories(it)
            check(Files.isDirectory(it, NOFOLLOW_LINKS))
            Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
        }


    }
}

/** Do not follow the session-directory link when deleting a disposable configuration home. */
private fun removeGrokLaunchFolder(folder: Path) {
    Files.walkFileTree(folder, object : java.nio.file.SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attributes: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
            Files.deleteIfExists(file)
            return java.nio.file.FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(directory: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
            if (error != null) throw error
            Files.deleteIfExists(directory)
            return java.nio.file.FileVisitResult.CONTINUE
        }
    })
}

/** Require known discovery arrays and reject warnings rather than assuming an absent field means empty. */
internal fun verifyGrokInspection(report: JsonObject) {
    for (key in listOf("projectInstructions", "hooks", "skills", "plugins", "mcpServers", "lspServers"))
        check((report[key] as? JsonArray)?.isEmpty() == true) { "Grok discovery is not isolated: $key" }
    for (key in listOf("configWarnings", "mcpConfigProblems"))
        check(report[key] == null || (report[key] as? JsonArray)?.isEmpty() == true) { "Grok configuration was not accepted" }
    val layers = (report["configSources"] as? JsonObject)?.get("layers") as? JsonArray ?: error("Missing configuration sources")
    check(layers.all { entry ->
        val layer = entry as? JsonObject ?: return@all false
        layer["role"] == JsonPrimitive("user") && layer["note"] == null || layer["note"] == JsonPrimitive("empty")
    }) { "Unexpected Grok configuration source" }
    val permissions = report["permissions"] as? JsonObject ?: error("Missing policy inspection")
    check(permissions["managedSettingsExists"] == JsonPrimitive(false)) { "Managed Grok policy needs preservation" }
    check(permissions["enforced"] == null || (permissions["enforced"] as? JsonArray)?.isEmpty() == true)
}

private val directoryPermissions = PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))
private val POLICY_FILES = listOf("requirements.toml", "managed_config.toml")
private const val GROK_ISOLATED_CONFIG = """[auth]
preferred_method = "oidc"
[skills]
ignore = ["/"]
[subagents]
enabled = false
[relay]
enabled = false
"""

/** Empty `tools` means inherit in Grok. Declare one known tool, then deny it before registry creation. */
private const val GROK_TALK_PROFILE = """---
name: hovel-talk
description: Hovel isolated conversation
promptMode: full
discoverSkills: false
inheritSkills: false
agentsMd: false
injectDefaultTools: false
toolConfig:
  tools:
    - id: GrokBuild:read_file
disallowedTools: [read_file, search_tool, use_tool, Agent]
maxTurns: 1
mcpInheritance: none
---
"""
