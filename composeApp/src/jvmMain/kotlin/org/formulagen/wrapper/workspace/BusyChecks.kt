package org.formulagen.wrapper.workspace

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.security.SecureRandom
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.process.SYSTEM_PATH
import org.formulagen.wrapper.process.runBounded
import org.formulagen.wrapper.storage.privateFolder
import org.formulagen.wrapper.util.shortened

/*
 * The pieces [PtySession.busy] asks with: a terminal's foreground process group, whether a process is
 * stopped, Neovim's own answer on its `--listen` socket, and a short name for a running program. See
 * [Session.busy] for the rules.
 */

private const val TAG = "workspace-busy"

/** The executable name of a running Neovim, by its real path. */
internal const val NEOVIM_EXECUTABLE = "nvim"

/** How long Neovim has to answer before its tab counts as busy. */
internal val NEOVIM_ANSWER_TIMEOUT: Duration = 1.seconds

/** The most of Neovim's answer that is read: a line per modified buffer. */
private const val NEOVIM_ANSWER_BYTES = 8 * 1024

/** The first line of every answer, so an empty or foreign reply is never read as "nothing modified". */
internal const val NEOVIM_ANSWER_HEADER = "hovel-state"

/**
 * The one expression Hovel sends Neovim (`--remote-expr`), fixed: nothing from a page, a file or a
 * tab reaches it. One line per loaded buffer with work: `M <file name>` for a modified file buffer
 * (as `:qa` would refuse to drop), `T` for a `:terminal` whose job still runs. Help, prompt and
 * other `nofile` buffers do not count, as they do not for `:qa`.
 */
internal const val NEOVIM_STATE_EXPRESSION =
    "luaeval('(function() local out = {\"$NEOVIM_ANSWER_HEADER\"}; " +
        "for _, b in ipairs(vim.api.nvim_list_bufs()) do if vim.api.nvim_buf_is_loaded(b) then " +
        "local bt = vim.bo[b].buftype; " +
        "if bt == \"terminal\" then local job = vim.b[b].terminal_job_id; " +
        "if job and vim.fn.jobwait({job}, 0)[1] == -1 then out[#out + 1] = \"T\" end " +
        "elseif (bt == \"\" or bt == \"acwrite\") and vim.bo[b].modified then " +
        "out[#out + 1] = \"M \" .. vim.fn.fnamemodify(vim.api.nvim_buf_get_name(b), \":t\") end end end; " +
        "return table.concat(out, \"\\n\") end)()')"

/**
 * Ask the Neovim listening on [socket] what it holds, with [nvim] (the running editor's own
 * executable) as the client: fixed arguments (`--clean`, so no config or plugins run), and an
 * empty environment. Null when nothing is modified; [Busy.NoAnswer] for no answer within [timeout].
 */
internal fun askNeovim(nvim: String, socket: Path, editor: String, timeout: Duration = NEOVIM_ANSWER_TIMEOUT): Busy? {
    if (!Files.exists(socket)) return Busy.NoAnswer(editor)
    val client = ProcessBuilder(nvim, "--clean", "--headless", "--server", socket.toString(), "--remote-expr", NEOVIM_STATE_EXPRESSION)
    client.environment().apply {
        clear()
        put("HOME", System.getProperty("user.home"))
        put("PATH", SYSTEM_PATH.joinToString(File.pathSeparator))
    }
    val answer = runBounded(client, timeout, NEOVIM_ANSWER_BYTES)?.takeIf { it.exitCode == 0 } ?: return Busy.NoAnswer(editor)
    return parseNeovimState(editor, answer.output)
}

/** [NEOVIM_STATE_EXPRESSION]'s answer as a [Busy]: unsaved files first, then a running terminal. */
internal fun parseNeovimState(editor: String, answer: String): Busy? {
    val lines = answer.lines()
    if (lines.firstOrNull()?.trim() != NEOVIM_ANSWER_HEADER) return Busy.NoAnswer(editor)
    val entries = lines.drop(1)
    val modified = entries.filter { it.startsWith("M") }
    if (modified.isNotEmpty()) {
        val names = modified.map { clip(it.removePrefix("M").trim(), 40) }.filter { it.isNotEmpty() }.distinct()
        val shown = names.take(3) + if (names.size > 3) listOf("${names.size - 3} more") else emptyList()
        return Busy.Unsaved(editor, shown)
    }
    if (entries.any { it.trim() == "T" }) return Busy.EditorTerminal(editor)
    return null
}

/**
 * Where Neovim tabs listen: one socket per session in [folder], a private folder whose path has no
 * spaces (`~/Library/Caches/<bundle id>/workspace/nvim`). A socket path must fit a Unix socket
 * address; when the home folder makes it too long, [next] gives none and that tab counts as busy
 * whenever Neovim runs in it.
 */
class EditorSockets(private val folder: Path) {
    private val random = SecureRandom()

