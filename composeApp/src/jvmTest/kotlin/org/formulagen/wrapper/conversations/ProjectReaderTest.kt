package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.brain.CoordinatorDecision
import org.formulagen.wrapper.runtimes.ProjectFolders

class ProjectReaderTest {
    private val base: Path = folder("reader")
    private val working: Path = Files.createDirectories(base.resolve("shop"))
    private val docs: Path = Files.createDirectories(base.resolve("brand"))

    init {
        Files.createDirectories(working.resolve("src/auth"))
        Files.createDirectories(working.resolve("node_modules/left-pad"))
        Files.writeString(working.resolve("README.md"), "# Shop\n\nA shopping list.")
        Files.writeString(working.resolve("src/app.ts"), "import { signIn } from './auth/session'\nexport const app = 1\n")
        Files.writeString(working.resolve("src/auth/session.ts"), "export function signIn() {}\n// Sign in lasts a week.\n")
        Files.writeString(working.resolve("node_modules/left-pad/index.js"), "// sign in here too")
        Files.writeString(working.resolve(".env"), "SIGN_IN_TOKEN=abc")
        Files.write(working.resolve("logo.png"), byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0, 13))
        Files.writeString(docs.resolve("voice.md"), "Say sign in, never log in.")
    }

    private fun reader() = ProjectReader(PathGuard(ProjectFolders(working, listOf(docs))))
    private fun read(files: List<String>? = null, list: List<String>? = null, search: String? = null) =
        CoordinatorDecision.Read(files, list, search)

    @Test fun aFileIsReadInFullAndNamedAsTheCoordinatorAskedForIt() {
        val result = reader().answer(read(files = listOf("README.md", "[brand]/voice.md")))
        assertTrue("File README.md, in full:\n<<<\n# Shop\n\nA shopping list.\n>>>" in result.text, result.text)
        assertTrue("File [brand]/voice.md, in full:\n<<<\nSay sign in, never log in.\n>>>" in result.text, result.text)
        assertEquals(listOf("README.md", "[brand]/voice.md"), result.read)
        assertEquals(emptyList(), result.refused)
        assertFalse(base.toString() in result.text, "no absolute path: ${result.text}")
        assertEquals("Read README.md and [brand]/voice.md.", result.line())
    }

    @Test fun whatMayNotBeReadIsNamedWithItsReasonAndNeverRead() {
        val result = reader().answer(read(files = listOf(".env", "../brand/voice.md", "logo.png", "src", "gone.md", "README.md")))
        assertFalse("SIGN_IN_TOKEN" in result.text, result.text)
        assertEquals(listOf("README.md"), result.read)
        assertEquals(listOf(
            ".env (never read: a key, an environment file or a build folder)",
            "../brand/voice.md (outside the project's folders)",
            "logo.png (not a text file)",
            "src (a folder: list it instead)",
            "gone.md (not found)",
        ), result.refused)
        assertTrue(result.text.contains("Not read: .env (never read"), result.text)
        assertEquals("Read README.md.", result.line())
        // With nothing read there is nothing to say in the conversation.
        assertNull(reader().answer(read(files = listOf(".env"))).line())
    }

    @Test fun aFolderIsListedWithoutItsSecretsOrBuildFolders() {
        val result = reader().answer(read(list = listOf(".", "src")))
        assertTrue("Folder .: README.md, logo.png, src/" in result.text, result.text)
        assertTrue("Folder src: app.ts, auth/" in result.text, result.text)
        assertFalse(".env" in result.text || "node_modules" in result.text, result.text)
        assertEquals(listOf("the folder's list", "src/"), result.read)
    }

    @Test fun aSearchGivesTheLinesThatHoldTheTextAndWhereTheyAre() {
        val result = reader().answer(read(search = "Sign in"))
        assertTrue("src/auth/session.ts:2: // Sign in lasts a week." in result.text, result.text)
        assertTrue("src/app.ts:1: import { signIn } from './auth/session'" !in result.text, "\"signIn\" does not hold \"sign in\"")
        assertTrue("[brand]/voice.md:1: Say sign in, never log in." in result.text, result.text)
        assertFalse("node_modules" in result.text || "SIGN_IN_TOKEN" in result.text, result.text)
        assertTrue(result.text.startsWith("Search for \"Sign in\": 2 lines."), result.text)
        assertEquals("Read a search for \"Sign in\".", result.line())
        assertTrue(reader().answer(read(search = "no such words")).text.startsWith("Search for \"no such words\": no line holds it."))
    }

    @Test fun aMessageHasAnAllowanceOfRoundsAndOfText() {
        val reader = reader()
        assertFalse(reader.spent)
        reader.answer(read(files = listOf("README.md")))
        assertFalse(reader.spent)
        val last = reader.answer(read(files = listOf("src/app.ts")))
        assertTrue(reader.spent, "two rounds is the allowance")
        assertTrue(last.text.endsWith("This message's reading allowance is now used up. Answer from what you have, or delegate."), last.text)
        // A long file is cut to what is left, and says so.
        Files.writeString(working.resolve("big.md"), "x".repeat(ReadLimits.CHARS + 500))
        val big = reader()
        val cut = big.answer(read(files = listOf("big.md", "README.md")))
        assertTrue("File big.md, its first ${ReadLimits.CHARS} characters:" in cut.text, cut.text.take(200))
        assertTrue(big.spent)
        assertEquals(listOf("README.md (this message's reading allowance is used up)"), cut.refused)
    }
}
