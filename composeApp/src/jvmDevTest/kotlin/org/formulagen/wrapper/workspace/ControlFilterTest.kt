package org.formulagen.wrapper.workspace

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The changes view's filter between git and delta: every control character in a file's diff made
 * visible, so hidden text, cursor moves and overwrites cannot hide a line, and so are the characters
 * that hide or reorder text (Trojan Source). Other valid UTF-8 is untouched.
 */
class ControlFilterTest {
    private val root: Path = createTempDirectory("hovel-controls")
    private val files: Path = ShellFiles.install(root.resolve("shell"))
    private val filter = ShellLaunch("/bin/zsh", emptyMap(), files).controlFilter()

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    /** [input] through the filter as the diff pipeline runs it, with [environment] added to the test's own. */
    private fun filtered(input: ByteArray, environment: Map<String, String> = emptyMap()): ByteArray {
        val process = ProcessBuilder(filter).apply { environment().putAll(environment) }.start()
        val output = CompletableFuture.supplyAsync { process.inputStream.readAllBytes() }
        val errors = CompletableFuture.supplyAsync { process.errorStream.readAllBytes().decodeToString() }
        process.outputStream.use { it.write(input) }
        assertTrue(process.waitFor(10, SECONDS), "the filter ended")
        assertEquals(0, process.exitValue(), errors.get(10, SECONDS))
        assertEquals("", errors.get(10, SECONDS), "no warnings")
        return output.get(10, SECONDS)
    }

    private fun filtered(text: String): String = filtered(text.toByteArray()).decodeToString()

    /** Text as UTF-8 and ints as raw bytes, in order. */
    private fun bytes(vararg parts: Any): ByteArray = ByteArrayOutputStream().apply {
        for (part in parts) if (part is Int) write(part) else write(part.toString().toByteArray())
    }.toByteArray()

    /** Only tab, newline, printable ASCII and valid UTF-8 with no C1 control: nothing a terminal acts on. */
    private fun assertHarmless(output: ByteArray) {
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(output)).toString()
        val control = text.firstOrNull { Character.isISOControl(it) && it != '\t' && it != '\n' }
        assertEquals(null, control, "a control character came through: $text")
    }

    @Test fun theFilterIsMacOSsPerlWithTheEnvironmentShutOut() {
        assertEquals(listOf("/usr/bin/perl", "-T", "-C0", files.resolve(ShellFiles.CONTROLS).toString()), filter)
        assertTrue(Files.isRegularFile(files.resolve(ShellFiles.CONTROLS)))
    }

    @Test fun escapeSequencesCarriageReturnsAndBackspacesBecomeVisible() {
        assertEquals("+hidden ^[[8msecret^[[0m shown\n", filtered("+hidden \u001b[8msecret\u001b[0m shown\n"))
        assertEquals("+^[[1A^[[2Kerased\n", filtered("+\u001b[1A\u001b[2Kerased\n"))
        assertEquals("+safe^Mrm -rf ~^M\n", filtered("+safe\rrm -rf ~\r\n"))
        assertEquals("+abc^H^H^Hxyz^?\n", filtered("+abc\b\b\bxyz\u007f\n"))
        assertEquals("^@^A^G^K^L^Z^\\^_\n", filtered("\u0000\u0001\u0007\u000b\u000c\u001a\u001c\u001f\n"))
        assertEquals("tab\tstays\n", filtered("tab\tstays\n"))
    }

    @Test fun c1ControlsIncludingTheOneCharacterCsiBecomeVisible() {
        assertEquals("+<U+009B>31m red <U+0085> <U+0080><U+009F>\n", filtered("+\u009b31m red \u0085 \u0080\u009f\n"))
    }

    @Test fun charactersThatHideOrReorderTextBecomeVisible() {
        val marked = listOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200B, 0x2060, 0xFEFF)
        for (code in marked) {
            val char = String(Character.toChars(code))
            assertEquals("+a<U+%04X>b\n".format(code), filtered("+a${char}b\n"), "U+%04X".format(code))
        }
        // Trojan Source's early return: the code reads as a comment.
        val trojan = "+    /* \u202e } \u2066if (isAdmin)\u2069 \u2066 begin admins only */\n"
        assertEquals("+    /* <U+202E> } <U+2066>if (isAdmin)<U+2069> <U+2066> begin admins only */\n", filtered(trojan))
    }

    @Test fun rightToLeftAndJoinedTextComesThroughUnchanged() {
        val text = listOf(
            "+שלום עולם",                         // Hebrew
            "+مرحبا بالعالم",                     // Arabic
            "+می\u200cخواهم",                    // Persian, with a ZWNJ
            "+क्\u200dष",                         // Devanagari, with a ZWJ
            "+👩\u200d💻 👨\u200d👩\u200d👧",       // ZWJ emoji
            "+a\u200eb\u200fc",                  // LRM and RLM
        ).joinToString("\n", postfix = "\n")
        assertContentEquals(text.toByteArray(), filtered(text.toByteArray()))
    }

    @Test fun bytesThatAreNotUtf8AreShownNeverPassedRaw() {
        // A raw CSI byte, a stray byte, a cut sequence, a surrogate, an overlong, past U+10FFFF.
        val input = bytes("+", 0x9B, "2J ", 0xFF, " ", 0xC3, " ", 0xED, 0xA0, 0x80, " ", 0xC0, 0xAF, " ", 0xF4, 0x90, 0x80, 0x80, "\n")
        val output = filtered(input)
        assertEquals("+<9B>2J <FF> <C3> <ED><A0><80> <C0><AF> <F4><90><80><80>\n", output.decodeToString())
        assertHarmless(output)
    }

    @Test fun everythingAtOnceComesOutHarmless() {
        val input = bytes("+\u001b[8m\u001b]0;title\u0007\r\b\u007f\u009b", 0x9B, 0xC2, "\u001b", 0xFF, "x", 0xE2, 0x80, "\n", 0x00)
        assertHarmless(filtered(input))
    }

    @Test fun utf8TextComesThroughUnchanged() {
        val text = "+café naïve Ünïcödé 日本語 😀 👩‍💻 ❤️\tend — “quotes”\n-plain ascii\n no newline at the end"
        assertContentEquals(text.toByteArray(), filtered(text.toByteArray()))
    }

    @Test fun thePlayersPerlSettingsChangeNothing() {
        val library = Files.createDirectories(root.resolve("lib"))
        Files.writeString(library.resolve("strict.pm"), "package strict; print \"LOADED\"; sub import {} 1;\n")
        val input = "+café \u001b[8m😀\u009b\n".toByteArray()
        val expected = filtered(input)
        val hostile = mapOf("PERL_UNICODE" to "SDA", "PERL5OPT" to "-CSDA", "PERLIO" to ":utf8", "PERL5LIB" to library.toString(), "PERLLIB" to library.toString())
        assertContentEquals(expected, filtered(input, hostile))
        assertEquals("+café ^[[8m😀<U+009B>\n", expected.decodeToString())
    }
}
