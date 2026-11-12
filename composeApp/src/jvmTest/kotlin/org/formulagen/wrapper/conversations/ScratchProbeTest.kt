package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalKind
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.runtimes.*
import org.formulagen.wrapper.runtimes.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.runtimes.codex.CodexWorkRuntime
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The live probes that gate `ScratchSupport.VERIFIED`: one short working turn on the owner's own runtime, in fresh
 * throwaway folders (never a real project). The turn reads a file in the scratch folder, writes one there, writes a
 * note in the working folder, and tries to write a file outside both.
 *
 * A probe passes when the scratch file was read and written, the working folder's note was written, and the file
 * outside both was not. The broker stands in for the person: it allows what names the working or the scratch folder
 * and denies what names the outside folder. What each provider must show beyond that differs, because their own
 * settings differ:
 * - **Claude Code** asks before a write wherever it is, so the proof is the read: a folder in its workspace is read
 *   without asking, and one outside it is not.
 * - **Codex** is probed in the sandbox Hovel sets itself (`workspace-write`, as with a context folder), where a write
 *   outside the writable roots fails. The proof is that scratch is a root and the outside folder is not.
 *
 * Each runs only when its flag names an installed, signed-in runtime. Prints states only, never output.
 */
class ScratchProbeTest {
    private class Probe(prefix: String, withContext: Boolean) {
        /**
         * Under the home folder, never the temporary one: Codex's `workspace-write` sandbox may always write the
         * temporary folders, so a probe there proves nothing about its roots.
         */
        val root: Path = Files.createDirectories(Path.of(System.getProperty("user.home"), "Library", "Caches", "HovelProbe", "$prefix-${UUID.randomUUID()}")).toRealPath()
        val working: Path = Files.createDirectories(root.resolve("probe-project"))
        val scratch: Path = Files.createDirectories(root.resolve("probe-scratch"))
        val outside: Path = Files.createDirectories(root.resolve("probe-outside"))
        val context: List<Path> = if (withContext) listOf(Files.createDirectories(root.resolve("probe-docs"))) else emptyList()
        val brief: Path = Files.writeString(scratch.resolve("brief.txt"), "The brief: probe the scratch folder.\n")
        val handoff: Path = scratch.resolve("handoff.txt")
        val leak: Path = outside.resolve("leak.txt")
        val note: Path = working.resolve("notes.txt")

        /** What the person was asked, by where it pointed and what kind of thing it was. */
        val asked = CopyOnWriteArrayList<Pair<String, ApprovalKind>>()

        val broker = ApprovalBroker { request, _, shown ->
            shown()
            val files = (request.subject as? ApprovalSubject.Edit)?.files.orEmpty().joinToString("\n")
            val text = "${request.title}\n${request.detail}\n$files"
            val where = when {
                "probe-outside" in text -> "outside"
                "probe-scratch" in text -> "scratch"
                else -> "working"
            }
            asked += where to request.kind
            if (where == "outside") ApprovalDecision.DENY else ApprovalDecision.ALLOW_ONCE
        }

        val prompt = "Do these four steps in order, and go on to the next step when one is refused. " +
            "1. Read $brief and quote its first line. " +
            "2. Create the file $handoff containing exactly 'handoff ok'. " +
            "3. Create the file notes.txt in the current folder containing exactly 'ok'. " +
            "4. Create the file $leak containing exactly 'leak'. " +
            "Then say which steps worked. Use your file tools, not the shell, where you have them."

        fun turn(runtime: WorkRuntime, member: TeamMember): String {
            val done = CompletableFuture<String>()
            runtime.startTurn(TurnRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), Persona.working("Ember"), member.modelId, null, prompt,
                WorkScope(ProjectFolders(working, context), "probe", "Ember", member, scratch = scratch)), object : TurnEvents {
                override fun opened(conversation: String) = Unit
                override fun accepted() = Unit
                override fun partial(text: String) = Unit
                override fun completed(text: String, conversation: String) { done.complete("completed; quoted the brief: ${"probe the scratch folder" in text}") }
                override fun failed(failure: ChatFailure) { done.complete("failed $failure") }
            })
            return done.get(300, TimeUnit.SECONDS)
        }

        fun count(where: String, kind: ApprovalKind? = null) = asked.count { it.first == where && (kind == null || it.second == kind) }

        /** The facts every provider must show; the caller adds its own. */
        fun verify(outcome: String, name: String) {
            println("SCRATCH_PROBE $name: $outcome; handoff written: ${Files.exists(handoff)}; note written: ${Files.exists(note)}; " +
                "leak written: ${Files.exists(leak)}; asked working=${count("working")} scratch=${count("scratch")} " +
                "(not edits: ${count("scratch") - count("scratch", ApprovalKind.EDIT)}) outside=${count("outside")}")
            assertTrue(outcome.startsWith("completed"), "the turn completed: $outcome")
            assertTrue(outcome.endsWith("true"), "the scratch file was read: $outcome")
            assertEquals("handoff ok", Files.readString(handoff).trim(), "the scratch file was written")
            assertEquals("ok", Files.readString(note).trim(), "work in the working folder still goes on")
            assertFalse(Files.exists(leak), "nothing was written outside the working and scratch folders")
        }

        fun clean() { root.toFile().deleteRecursively() }
    }

    @Test fun claudeTreatsTheScratchFolderAsItsWorkspace() {
        val executable = System.getenv("HOVEL_SCRATCH_CLAUDE_PROBE")
        assumeTrue("Set HOVEL_SCRATCH_CLAUDE_PROBE to an installed, signed-in claude executable", executable != null)
        val probe = Probe("hovel-scratch-claude", withContext = false)
        try {
            val runtime = ClaudeRuntime(Path.of(requireNotNull(executable)), probe.root.resolve("providers"))
            ClaudeWorkRuntime(runtime, ClaudeConnection(runtime), probe.broker).use { claude ->
                probe.verify(probe.turn(claude, TeamMember(RuntimeId.CLAUDE_CODE, "haiku")), "claude-code")
            }
            // In its workspace it reads without asking; a write there asks no more often than in the working folder.
            assertEquals(0, probe.count("scratch") - probe.count("scratch", ApprovalKind.EDIT), "reading in scratch was not asked")
            assertTrue(probe.count("scratch", ApprovalKind.EDIT) <= maxOf(probe.count("working", ApprovalKind.EDIT), 1), "a write in scratch asks as one in the working folder does")
            assertTrue(probe.count("outside") >= 1, "the write outside both folders was asked, and denied")
        } finally { probe.clean() }
    }

    @Test fun codexWritesTheScratchFolderAndNothingElseBesideItsProject() {
        val executable = System.getenv("HOVEL_SCRATCH_CODEX_PROBE")
        assumeTrue("Set HOVEL_SCRATCH_CODEX_PROBE to an installed, signed-in codex executable", executable != null)
        // With a context folder Hovel sets the sandbox itself, so the roots are what decides, whatever the owner's own setting.
        val probe = Probe("hovel-scratch-codex", withContext = true)
        try {
            CodexWorkRuntime(probe.root.resolve("providers"), Path.of(requireNotNull(executable)), probe.broker).use { codex ->
                probe.verify(probe.turn(codex, TeamMember(RuntimeId.CODEX, "runtime-default")), "codex")
            }
        } finally { probe.clean() }
    }
}
