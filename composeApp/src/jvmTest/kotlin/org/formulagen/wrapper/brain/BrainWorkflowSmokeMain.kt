package org.formulagen.wrapper.brain

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.chat.codex.CodexWorkRuntime
import org.formulagen.wrapper.providers.*
import org.formulagen.wrapper.workspace.*

/** Opt-in desktop validation using real provider adapters and the production native dialog host. */
fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "Hovel brain workflow validation",
        state = rememberWindowState(width = 640.dp, height = 220.dp)) {
        val presenter = remember { ComposeDialogPresenter { window } }
        var status by remember { mutableStateOf("Starting a disposable project workflow. Native prompts require a decision.") }
        LaunchedEffect(Unit) {
            thread(name = "brain-workflow-smoke") {
                runCatching {
                    val previous = System.getenv("HOVEL_BRAIN_VERIFY_ROOT")
                    if (previous == null) projectSmoke(presenter) { status = it }
                    else reopenSmoke(Path.of(previous), presenter)
                }
                    .onSuccess { status = "PASS: requested native validation completed. Close this window."; println("BRAIN_NATIVE_VALIDATION PASS") }
                    .onFailure { status = "FAILED: ${it.message}"; it.printStackTrace() }
            }
        }
        MaterialTheme { Text(status); NativeDialogHost(presenter) }
    }
}

/** The only requested effects are local Markdown files in a newly generated temporary folder. */
private fun projectSmoke(presenter: DialogPresenter, progress: (String) -> Unit) {
    val providerId = if (System.getenv("HOVEL_BRAIN_WORKFLOW_PROVIDER") == "codex") RuntimeId.CODEX else RuntimeId.CLAUDE_CODE
    val executable = Path.of(requireNotNull(System.getenv("HOVEL_BRAIN_WORKFLOW_EXECUTABLE")))
    val root = Files.createTempDirectory("hovel-brain-project-smoke")
    val project = Files.createDirectory(root.resolve("disposable-project"))
    Files.writeString(project.resolve("AGENTS.md"), "This is a disposable smoke test. Only create or read Markdown files in this folder. Do not use networks, git, connectors, skills, or external paths. Use the assigned stage instructions.\n")
    Files.writeString(project.resolve("CLAUDE.md"), Files.readString(project.resolve("AGENTS.md")))
    println("BRAIN_PROJECT_SMOKE provider=$providerId root=$root")
    val dialogs = NativeDialogs(presenter)
    val broker = DialogApprovalBroker(dialogs)
    val claude = ClaudeRuntime(executable, root)
    val connection = ClaudeConnection(claude)
    val runtime: ChatRuntime = if (providerId == RuntimeId.CODEX) CodexChatRuntime(root, executable) else ClaudeChatRuntime(claude, connection)
    val worker: WorkRuntime = if (providerId == RuntimeId.CODEX) CodexWorkRuntime(root, executable, broker) else ClaudeWorkRuntime(claude, connection, broker)
    // Opt-in diagnostics stay in this disposable harness, not the user's conversation transcript.
    val observedWorker = object : WorkRuntime by worker {
        override fun readiness(profile: String): Readiness = try {
            worker.readiness(profile).also { println("BRAIN_PROJECT_SMOKE worker readiness=${it::class.simpleName}") }
        } catch (failure: Exception) { failure.printStackTrace(); throw failure }
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle = try {
            worker.startTurn(turn, events)
        } catch (failure: Exception) { failure.printStackTrace(); throw failure }
    }
    val provider: RuntimeProvider = if (providerId == RuntimeId.CODEX) CodexProvider(root, executable) else ClaudeProvider(connection)
    val speech = object : ChatSpeech { override fun status() = ChatVoice(); override fun speak(text: String) {}; override fun stop() {}; override fun close() {} }
    val scope = ChatScope("local", brainId(), "Workflow smoke", brainId())
    DesktopProviders(listOf(provider), setOf(providerId)).use { providers -> runtime.use { worker.use {
        check(runtime.readiness("local") == Readiness.Ready)
        val models = runBlocking { providers.models(ProviderScope("local", providerId)).models }
        val model = if (providerId == RuntimeId.CLAUDE_CODE) models.first { it.modelId == "haiku" } else models.first { it.isDefault }
        val binding = BrainBinding(providerId, model.modelId)
        val tasks = listOf(
            "Planner" to "Create plan.md with a concise plan for a Markdown greeting document. The greeting must say Hello from the coordinated brain.",
            "Architect" to "Read plan.md and create architecture.md specifying greeting.md containing exactly Hello from the coordinated brain.",
            "Implementer" to "Read architecture.md and create greeting.md containing exactly Hello from the coordinated brain.",
            "Reviewer" to "Read greeting.md. Verify its exact text is Hello from the coordinated brain. Create review.md with PASS if it matches, otherwise report changes needed.",
            "Merger" to "This disposable test does not merge git. Read review.md; when it says PASS, create ready.md containing Ready for merge. Report the verified evidence."
        )
        val roles = tasks.mapIndexed { index, (name, _) -> SpecialistRole(brainId(), name, "Follow this stage's task. Only operate inside the disposable project. After each write, read the file back and report its exact verified contents. Do not use git, network, external paths or unrelated tools.", MemberMode.WORK, binding, index) }
        val steps = tasks.mapIndexed { index, (name, task) -> WorkflowStep(brainId(), name, roles[index].id, task, "Prior stage files and artifacts", "The requested Markdown file exists with the specified content", index == 2 || index == 4, null, null) }.toMutableList()
        steps[3] = steps[3].copy(returnTo = BrainReturn(steps[2].id, "Greeting does not match the requirement"))
        val config = BrainConfiguration(1, CoordinatorRole(brainId(), binding, "Run every stage of the enabled workflow in order for this disposable test. Never skip a stage. Finish after every output is verified. No real merge is requested."), roles, BrainWorkflow(brainId(), "Feature development smoke", true, steps, emptyList()))
        val store = BrainStore(root)
        store.save(scope.profile, scope.companionId, 0, brainId(), config)
        val workspaces = WorkspaceStore(root, FolderPicker { _, _ -> project })
        workspaces.choose(scope.profile, scope.companionId)
        DesktopBrain(root, store, listOf(runtime), ChatWork(workspaces, StartOfWork(dialogs), listOf(observedWorker)), providers, dialogs, FilePicker { null }, speech, {}).use { brain ->
            fun call(method: String, fields: JsonObject = buildJsonObject {}) = runBlocking { brain.call(method, JsonObject(brainWire(scope).jsonObject + fields)) }
            call("open")
            call("send", buildJsonObject { put("requestId", brainId()); put("expectedRevision", 1); put("text", "Run the entire five-stage workflow to create and review the greeting document in this disposable project. Use each configured stage in order. There is no git merge in this test."); put("inputIds", JsonArray(emptyList())); put("speak", false) })
            val deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(20)
            var snapshot: BrainSnapshot
            do {
                Thread.sleep(300)
                snapshot = brainJson.decodeFromJsonElement(call("snapshot"))
                progress("$providerId · ${snapshot.run?.state} · ${snapshot.attempts.lastOrNull()?.role?.name.orEmpty()}\n${snapshot.waiting?.summary.orEmpty()}\nProject: $project")
            } while (snapshot.run?.state in setOf("planning", "running", "awaiting-approval") && System.nanoTime() < deadline)
            Files.writeString(root.resolve("snapshot.json"), call("snapshot").toString())
            check(snapshot.run?.state == "completed") { "Ended ${snapshot.run?.state}: ${snapshot.notice ?: snapshot.waiting?.summary}" }
            check(snapshot.attempts.count { it.state == "completed" } == 5)
            check(snapshot.attempts.map { it.role.roleId } == roles.map { it.id })
            check(Files.readString(project.resolve("greeting.md")).trim().removeSuffix(".") == "Hello from the coordinated brain")
            check(Files.readString(project.resolve("review.md")).contains("PASS"))
            check(Files.readString(project.resolve("ready.md")).contains("Ready for merge"))
            snapshot.artifacts.forEach { check(store.bytes(scope.profile, scope.companionId, it).isNotEmpty()) }
            call("close"); call("open")
            check(brainJson.decodeFromJsonElement<BrainSnapshot>(call("snapshot")).run?.state == "completed")
            check(BrainStore(root).readRecord(scope.profile, scope.companionId).run?.attempts?.size == 5)
            println("BRAIN_PROJECT_SMOKE verified model=${model.modelId} roles=5 artifacts=${snapshot.artifacts.size} project=$project")
        }
    } } }
}

