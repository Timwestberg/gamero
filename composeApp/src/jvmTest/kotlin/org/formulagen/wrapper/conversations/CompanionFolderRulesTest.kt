package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.bridge.RuntimeId

class CompanionFolderRulesTest {
    private val rules = CompanionFolderRules

    @Test fun aNameIsMadeSafeForFinder() {
        assertEquals("Q3 plan - draft- 2", rules.safeName("Q3 plan / draft: 2", "x"))
        assertEquals("a-b-c", rules.safeName("a/b:c", "x"))
        assertEquals("two words", rules.safeName("  two \n\t words  ", "x"))
        assertEquals("hidden", rules.safeName("..hidden", "x"), "a name never starts with a dot")
        assertEquals("ends", rules.safeName("ends...", "x"))
        assertEquals("Conversation", rules.safeName("  ", "Conversation"))
        assertEquals("Conversation", rules.safeName("...", "Conversation"))
        assertEquals(80, rules.safeName("n".repeat(200), "x").length)
        assertEquals("ab", rules.safeName("a\u0000b\u0007", "x"), "control characters go")
    }

    @Test fun aTakenNameGetsTheNextNumber() {
        assertEquals("Ember", rules.freeName("Ember", emptySet()))
        assertEquals("Ember 2", rules.freeName("Ember", setOf("Ember")))
        assertEquals("Ember 3", rules.freeName("Ember", setOf("ember", "EMBER 2")), "names that differ only by case are one file")
        assertEquals("01 Plan 2.md", rules.freeName("01 Plan", setOf("01 Plan.md"), ".md"))
        assertEquals("01 Plan.md", rules.freeName("01 Plan", setOf("01 Plan"), ".md"), "the extension is part of the name")
    }

    @Test fun anOutputIsNamedByItsFirstHeading() {
        assertEquals("Shopping list spec", rules.firstHeading("intro\n\n## Shopping list spec ##\n\n# Later"))
        assertEquals("The API and the docs", rules.firstHeading("# The **API** and [the docs](https://example.test) `"))
        assertNull(rules.firstHeading("no heading here\n#hashtag is not one"))
        assertNull(rules.firstHeading("```\n# a comment in code\n```\nplain"), "a heading inside a code fence is code")
        assertNull(rules.firstHeading("\n".repeat(60) + "# Too far down"))
        assertEquals("01 Shopping list spec", rules.outputName(1, "TPM", "# Shopping list spec\n\nBody"))
        assertEquals("03 TPM", rules.outputName(3, "TPM", "Just a reply with no heading."))
        assertEquals("12 Architect", rules.outputName(12, "Architect", null))
        assertEquals("100 a-b", rules.outputName(100, "TPM", "# a/b"))
        assertTrue(rules.outputName(1, "TPM", "# " + "w".repeat(300)).length <= rules.NAME_LIMIT)
    }

    @Test fun aConversationsFolderIsItsFirstDayAndItsTitle() {
        assertEquals("2026-11-10 Add sign-in", rules.conversationName("2026-11-10", "Add sign-in"))
        assertEquals("2026-11-10 Conversation", rules.conversationName("2026-11-10", "  "))
        assertTrue(rules.conversationName("2026-11-10", "t".repeat(200)).length <= rules.NAME_LIMIT)
    }

    @Test fun outputsAreMarkdownFilesAndAnythingElseIsNot() {
        assertEquals(".md", rules.extension("text/markdown"))
        assertEquals(".md", rules.extension("text/plain"))
        assertEquals(".bin", rules.extension("application/octet-stream"))
    }

    @Test fun theIndexListsEachOutputWithWhoMadeIt() {
        val names = FolderNames("a", "c", "Ember", "v", "Add sign-in")
        val folder = ConversationFolder("c", "2026-11-10 Add sign-in", "Add sign-in", false, "2026-11-10", listOf(
            MirroredOutput("1", "01 Shopping list spec.md", "TPM", RuntimeId.CODEX, "gpt-6-luna", "2026-11-10T14:28:05Z"),
            MirroredOutput("2", "02 A|B.md", "Architect", RuntimeId.CLAUDE_CODE, "opus", "not a time"),
        ))
        val index = rules.index(names, folder, ZoneOffset.UTC)
        assertTrue(index.startsWith("# Add sign-in\n\nWhat Ember's agents produced in this conversation"), index)
        assertTrue("| 01 | [01 Shopping list spec.md](outputs/01%20Shopping%20list%20spec.md) | TPM | Codex | gpt-6-luna | 2026-11-10 14:28 |" in index, index)
        assertTrue("| 02 | [02 A\\|B.md](outputs/02%20A%7CB.md) | Architect | Claude | opus | not a time |" in index, index)
        assertTrue("Nothing yet." in rules.index(names, folder.copy(outputs = emptyList()), ZoneOffset.UTC))
    }

    @Test fun theRootIsHovelInTheHomeFolderUnlessARigMovesIt() {
        val home = Path.of("/Users/someone")
        assertEquals(Path.of("/Users/someone/Hovel"), rules.root(emptyMap(), home))
        assertEquals(Path.of("/Users/someone/Hovel"), rules.root(mapOf(rules.ROOT_OVERRIDE to "  "), home))
        assertEquals(Path.of("/tmp/rig/folders"), rules.root(mapOf(rules.ROOT_OVERRIDE to "/tmp/rig/x/../folders"), home))
    }
}
