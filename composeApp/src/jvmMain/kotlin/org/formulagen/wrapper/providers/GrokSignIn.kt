package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.chat.grok.GrokAcp
import org.formulagen.wrapper.chat.grok.GrokAuthAvailability
import org.formulagen.wrapper.chat.grok.grokHandshake
import org.formulagen.wrapper.chat.grok.grokInitialize

/**
 * Ask Grok itself which sign-in paths are available. Only ACP initialize is allowed: no authentication,
 * session, model prompt or client tools. A cached credential is detection, not verified subscription access.
 * Grok owns its credential home and may refresh its own token; Hovel never opens credential files.
 */
internal fun readGrokSignIn(executable: Path, environment: Map<String, String>, timeout: Duration = 5.seconds): GrokAuthAvailability {
    var folder: Path? = null
    return try {
        folder = Files.createTempDirectory("hovel-grok-status-",
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val builder = ProcessBuilder(executable.toString(), "--no-auto-update", "agent", "--no-leader", "stdio")
            .directory(folder.toFile()).apply {
                environment().clear()
                environment().putAll(grokStatusEnvironment(environment))
            }
        val rpc = GrokAcp(builder)
        try { grokHandshake(rpc.call("initialize", grokInitialize(), timeout)).auth }
        finally { rpc.close(); rpc.awaitClosed() }
    } catch (error: Exception) {
        if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException()
        GrokAuthAvailability.UNKNOWN
    } finally { folder?.toFile()?.deleteRecursively() }
}

/** Preserve only the provider-owned login home; exclude inherited tokens, endpoints and compatibility scans. */
private fun grokStatusEnvironment(environment: Map<String, String>): Map<String, String> = buildMap {
    putAll(environment.filterKeys { it in setOf("HOME", "GROK_HOME", "USER", "LOGNAME", "TMPDIR", "LANG") })
    putIfAbsent("HOME", System.getProperty("user.home"))
    put("PATH", "/usr/bin:/bin:/usr/sbin:/sbin")
    put("GROK_DISABLE_AUTOUPDATER", "1")
    put("BROWSER", "/usr/bin/false")
    for (brand in listOf("CURSOR", "CLAUDE")) for (feature in listOf("SKILLS", "RULES", "AGENTS", "MCPS", "HOOKS", "SESSIONS"))
        put("GROK_${brand}_${feature}_ENABLED", "0")
    put("GROK_CODEX_SESSIONS_ENABLED", "0")
}
