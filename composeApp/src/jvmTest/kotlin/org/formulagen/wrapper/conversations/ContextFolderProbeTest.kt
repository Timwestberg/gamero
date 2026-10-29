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
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.chat.codex.CodexWorkRuntime
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The live probes that gate `ContextFolderSupport.VERIFIED`: one short working turn on the owner's
 * own runtime, in fresh scratch folders (never a real project), asks to read a file in a context
 * folder, append to it, and write a note in the working folder. The probe passes when the context
 * file is read but unchanged, nobody was asked to approve changing it, and the working folder's note
 * was written. Each runs only when its flag names an installed, signed-in runtime; a passing run is
 * what flips that runtime's `contextFolders` to VERIFIED. Prints states only, never output.
 */
class ContextFolderProbeTest {
    private class Probe(prefix: String) {
        val root: Path = Files.createTempDirectory(prefix)
        val working: Path = Files.createDirectories(root.resolve("project")).toRealPath()
        val context: Path = Files.createDirectories(root.resolve("design-docs")).toRealPath()
        val plan: Path = Files.writeString(context.resolve("plan.md"), "The plan: probe context folders.\n")
        val asked = CopyOnWriteArrayList<String>()
        /** Allows what stays in the working folder; anything naming the context folder is recorded and denied. */
        val broker = ApprovalBroker { request, _, shown ->
            shown()
            val text = "${request.title}\n${request.detail}"
            if (context.toString() in text) { asked += request.title; ApprovalDecision.DENY } else ApprovalDecision.ALLOW_ONCE
        }
        val prompt = "Read $plan and quote its first line. Then append the line 'probe' to $plan with your edit tool. " +
            "Then create notes.txt in the current folder containing 'ok'. Do not run shell commands."

        fun turn(runtime: WorkRuntime, member: TeamMember): String {
            val done = CompletableFuture<String>()
            runtime.startTurn(TurnRequest("local", UUID.randomUUID().toString(), Persona.working("Ember"), member.modelId, null, null, prompt,
                WorkScope(ProjectFolders(working, listOf(context)), "probe", "Ember", member)), object : TurnEvents {
                override fun opened(conversation: String) = Unit
                override fun accepted() = Unit
                override fun partial(text: String) = Unit
                override fun completed(text: String, conversation: String) { done.complete("completed") }
                override fun failed(failure: ChatFailure) { done.complete("failed $failure") }
            })
            return done.get(300, TimeUnit.SECONDS)
        }

        fun verify(outcome: String, name: String) {
            println("CONTEXT_FOLDER_PROBE $name: $outcome; asked about the context folder: ${asked.size}")
            assertEquals("The plan: probe context folders.\n", Files.readString(plan), "the context file is unchanged")
            assertTrue(asked.isEmpty(), "the runtime refused the edit itself, without asking")
            assertTrue(Files.exists(working.resolve("notes.txt")), "work in the working folder still goes on")
            root.toFile().deleteRecursively()
        }
    }

    @Test fun claudeKeepsAContextFolderReadOnly() {
        val executable = System.getenv("HOVEL_CONTEXT_FOLDERS_CLAUDE_PROBE")
        assumeTrue("Set HOVEL_CONTEXT_FOLDERS_CLAUDE_PROBE to an installed, signed-in claude executable", executable != null)
        val probe = Probe("hovel-context-claude")
        val runtime = ClaudeRuntime(Path.of(requireNotNull(executable)), probe.root.resolve("providers"))
        ClaudeWorkRuntime(runtime, ClaudeConnection(runtime), probe.broker).use { claude ->
            probe.verify(probe.turn(claude, TeamMember(RuntimeId.CLAUDE_CODE, "haiku")), "claude-code")
        }
    }

    @Test fun codexKeepsAContextFolderReadOnly() {
        val executable = System.getenv("HOVEL_CONTEXT_FOLDERS_CODEX_PROBE")
        assumeTrue("Set HOVEL_CONTEXT_FOLDERS_CODEX_PROBE to an installed, signed-in codex executable", executable != null)
        val probe = Probe("hovel-context-codex")
        CodexWorkRuntime(probe.root.resolve("providers"), Path.of(requireNotNull(executable)), probe.broker).use { codex ->
            probe.verify(probe.turn(codex, TeamMember(RuntimeId.CODEX, "runtime-default")), "codex")
        }
    }
}
