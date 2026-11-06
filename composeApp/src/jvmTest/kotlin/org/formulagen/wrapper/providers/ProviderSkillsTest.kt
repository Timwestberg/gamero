package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import kotlin.test.*

/** The installed skills a provider reports, read from a skills folder's `SKILL.md` front matter. */
class ProviderSkillsTest {
    private val root: Path = Files.createTempDirectory("skills").toRealPath()

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    /** A skill folder [name] in [root] whose `SKILL.md` is [text]. */
    private fun skill(name: String, text: String, root: Path = this.root): Path =
        Files.createDirectories(root.resolve(name)).also { Files.writeString(it.resolve("SKILL.md"), text) }

    private fun frontMatter(vararg lines: String) = "---\n" + lines.joinToString("\n") + "\n---\n\n# Body\n\nNever read.\n"

    @Test fun aSkillIsNamedByItsFolderAndDescribedByItsFrontMatter() {
        skill("code-review", frontMatter("name: code-review", "description: Review a change before merge."))
        skill("write-documentation", frontMatter("name: documentation-writer", "description: Write docs."))
        assertEquals(listOf(ProviderSkill("code-review", "Review a change before merge."), ProviderSkill("write-documentation", "Write docs.")),
            readInstalledSkills(root), "Claude Code invokes a skill by its folder; the front matter name is only its label")
    }

    @Test fun descriptionsReadAsOneLineInEveryYamlForm() {
        skill("folded", frontMatter("description: >-", "  Plans commits", "  for a pull request.", "compatibility: Git"))
        skill("literal", frontMatter("description: |", "  First line.", "", "  Second line."))
        skill("quoted", frontMatter("description: \"Say \\\"hi\\\" twice\""))
        skill("single", frontMatter("description: 'It''s short'"))
        skill("plain", frontMatter("description: Starts here", "  and goes on."))
        skill("none", frontMatter("name: none"))
        skill("bom", "﻿" + frontMatter("description: Saved with a byte order mark."))
        assertEquals(mapOf("bom" to "Saved with a byte order mark.", "folded" to "Plans commits for a pull request.",
            "literal" to "First line. Second line.", "none" to "", "plain" to "Starts here and goes on.",
            "quoted" to "Say \"hi\" twice", "single" to "It's short"), readInstalledSkills(root).associate { it.name to it.description })
    }

    @Test fun anEntryWithoutValidFrontMatterOrAProviderNameIsLeftOut() {
        skill("valid", frontMatter("description: Kept."))
        skill("no-front-matter", "# Just a heading\n\nInstructions.\n")
        skill("unclosed", "---\nname: unclosed\ndescription: Never closed.\n----------\n\n# Body\n")
        skill("too-long-front-matter", "---\n" + "description: x\n".repeat(250) + "---\n")
        skill("Upper-Case", frontMatter("description: Not a provider skill name."))
        skill(".hidden", frontMatter("description: Hidden."))
        skill("n".repeat(BrainLimits.PROVIDER_SKILL_NAME + 1), frontMatter("description: Name too long."))
        skill("user-only", frontMatter("description: Started by the person.", "disable-model-invocation: true"))
        Files.createDirectories(root.resolve("synced").resolve("org").resolve("nested"))
        Files.writeString(root.resolve("loose-file"), "not a skill")
        Files.createDirectories(root.resolve("folder-skill.md").resolve("SKILL.md"))
        assertEquals(listOf("valid"), readInstalledSkills(root).map { it.name })
    }

    @Test fun aLongDescriptionIsShortenedAndAtMostTheLimitAreReportedByName() {
        skill("long", frontMatter("description: " + "word ".repeat(200)))
        val long = readInstalledSkills(root).single().description
        assertEquals(BrainLimits.USE_TEXT, long.length)
        assertTrue(long.endsWith("…"))
        repeat(BrainLimits.PROVIDER_SKILLS + 5) { skill("skill-%03d".format(it), frontMatter("description: Number $it.")) }
        val skills = readInstalledSkills(root)
        assertEquals(BrainLimits.PROVIDER_SKILLS, skills.size)
        assertEquals("long", skills.first().name, "sorted by name")
        assertEquals("skill-062", skills.last().name)
    }

