package org.formulagen.wrapper.process

import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import org.formulagen.wrapper.companion.terminateProcessTree

/**
 * How the host runs the model runtimes it drives (Codex, Claude Code): bounded reads, bounded
 * short commands, and a child environment built from an allowlist. A new runtime uses
 * [allowlistedEnvironment]; Codex predates it and scrubs a blocklist instead (`codexProcess`).
 */

/** The system folders every child's `PATH` ends with. */
private val SYSTEM_PATH = listOf("/usr/bin", "/bin", "/usr/sbin", "/sbin")

/**
 * The whole environment of a runtime child: each of [keys] copied from [parent] when present, a
 * fixed `PATH` ([pathFirst], then the system folders) and [settings]. Nothing else is inherited, so
 * no API key, endpoint or another session's variables reach the child.
 */
internal fun allowlistedEnvironment(
    parent: Map<String, String>,
    keys: List<String>,
    pathFirst: List<String>,
    settings: Map<String, String>,
): Map<String, String> = buildMap {
    keys.forEach { key -> parent[key]?.let { put(key, it) } }
    put("PATH", (pathFirst + SYSTEM_PATH).distinct().joinToString(File.pathSeparator))
    putAll(settings)
}

/** Longest protocol message kept, in UTF-16 units. Larger messages are dropped whole. */
internal const val MAX_PROTOCOL_LINE = 256 * 1024

/**
 * The next newline-delimited message, or null at end of stream. A message longer than [limit] is
 * discarded up to its newline without being buffered, and reading continues with the next message;
 * a request still waiting for that reply then fails by its own timeout.
 */
internal fun readBoundedLine(reader: BufferedReader, limit: Int = MAX_PROTOCOL_LINE): String? {
    val line = StringBuilder()
    var dropping = false
    while (true) {
        val next = reader.read()
        when {
            next == -1 -> return line.takeIf { !dropping && it.isNotEmpty() }?.toString()
            next == '\n'.code -> {
                if (!dropping && line.isNotEmpty()) return line.toString()
                dropping = false
                line.setLength(0)
            }
            dropping -> Unit
            line.length >= limit -> { dropping = true; line.setLength(0) }
            else -> line.append(next.toChar())
        }
    }
}

/** One message read by [readProtocolLine]: whole, or too long and dropped with only its [prefix] kept. */
internal sealed interface ProtocolLine {
    data class Whole(val text: String) : ProtocolLine
    /** The first characters of a message longer than the limit; the rest was discarded unbuffered. */
    data class Dropped(val prefix: String) : ProtocolLine
}

/**
 * Like [readBoundedLine], for a trusted local child whose messages can be large (a working agent's
 * tool results): a message over [limit] is not silently skipped but reported as [ProtocolLine.Dropped]
 * with its first [keep] characters, so the caller can still read its `type` and answer it.
 */
internal fun readProtocolLine(reader: BufferedReader, limit: Int, keep: Int = 4_096): ProtocolLine? {
    val line = StringBuilder()
    var dropping = false
    var prefix = ""
    while (true) {
        val next = reader.read()
        when {
            next == -1 -> return when {
                dropping -> ProtocolLine.Dropped(prefix)
                line.isNotEmpty() -> ProtocolLine.Whole(line.toString())
                else -> null
            }
            next == '\n'.code -> {
                if (dropping) return ProtocolLine.Dropped(prefix)
                if (line.isNotEmpty()) return ProtocolLine.Whole(line.toString())
            }
            dropping -> Unit
            line.length >= limit -> { dropping = true; prefix = line.take(keep).toString(); line.setLength(0) }
            else -> line.append(next.toChar())
        }
    }
}

/**
 * The `PATH` the player's login shell sets up, or null when it cannot be read within a few seconds:
 * a Mac app started from Finder has only the system folders on its own.
 */
internal fun readLoginShellPath(parent: Map<String, String>): String? {
    val shell = parent["SHELL"]?.takeIf { java.nio.file.Path.of(it).isAbsolute && java.nio.file.Files.isExecutable(java.nio.file.Path.of(it)) } ?: "/bin/zsh"
    val marker = "__HOVEL_PATH__"
    val output = runBounded(ProcessBuilder(shell, "-l", "-c", "printf '$marker%s' \"\$PATH\"").directory(File(System.getProperty("user.home"))),
        kotlin.time.Duration.parse("5s"))?.takeIf { it.exitCode == 0 }?.output ?: return null
    return output.substringAfterLast(marker, "").trim().takeIf { value ->
        value.isNotEmpty() && value.split(File.pathSeparator).all { it.isEmpty() || it.startsWith("/") }
    }
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
    Thread.ofPlatform().daemon().name("hovel-child-output").start { output.complete(runCatching { readCapped(process.inputStream, limit) }.getOrDefault("")) }
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

/**
 * SIGINT, which the JVM cannot send itself: a runtime that handles it ends its turn and keeps the
 * text so far. Blocks briefly for `kill`, so callers on a lock run it on their own thread.
 */
internal fun signalInterrupt(process: Process) {
    if (!process.isAlive) return
    runCatching { ProcessBuilder("/bin/kill", "-INT", process.pid().toString()).redirectErrorStream(true).start().waitFor(2, TimeUnit.SECONDS) }
}
