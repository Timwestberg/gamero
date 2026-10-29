package org.formulagen.wrapper.brain

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.providers.*
import org.formulagen.wrapper.workspace.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Opt-in authenticated inference, restricted to generated text in a disposable conversation folder. */
class DesktopBrainSmokeTest {
    @Test fun codexCoordinatesAndDelegates() = check(RuntimeId.CODEX, "HOVEL_BRAIN_CODEX_SMOKE")
    @Test fun claudeCoordinatesAndDelegates() = check(RuntimeId.CLAUDE_CODE, "HOVEL_BRAIN_CLAUDE_SMOKE")
    private fun check(id: RuntimeId, variable: String) {
        val executable = System.getenv(variable)
        assumeTrue("Set $variable for authenticated local verification", executable != null)
        val root = Files.createTempDirectory("brain-live-smoke")
        val path = Path.of(requireNotNull(executable))
        val claudeRuntime = ClaudeRuntime(path, root)
        val connection = ClaudeConnection(claudeRuntime)
        val runtime: ChatRuntime = if (id == RuntimeId.CODEX) CodexChatRuntime(root, path) else ClaudeChatRuntime(claudeRuntime, connection)
        val provider: RuntimeProvider = if (id == RuntimeId.CODEX) CodexProvider(root, path) else ClaudeProvider(connection)
        val dialogs = NativeDialogs(DialogPresenter { _, _ -> error("Text-only smoke must never request permission") })
        val speech = object : ChatSpeech { override fun status() = ChatVoice(); override fun speak(text: String) {}; override fun stop() {}; override fun close() {} }
        DesktopProviders(listOf(provider)).use { providers ->
            runtime.use {
                assertEquals(Readiness.Ready, runtime.readiness("local"))
                val models = runBlocking { providers.models(ProviderScope("local", id)).models }
                val model = if (id == RuntimeId.CLAUDE_CODE) models.first { it.modelId == "haiku" } else models.firstOrNull { it.modelId.contains("mini") } ?: models.first { it.isDefault }
                val base = testConfiguration()
                val binding = BrainBinding(id, model.modelId)
                val role = base.specialists.first().copy(binding = binding, instructions = "Write one sentence about a blue kite. No tools.")
                val config = base.copy(coordinator = base.coordinator.copy(binding = binding), specialists = listOf(role))
                val store = BrainStore(root); val companion = brainId()
                store.save("local", companion, 0, brainId(), config)
                DesktopBrain(root, store, listOf(runtime), ChatWork(WorkspaceStore(root, FolderPicker { _, _ -> null }), StartOfWork(dialogs), emptyList()), providers, dialogs, FilePicker { null }, speech, {}).use { brain ->
                    val lease = brainWire(ChatScope("local", companion, "Smoke companion", brainId())).jsonObject
                    fun call(method: String, fields: JsonObject = buildJsonObject {}) = runBlocking { brain.call(method, JsonObject(lease + fields)) }
                    call("open")
                    call("send", buildJsonObject { put("requestId", brainId()); put("expectedRevision", 1); put("text", "Ask the Writer specialist to write one sentence about a blue kite, then briefly tell me the result."); put("inputIds", JsonArray(emptyList())); put("speak", false) })
                    val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(4)
                    var snapshot: BrainSnapshot
                    do { Thread.sleep(200); snapshot = brainJson.decodeFromJsonElement(call("snapshot")) } while (snapshot.run?.state?.working == true && System.nanoTime() < deadline)
                    assertEquals(BrainRunState.COMPLETED, snapshot.run?.state, "Runtime $id ended ${snapshot.run?.state}: ${snapshot.notice}")
                    assertEquals(1, snapshot.attempts.size)
                    assertTrue(snapshot.messages.any { it.author == BrainAuthor.SPECIALIST && it.text.contains("kite", ignoreCase = true) })
                    println("BRAIN_LIVE_SMOKE: $id model=${model.modelId}; completed coordinator + independent specialist + final reply; no tools")
                }
            }
        }
    }
}