/** A new OS process opens saved work without dispatching or replacing its artifacts. */
private fun reopenSmoke(root: Path, presenter: DialogPresenter) {
    val companions = root.resolve("local/companion-brains")
    val companion = Files.list(companions).use { it.toList().single().fileName.toString() }
    val store = BrainStore(root)
    val before = store.readRecord("local", companion)
    val original = checkNotNull(before.run)
    val dialogs = NativeDialogs(presenter)
    val speech = object : ChatSpeech { override fun status() = ChatVoice(); override fun speak(text: String) {}; override fun stop() {}; override fun close() {} }
    DesktopProviders(emptyList()).use { providers ->
        DesktopBrain(root, store, emptyList(), ChatWork(WorkspaceStore(root, FolderPicker { _, _ -> error("No folder picker during recovery") }), StartOfWork(dialogs), emptyList()), providers, dialogs, FilePicker { null }, speech, {}).use { brain ->
            val scope = brainWire(ChatScope("local", companion, "Workflow smoke", brainId())).jsonObject
            // Reopening pauses unfinished work, except a run that was already waiting for the user's message.
            val expected = if (original.summary.state in setOf("completed", "cancelled", "failed", "awaiting-input")) original.summary.state else "paused"
            runBlocking { brain.call("open", scope) }
            repeat(10) {
                Thread.sleep(200)
                val recovered = brainJson.decodeFromJsonElement<BrainSnapshot>(runBlocking { brain.call("snapshot", scope) })
                check(recovered.run?.state == expected)
                check(recovered.attempts.size == original.attempts.size)
                check(recovered.artifacts == original.artifacts)
                recovered.artifacts.forEach { artifact -> check(store.bytes("local", companion, artifact).isNotEmpty()) }
            }
            println("BRAIN_RESTART_SMOKE state=$expected attempts=${original.attempts.size} artifacts=${original.artifacts.size} root=$root")
        }
    }
}
