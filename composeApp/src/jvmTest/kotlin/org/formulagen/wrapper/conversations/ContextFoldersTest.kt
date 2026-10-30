package org.formulagen.wrapper.conversations

import java.nio.file.Path
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.runtimes.ContextFolderSupport
import org.formulagen.wrapper.runtimes.ProjectFolders
import org.formulagen.wrapper.runtimes.WorkScope
import org.formulagen.wrapper.runtimes.claude.claudeContextArguments
import org.formulagen.wrapper.runtimes.codex.codexContextSandbox
import org.junit.Test

/**
 * How each runtime keeps context folders read-only, built hermetically. No runtime may be given
 * context folders until its live probe ([ContextFolderProbeTest]) has confirmed the mechanism.
 */
class ContextFoldersTest {
    private val working = Path.of("/Users/ember/hovel-notes")
    private val docs = Path.of("/Users/ember/Design Docs")
    private val specs = Path.of("/Volumes/Shared/specs")

    @Test fun claudeAddsEachContextFolderAndDeniesEditAndWriteUnderIt() {
        assertEquals(listOf(
            "--add-dir", "/Users/ember/Design Docs",
            "--disallowedTools", "Edit(//Users/ember/Design Docs/**)",
            "--disallowedTools", "Write(//Users/ember/Design Docs/**)",
            "--add-dir", "/Volumes/Shared/specs",
            "--disallowedTools", "Edit(//Volumes/Shared/specs/**)",
            "--disallowedTools", "Write(//Volumes/Shared/specs/**)",
        ), claudeContextArguments(listOf(docs, specs)))
        assertEquals(emptyList(), claudeContextArguments(emptyList()), "no context folder changes nothing of the player's setup")
    }

    @Test fun claudeRefusesAFolderARuleCannotNameExactly() {
        for (bad in listOf("relative/docs", "/Users/ember/../etc", "/", "/Users/ember/docs (old)", "/Users/ember/*", "/Users/ember/a,b",
            "/Users/ember/[x]", "/Users/ember/new\nline", "/Users/ember/back\\slash", "/Users/ember/bang!")) {
            assertFails(bad) { claudeContextArguments(listOf(Path.of(bad))) }
        }
    }

    @Test fun codexWritesOnlyInTheWorkingFolderWhenThereAreContextFolders() {
        val sandbox = assertNotNull(codexContextSandbox(working, listOf(docs, specs)))
        assertEquals("workspace-write", sandbox.getValue("sandbox").jsonPrimitive.content)
        assertEquals(JsonArray(listOf(JsonPrimitive(working.toString()))),
            sandbox.getValue("config").jsonObject.getValue("sandbox_workspace_write.writable_roots"))
        assertNull(codexContextSandbox(working, emptyList()), "without context folders the player's own sandbox stands")
        assertFails { codexContextSandbox(Path.of("relative"), listOf(docs)) }
    }

    @Test fun aRuntimeIsGivenContextFoldersOnlyOnceVerified() {
        val folders = ProjectFolders(working, listOf(docs))
        assertEquals(folders, folders.forRuntime(ContextFolderSupport.VERIFIED))
        assertEquals(ProjectFolders(working), folders.forRuntime(ContextFolderSupport.UNVERIFIED))
        assertEquals(ProjectFolders(working), folders.forRuntime(ContextFolderSupport.NONE))
        assertFails("context folders only beside a working folder") { ProjectFolders(null, listOf(docs)) }
        assertFails("a working turn needs a working folder") { WorkScope(ProjectFolders(null), "lease", "Ember", TeamMember(RuntimeId.CODEX, "x")) }
        assertEquals(working, WorkScope(working, "lease", "Ember", TeamMember(RuntimeId.CODEX, "x")).folder)
    }

    @Test fun noRuntimeIsVerifiedBeforeItsLiveProbe() {
        // Flip a runtime to VERIFIED only together with its passing probe in ContextFolderProbeTest.
        val root = java.nio.file.Files.createTempDirectory("context-support").toRealPath()
        val broker = org.formulagen.wrapper.approvals.ApprovalBroker { _, _, _ -> org.formulagen.wrapper.approvals.ApprovalDecision.DENY }
        val claude = org.formulagen.wrapper.providers.ClaudeRuntime(root.resolve("claude"), root.resolve("providers"), emptyMap())
        val runtimes = listOf(
            org.formulagen.wrapper.runtimes.claude.ClaudeWorkRuntime(claude, org.formulagen.wrapper.providers.ClaudeConnection(claude), broker),
            org.formulagen.wrapper.runtimes.codex.CodexWorkRuntime(root, root.resolve("codex"), broker),
            org.formulagen.wrapper.runtimes.grok.GrokWorkRuntime(root, emptyMap(), org.formulagen.wrapper.providers.GrokConnection(emptyMap()), broker, { emptySet() }),
        )
        try {
            assertEquals(mapOf(RuntimeId.CLAUDE_CODE to ContextFolderSupport.UNVERIFIED, RuntimeId.CODEX to ContextFolderSupport.UNVERIFIED,
                RuntimeId.GROK_BUILD to ContextFolderSupport.NONE), runtimes.associate { it.id to it.contextFolders })
            assertTrue(runtimes.none { it.contextFolders.readable })
        } finally { runtimes.forEach { it.close() } }
        assertTrue(ContextFolderSupport.VERIFIED.readable)
    }
}