    /** A fresh socket path, or null when none can be used. The folder is created private on first use. */
    fun next(): Path? {
        val name = ByteArray(6).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val socket = folder.resolve("$name.sock").toAbsolutePath()
        if (socket.toString().toByteArray().size > MAX_SOCKET_PATH_BYTES) {
            log(LogLevel.Warn, TAG, "The Neovim socket folder's path is too long for a socket; Neovim tabs will always ask before closing")
            return null
        }
        return try { privateFolder(folder); socket } catch (error: IOException) {
            log(LogLevel.Warn, TAG, "Could not create the Neovim socket folder", error)
            null
        }
    }

    companion object {
        /** macOS's `sun_path` holds 104 bytes, with the terminating NUL. */
        const val MAX_SOCKET_PATH_BYTES = 103

        /** `~/Library/Caches/<bundle id>/workspace/nvim`. */
        fun defaultFolder(home: String = System.getProperty("user.home")): Path = workspaceCacheFolder(home).resolve("nvim")

        /** Remove a session's socket once it has ended: Neovim removes its own unless it was killed. */
        fun release(socket: Path) {
            try { Files.deleteIfExists(socket) } catch (_: IOException) {}
        }
    }
}

/** The two libSystem calls a Shell tab's busy check makes. */
private interface LibSystem : Library {
    /** The foreground process group of the terminal open on [fd]. */
    fun tcgetpgrp(fd: Int): Int

    /** libproc's process information: [flavor] [PROC_PIDT_SHORTBSDINFO] fills a `proc_bsdshortinfo`. */
    fun proc_pidinfo(pid: Int, flavor: Int, arg: Long, buffer: Memory, size: Int): Int
}

/** `PROC_PIDT_SHORTBSDINFO` (`sys/proc_info.h`): unlike `PROC_PIDTBSDINFO`, it reads another user's process. */
private const val PROC_PIDT_SHORTBSDINFO = 13

/** `sizeof(struct proc_bsdshortinfo)` in the macOS SDK. */
private const val PROC_BSDSHORTINFO_BYTES = 64

/** `offsetof(struct proc_bsdshortinfo, pbsi_status)`. */
private const val PBSI_STATUS_OFFSET = 12L

/** `SSTOP` (`sys/proc.h`): stopped by a signal, such as ⌃Z's SIGTSTP. */
private const val SSTOP = 4

/** `ESRCH` (`sys/errno.h`): what libproc gives for a process that is gone or a zombie. */
private const val ESRCH = 3

/** libSystem through JNA, which pty4j already loads. Null when it cannot be loaded: callers fall back to busy. */
private val libSystem: LibSystem? by lazy {
    try { Native.load("c", LibSystem::class.java) } catch (error: Throwable) {
        rethrowIfFatal(error)
        log(LogLevel.Warn, TAG, "Could not load libSystem; Shell tabs count any child as busy", error)
        null
    }
}

/** `tcgetpgrp` on a pseudo-terminal's master [fd]: its foreground process group, or null on failure. */
internal fun foregroundProcessGroup(fd: Int): Long? = try {
    libSystem?.tcgetpgrp(fd)?.takeIf { it > 0 }?.toLong()
} catch (error: Throwable) {
    rethrowIfFatal(error)
    null
}

/** Whether [process] is stopped: false once gone or a zombie; null when unreadable, which counts as busy. */
internal fun isStopped(process: ProcessHandle): Boolean? {
    val calls = libSystem ?: return null
    return try {
        Memory(PROC_BSDSHORTINFO_BYTES.toLong()).use { info ->
            val read = calls.proc_pidinfo(process.pid().toInt(), PROC_PIDT_SHORTBSDINFO, 0, info, PROC_BSDSHORTINFO_BYTES)
            when {
                read == PROC_BSDSHORTINFO_BYTES -> info.getInt(PBSI_STATUS_OFFSET) == SSTOP
                // Gone or a zombie: nothing to lose.
                Native.getLastError() == ESRCH -> false
                else -> null
            }
        }
    } catch (error: Throwable) {
        rethrowIfFatal(error)
        null
    }
}

/** Interpreters whose first argument is the script that names the program. */
private val INTERPRETERS = Regex("node|bun|deno|ruby|perl|php|python[0-9.]*|sh|bash|zsh|dash|fish")

/**
 * A short name for a running program in a confirmation: its file name and arguments, with a
 * script's interpreter left out (`node /opt/homebrew/bin/npm run dev` is `npm run dev`).
 */
internal fun commandLabel(executable: String?, arguments: List<String>): String {
    val name = executable?.let { File(it).name }?.takeIf { it.isNotEmpty() } ?: return "a program"
    val script = arguments.firstOrNull()?.takeIf { INTERPRETERS.matches(name) && it.startsWith("/") }
    val words = if (script != null) listOf(File(script).name) + arguments.drop(1) else listOf(name) + arguments
    return clip(words.joinToString(" "), 60)
}

/** [text] as plain words: no control or format characters, no lone surrogates, at most [max] characters. */
internal fun clip(text: String, max: Int): String = buildString {
    text.codePoints().filter(::isShown).forEach { appendCodePoint(it) }
}.trim().shortened(max)

/** False for controls, format characters (bidi overrides and isolates, zero widths) and lone surrogates. */
private fun isShown(codePoint: Int): Boolean = !Character.isISOControl(codePoint) &&
    Character.getType(codePoint).let { it != Character.FORMAT.toInt() && it != Character.SURROGATE.toInt() }