    @Test fun aLinkedSkillFolderIsReadAndAMissingRootHasNone() {
        val elsewhere = Files.createTempDirectory("linked").toRealPath()
        try {
            val target = skill("shared", frontMatter("description: Kept in another folder."), elsewhere)
            Files.createSymbolicLink(root.resolve("shared"), target)
            assertEquals(listOf(ProviderSkill("shared", "Kept in another folder.")), readInstalledSkills(root))
        } finally { elsewhere.toFile().deleteRecursively() }
        assertEquals(emptyList(), readInstalledSkills(root.resolve("missing")))
        assertEquals(emptyList(), readInstalledSkills(Files.writeString(root.resolve("a-file"), "")))
    }

    @Test fun aSkillsTextIsItsBodyAfterTheFrontMatterNamedWhereItReallyIs() {
        skill("code-review", frontMatter("name: reviewer", "description: Review a change before merge."))
        val text = assertNotNull(readSkillText(root, "code-review"))
        assertEquals(ProviderSkillText("Review a change before merge.", "# Body\n\nNever read.", false,
            root.resolve("code-review").resolve("SKILL.md").toString()), text)
        val elsewhere = Files.createTempDirectory("linked").toRealPath()
        try {
            val target = skill("shared", frontMatter("description: Kept elsewhere."), elsewhere)
            Files.createSymbolicLink(root.resolve("shared"), target)
            assertEquals(target.resolve("SKILL.md").toString(), readSkillText(root, "shared")?.file, "a linked folder is named where it is")
        } finally { elsewhere.toFile().deleteRecursively() }
        skill("user-only", frontMatter("description: Started by the person.", "disable-model-invocation: true"))
        skill("no-front-matter", "# Just a heading\n")
        for (name in listOf("user-only", "no-front-matter", "missing", "../escape", "Upper")) assertNull(readSkillText(root, name), name)
    }

    @Test fun aLongSkillIsCutToTheLimitAndSaysSo() {
        val limit = ProviderSkillText.INSTRUCTIONS
        skill("exact", frontMatter("description: Exactly.") + "x".repeat(limit - "# Body\n\nNever read.\n".length - 1) + "\n")
        val exact = assertNotNull(readSkillText(root, "exact"))
        assertEquals(limit - 1, exact.instructions.length)
        assertFalse(exact.truncated)
        skill("long", frontMatter("description: Long.") + "y".repeat(limit * 3))
        val long = assertNotNull(readSkillText(root, "long"))
        assertEquals(limit, long.instructions.length)
        assertTrue(long.truncated)
        assertTrue(long.instructions.startsWith("# Body"))
    }

    @Test fun claudeSkillsLiveInTheConfigFolderThePlayerSets() {
        assertEquals(Path.of("/Users/player/.claude/skills"), claudeSkillsRoot(mapOf("HOME" to "/Users/player")))
        assertEquals(Path.of("/Volumes/setup/claude/skills"),
            claudeSkillsRoot(mapOf("HOME" to "/Users/player", "CLAUDE_CONFIG_DIR" to "/Volumes/setup/claude")))
        assertEquals(Path.of("/Users/player/.claude/skills"), claudeSkillsRoot(mapOf("HOME" to "/Users/player", "CLAUDE_CONFIG_DIR" to "relative")),
            "a relative folder is not one Claude Code would find from every working folder")
    }

    @Test fun onlyClaudeCodeReportsSkills() = runBlocking {
        skill("code-review", frontMatter("description: Review a change before merge."))
        DesktopProviders(listOf(ClaudeProvider(ClaudeConnection(null), root), CodexProvider(root, null))).use { providers ->
            assertEquals(listOf("code-review"), providers.skills(ProviderScope(RuntimeId.CLAUDE_CODE)).map { it.name })
            assertEquals("Review a change before merge.", providers.skillText(ProviderScope(RuntimeId.CLAUDE_CODE), "code-review")?.description)
            assertEquals(emptyList(), providers.skills(ProviderScope(RuntimeId.CODEX)), "Codex's skill location is not read yet")
            assertNull(providers.skillText(ProviderScope(RuntimeId.CODEX), "code-review"))
            assertEquals(emptyList(), providers.skills(ProviderScope(RuntimeId.GROK_BUILD)), "a runtime not offered has none")
        }
        assertEquals(emptyList(), ClaudeProvider(ClaudeConnection(null)).skills(), "no folder, no skills")
    }
}
