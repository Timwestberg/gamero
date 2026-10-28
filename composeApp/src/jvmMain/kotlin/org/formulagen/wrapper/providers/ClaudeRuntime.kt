package org.formulagen.wrapper.providers

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.companion.terminateProcessTree

/** A Claude Code release, compared part by part. */
internal data class ClaudeVersion(val major: Int, val minor: Int, val patch: Int) : Comparable<ClaudeVersion> {
    override fun compareTo(other: ClaudeVersion) = compareValuesBy(this, other, { it.major }, { it.minor }, { it.patch })
    override fun toString() = "$major.$minor.$patch"

    companion object {
        /** The leading `major.minor.patch` of `claude --version` ("2.1.304 (Claude Code)"), or null. */
        fun parse(text: String): ClaudeVersion? = Regex("^\\s*(\\d{1,6})\\.(\\d{1,6})\\.(\\d{1,9})(?!\\d)").find(text)
            ?.destructured?.let { (major, minor, patch) -> ClaudeVersion(major.toInt(), minor.toInt(), patch.toInt()) }
    }
}

/**
 * The oldest Claude Code proven to have everything a locked-down turn needs: `--safe-mode`,
 * `--permission-prompts`, `--disable-slash-commands`, `auth status --json` and `terminal_reason`
 * (runtime spike, 2026-10-27).
 */
internal val MIN_CLAUDE_VERSION = ClaudeVersion(2, 1, 304)
internal val CLAUDE_STATUS_TIMEOUT: Duration = 10.seconds

internal data class ClaudeModel(val modelId: String, val displayName: String)

/** The models a Claude team member may use, by Claude Code alias. */
internal val CLAUDE_MODELS = listOf(ClaudeModel("sonnet", "Sonnet"), ClaudeModel("opus", "Opus"), ClaudeModel("haiku", "Haiku"))

/** The model for a turn that names none; the page normally resolves "provider default" to the profile's choice first. */
internal const val CLAUDE_FALLBACK_MODEL = "sonnet"

/** Copied from Hovel's own environment when present. Nothing else is: not `ANTHROPIC_*` (billing, endpoints) nor `CLAUDE_*` (another session). */
internal val CHILD_ENV = listOf("HOME", "USER", "LOGNAME", "TMPDIR", "LANG")
private val SYSTEM_PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")
private val CLAUDE_SETTINGS = mapOf("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC" to "1", "CLAUDE_CODE_DISABLE_AUTO_MEMORY" to "1")

/** Release builds never offer Claude, whatever the environment says, until Anthropic confirms a companion persona on a player's own subscription is allowed. */
internal fun claudeProviderEnabled(env: Map<String, String>, release: Boolean): Boolean = !release && env["HOVEL_CLAUDE_PROVIDER"] == "1"

/**
 * The player's installed Claude Code, run unmodified on its own sign-in. [workspaceRoot] is the
 * providers root; each profile's chats run in an empty folder under it. Hovel never runs sign-in
 * or sign-out, and never reads Claude Code's credentials.
 */
internal class ClaudeRuntime(
    val executable: Path,
    val workspaceRoot: Path,
    private val parentEnvironment: Map<String, String> = System.getenv(),
) {
    /** A Claude Code child with [claudeChildEnvironment] only: the inherited environment is cleared first. */
    fun builder(args: List<String>, cwd: Path): ProcessBuilder =
        ProcessBuilder(listOf(executable.toString()) + args).directory(cwd.toFile()).apply {
            environment().clear()
            environment().putAll(claudeChildEnvironment(parentEnvironment, executable))
        }

    /** `claude --version`, or null when it does not run, fails or prints something else. */
    fun version(timeout: Duration = CLAUDE_STATUS_TIMEOUT): ClaudeVersion? =
        runBounded(builder(listOf("--version"), statusFolder()), timeout)?.takeIf { it.exitCode == 0 }?.let { ClaudeVersion.parse(it.output) }

    /** `<providers root>/<profile>/claude-code/conversation-workspace`: empty and native-owned, so Claude Code's sessions stay apart from the player's projects. */
    fun workspace(profile: String): Path {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        val home = private(workspaceRoot.resolve(profile).resolve("claude-code"))
        return private(home.resolve("conversation-workspace"))
    }

    /** Where status checks run. They belong to no profile: every profile shares the one Claude Code sign-in. */
    fun statusFolder(): Path = private(workspaceRoot.resolve("claude-code"))

    private fun private(folder: Path): Path = folder.also {
        Files.createDirectories(it)
        Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------"))
    }
}

/**
 * The whole environment of a Claude Code child: [CHILD_ENV] from [parent], a fixed `PATH` (the
 * executable's own folder first, since an npm-installed `claude` starts through `env node`), and
 * the two settings that turn off nonessential traffic and auto memory.
 */
internal fun claudeChildEnvironment(parent: Map<String, String>, executable: Path): Map<String, String> = buildMap {
    CHILD_ENV.forEach { key -> parent[key]?.let { put(key, it) } }
    putIfAbsent("HOME", System.getProperty("user.home"))
    put("PATH", (listOfNotNull(executable.parent?.toString()) + SYSTEM_PATH).distinct().joinToString(File.pathSeparator))
    putAll(CLAUDE_SETTINGS)
}

/** Explicit configuration, then `PATH`, then the installer's and Homebrew's locations. Never downloads Claude Code. */
internal fun findClaudeExecutable(
    environment: Map<String, String>,
    fixed: List<String> = listOf("/opt/homebrew/bin/claude", "/usr/local/bin/claude"),
): Path? {
    val configured = environment["GAMERO_CLAUDE_PATH"]
    val home = environment["HOME"] ?: System.getProperty("user.home")
    val candidates = if (configured != null) listOf(configured) else
        environment["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/claude" } +
            "$home/.local/bin/claude" + fixed
    return candidates.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
}

internal data class BoundedOutput(val exitCode: Int, val output: String)

/**
 * Run a short child with no input and stderr discarded, keeping at most [limit] bytes of stdout.
 * A child still running after [timeout] is stopped with its descendants. Null on a timeout or a
 * failed start; raw output is for the caller to parse, never to log.
 */
internal fun runBounded(builder: ProcessBuilder, timeout: Duration, limit: Int = 64 * 1024): BoundedOutput? {
    val process = try {
        builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectInput(ProcessBuilder.Redirect.from(File("/dev/null"))).start()
    } catch (_: IOException) { return null }
    val output = CompletableFuture<String>()
    Thread.ofPlatform().daemon().name("hovel-claude-output").start { output.complete(runCatching { readCapped(process.inputStream, limit) }.getOrDefault("")) }
    return try {
        if (!process.waitFor(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) { terminateProcessTree(process); return null }
        BoundedOutput(process.exitValue(), output.get(2, TimeUnit.SECONDS))
    } catch (error: InterruptedException) {
        terminateProcessTree(process); throw error
    } catch (_: Exception) {
        terminateProcessTree(process); null
    }
}

/** Keep the first [limit] bytes and drain the rest, so the child never blocks on a full pipe. */
private fun readCapped(stream: InputStream, limit: Int): String = stream.use {
    val kept = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = it.read(buffer)
        if (read < 0) break
        kept.write(buffer, 0, minOf(read, (limit - kept.size()).coerceAtLeast(0)))
    }
    kept.toString(Charsets.UTF_8)
}
