package org.formulagen.wrapper.brain

import java.awt.EventQueue
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.swing.JFileChooser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*

/** A single leased, durable controller. Provider work and dialogs run outside the state lock. */
internal class DesktopBrain(
    private val root: Path,
    private val store: BrainStore,
    runtimes: List<ChatRuntime>,
    private val work: ChatWork,
    private val providers: ProviderCapability,
    private val dialogs: NativeDialogs,
    private val speech: ChatSpeech,
    private val closeLegacy: () -> Unit,
    private val saveWhileLegacyIdle: (String, String, () -> JsonElement) -> JsonElement = { _, _, save -> save() },
    private val ownershipGate: Any = Any(),
) : BrainCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val gate = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "companion-brain").apply { isDaemon = true } }
    private var active: Session? = null
    private class Session(val scope: ChatScope, var record: BrainRecord) {
        var cancel = CancelSignal()
        var handle: TurnHandle? = null
        var busy = false
        var speak = false
        var generation = 0L
        var coordinator: BrainRoleSnapshot? = null
    }
    private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content
    private fun scope(args: JsonObject) = ChatScope(args.string("profile"), args.string("companionId"), args.string("name"), args.string("sessionId"))
    private fun key(args: JsonObject) = args.string("profile") to args.string("companionId")
    private fun uuid() = UUID.randomUUID().toString()
    private fun live(session: Session) = active === session && !session.cancel.isCancelled
    private fun requireSession(args: JsonObject) = active?.takeIf { it.scope == scope(args) } ?: error("Expired brain lease")
    /** A cancelled execution stays cancelled even when the same chat starts another run. */
    private fun renewedSession(args: JsonObject): Session {
        val previous = requireSession(args)
        return if (!previous.cancel.isCancelled) previous else Session(previous.scope, previous.record).also {
            it.speak = previous.speak
            active = it
        }
    }
    private fun persist(session: Session, value: BrainRecord) {
        store.writeRecord(session.scope.profile, session.scope.companionId, value)
        session.record = value
    }
    private fun change(session: Session, transform: (BrainRecord) -> BrainRecord) = synchronized(gate) {
        check(live(session)) { "Expired brain execution" }
        val value = transform(session.record)
        persist(session, value.copy(run = value.run?.let { it.copy(summary = it.summary.copy(revision = it.summary.revision + 1)) }))
    }
    private fun run(session: Session) = synchronized(gate) { check(live(session)); checkNotNull(session.record.run) }
    private fun addMessage(session: Session, author: String, text: String, role: BrainRoleSnapshot? = null, activity: Activity? = null) = change(session) {
        it.copy(messages = (it.messages + BrainEntry(uuid(), author, text.take(BrainLimits.OUTPUT), role, activity)).takeLast(ChatLimits.HISTORY))
    }
    private fun setRun(session: Session, transform: (RunRecord) -> RunRecord) = change(session) { it.copy(run = transform(checkNotNull(it.run))) }
    private fun pause(session: Session, message: String, kind: String = "recovery") = change(session) {
        it.copy(run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "paused"), attempts = run.attempts.map { attempt -> if (attempt.state == "running") attempt.copy(state = "interrupted") else attempt }) }, waiting = BrainWaiting(kind, message.take(512)), notice = message.take(512))
    }

    override suspend fun call(method: String, args: JsonObject): JsonElement = withContext(Dispatchers.IO) {
        require(BrainRules.arguments(method, args))
        val (profile, companionId) = key(args)
        when (method) {
            "read" -> store.read(profile, companionId)?.let { document -> buildJsonObject { put("status", "found"); put("document", brainWire(document)); put("issues", brainWire(readiness(profile, companionId, document.configuration))) } }
                ?: buildJsonObject { put("status", "absent") }
            "save" -> {
                val result = saveWhileLegacyIdle(profile, companionId) { store.save(profile, companionId, args.getValue("expectedRevision").jsonPrimitive.long, args.string("mutationId"), brainJson.decodeFromJsonElement(args.getValue("configuration"))) }
                if (result.jsonObject["status"]?.jsonPrimitive?.content == "saved") {
                    val document = brainJson.decodeFromJsonElement<BrainDocument>(result.jsonObject.getValue("document"))
                    JsonObject(result.jsonObject + ("issues" to brainWire(readiness(profile, companionId, document.configuration))))
                } else result
            }
            "open" -> open(scope(args))
            "close" -> synchronized(gate) { active?.takeIf { it.scope == scope(args) }?.let { end(it, false); active = null }; buildJsonObject {} }
            "snapshot" -> synchronized(gate) { snapshot(requireSession(args)) }
            "send" -> send(args)
            "stop" -> synchronized(gate) { val session = requireSession(args); end(session, true); snapshot(session) }
            "stopSpeaking" -> synchronized(gate) { speech.stop(); snapshot(requireSession(args)) }
            "resume", "retry" -> resume(args, method == "retry")
            "showApproval" -> synchronized(gate) {
                val session = requireSession(args); require(session.record.run?.summary?.id == args.string("runId") && session.record.waiting?.approvalId == args.string("approvalId")); buildJsonObject {}
            }
            "addInput" -> addInput(args, args.string("text").toByteArray(), args.string("title"), "text/plain")
            "chooseInput" -> {
                synchronized(gate) { requireSession(args) }
                var file: Path? = null
                EventQueue.invokeAndWait { val chooser = JFileChooser(); chooser.dialogTitle = "Attach input to companion"; if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) file = chooser.selectedFile.toPath() }
                file?.let { path -> require(Files.isRegularFile(path) && Files.size(path) <= 8 * 1024 * 1024); val bytes = Files.readAllBytes(path); addInput(args, bytes, path.fileName.toString().take(80), if (path.toString().endsWith(".md") || path.toString().endsWith(".txt")) "text/plain" else "application/octet-stream") } ?: JsonNull
            }
            "readOutput" -> {
                val artifact = synchronized(gate) {
                    val session = requireSession(args)
                    val runId = args.string("runId")
                    val record = session.record.run?.takeIf { it.summary.id == runId } ?: store.archived(profile, companionId, runId) ?: error("Unknown run")
                    record.artifacts.find { it.id == args.string("artifactId") } ?: error("Unknown artifact")
                }
                val text = if (artifact.mediaType == "application/octet-stream") "Attached file: ${artifact.title}" else store.bytes(profile, companionId, artifact).toString(Charsets.UTF_8)
                val cursor = args.getValue("cursor").jsonPrimitive.int; require(cursor <= text.length)
                val end = minOf(text.length, cursor + BrainLimits.OUTPUT)
                buildJsonObject { put("text", text.substring(cursor, end)); put("nextCursor", if (end < text.length) JsonPrimitive(end) else JsonNull) }
            }
            else -> error("Unknown brain method")
        }
    }
    private fun open(scope: ChatScope): JsonElement = synchronized(ownershipGate) { synchronized(gate) state@{
        active?.takeIf { it.scope == scope }?.let { return@state snapshot(it) }
        active?.let { end(it, false) }; closeLegacy()
        var record = store.readRecord(scope.profile, scope.companionId)
        if (record.messages.isEmpty()) {
            val legacy = ChatHistory(root.resolve(scope.profile).resolve("companion-chats").resolve("${scope.companionId}.json")).read()
            record = record.copy(messages = legacy.entries.mapNotNull { entry -> when (entry) {
                is ChatEntry.User -> BrainEntry(entry.id, "user", entry.text)
                is ChatEntry.Assistant -> BrainEntry(entry.id, "system", "Previous conversation (${entry.author.modelId}): ${entry.text}".take(BrainLimits.OUTPUT))
                else -> null
            } }.takeLast(ChatLimits.HISTORY))
        }
        val unfinished = record.run?.takeIf { it.summary.state !in setOf("completed", "cancelled", "failed") }
        if (unfinished != null) record = record.copy(run = unfinished.copy(summary = unfinished.summary.copy(state = "paused"), attempts = unfinished.attempts.map { if (it.state == "running") it.copy(state = "interrupted") else it }), waiting = BrainWaiting("recovery", "Work is paused. Review completed outputs before resuming."))
        val session = Session(scope, record); active = session; persist(session, record)
        snapshot(session)
    } }
    private fun snapshot(session: Session): JsonElement {
        val document = store.read(session.scope.profile, session.scope.companionId)
        val coordinator = session.coordinator?.takeIf { session.busy } ?: document?.configuration?.coordinator?.let { role -> role.binding?.let { BrainRoleSnapshot(role.id, "Coordinator", it, role.instructions, MemberMode.TALK) } }
        val record = session.record
        return brainWire(BrainSnapshot(document?.revision ?: 0, coordinator, record.messages, record.run?.summary, record.run?.attempts ?: emptyList(), record.run?.artifacts ?: emptyList(), record.waiting, speech.status(), (record.run?.summary?.revision ?: 0).toString(), record.notice))
    }
    private fun send(args: JsonObject): JsonElement = synchronized(gate) {
        val session = renewedSession(args)
        val receipt = args.string("requestId")
        val digest = BrainStore.digest(args.toString().toByteArray())
        session.record.receipts[receipt]?.let { require(it == digest); return@synchronized snapshot(session) }
        require(!session.busy)
        val document = store.read(session.scope.profile, session.scope.companionId) ?: error("Configure a coordinator first")
        if (document.revision != args.getValue("expectedRevision").jsonPrimitive.long) {
            persist(session, session.record.copy(notice = "The brain changed. Review the current coordinator and send again.")); return@synchronized snapshot(session)
        }
        val inputIds = args.getValue("inputIds").jsonArray.map { it.jsonPrimitive.content }
        val available = session.record.staged + (session.record.run?.artifacts ?: emptyList())
        require(inputIds.all { id -> available.any { it.id == id } })
        val text = args.string("text")
        val existing = session.record.run
        val next = if (existing?.summary?.state == "awaiting-input") {
            existing.copy(request = "${existing.request}\nUser clarification: $text".takeLast(BrainLimits.INPUT), inputs = (existing.inputs + inputIds).distinct(), artifacts = (existing.artifacts + available.filter { it.id in inputIds }).distinctBy { it.id }, summary = existing.summary.copy(state = "planning"))
        } else {
            require(existing == null || existing.summary.state in setOf("completed", "cancelled", "failed")) { "Resume or stop the current run first" }
            existing?.let { store.archive(session.scope.profile, session.scope.companionId, it) }
            RunRecord(BrainRun(uuid(), 1, document.revision, null, "planning", null), document.configuration, work.workspaces.folder(session.scope.profile, session.scope.companionId)?.path?.toString(), text, inputIds, artifacts = available.filter { it.id in inputIds })
        }
        session.cancel = CancelSignal(); session.speak = args.getValue("speak").jsonPrimitive.boolean
        persist(session, session.record.copy(run = next, messages = (session.record.messages + BrainEntry(receipt, "user", text)).takeLast(ChatLimits.HISTORY), waiting = null, notice = null,
            receipts = (session.record.receipts + (receipt to digest)).entries.toList().takeLast(128).associate { it.toPair() }, staged = session.record.staged.filter { it.id !in inputIds }))
        launch(session); snapshot(session)
    }
    private fun addInput(args: JsonObject, bytes: ByteArray, title: String, type: String): JsonElement {
        val session = synchronized(gate) { requireSession(args) }
        val artifact = store.artifact(session.scope.profile, session.scope.companionId, title, BrainSource("user", messageId = args.string("messageId")), bytes, type)
        synchronized(gate) { require(active === session); persist(session, session.record.copy(staged = (session.record.staged + artifact).takeLast(30))) }
        return brainWire(artifact)
    }
    private fun launch(session: Session) {
        require(!session.busy); session.busy = true
        val generation = ++session.generation
        executor.execute {
            try {
                while (synchronized(gate) { live(session) && session.generation == generation && session.record.run?.summary?.state in setOf("planning", "running") }) {
                    if (run(session).phase == "execute") execute(session) else decide(session)
                }
            } catch (_: InterruptedException) { }
            catch (error: Throwable) { synchronized(gate) { if (live(session) && session.generation == generation) pause(session, "Work paused. The provider or saved state could not complete this turn. Review the result before retrying.") } }
            finally { synchronized(gate) { if (active === session && session.generation == generation) { session.busy = false; session.handle = null } } }
        }
    }
    private fun end(session: Session, cancelled: Boolean) {
        session.generation++; session.cancel.cancel(); session.handle?.stop(); session.handle = null; session.busy = false
        runtimes.values.forEach { it.release() }; work.runtimes.values.forEach { it.release() }; speech.stop()
        val run = session.record.run
        if (run != null && run.summary.state !in setOf("completed", "cancelled", "failed")) persist(session, session.record.copy(run = run.copy(summary = run.summary.copy(state = if (cancelled) "cancelled" else "paused", revision = run.summary.revision + 1), attempts = run.attempts.map { if (it.state == "running") it.copy(state = "interrupted") else it }), waiting = if (cancelled) null else BrainWaiting("recovery", "Work paused when chat closed. Review before resuming.")))
    }
    fun invalidate() = synchronized(gate) { active?.let { end(it, false) }; active = null }
    override fun close() { invalidate(); executor.shutdownNow(); speech.close() }

    /** Catalog checks never silently substitute a model or effort. */
    private suspend fun readiness(profile: String, companionId: String, configuration: BrainConfiguration): List<BrainIssue> {
        val issues = mutableListOf<BrainIssue>()
        val roles = listOf(Triple(configuration.coordinator.id, configuration.coordinator.binding, MemberMode.TALK)) + configuration.specialists.map { Triple(it.id, it.binding, it.mode) }
        // A capacity-sized brain still needs only one status/catalog read per subscription.
        val catalogs = roles.mapNotNull { it.second?.runtimeId }.distinct().associateWith { runtime ->
            val scope = ProviderScope(profile, runtime)
            val connected = runCatching { providers.perform(ProviderAction.STATUS, scope).state == ProviderState.CONNECTED }.getOrDefault(false)
            connected to if (connected) runCatching { providers.models(scope).models }.getOrNull() else null
        }
        val projectExists = work.workspaces.folder(profile, companionId)?.exists == true
        for ((id, binding, mode) in roles) {
            if (binding == null) { issues += BrainIssue("missing-binding", id); continue }
            val (connected, models) = catalogs.getValue(binding.runtimeId)
            if (!connected) { issues += BrainIssue("unavailable-provider", id); continue }
            val model = models?.find { if (binding.modelId == SharedRules.RUNTIME_DEFAULT_MODEL) it.isDefault else it.modelId == binding.modelId }
            if (model == null) issues += BrainIssue("unavailable-model", id)
            if (binding.effort != null && model?.effortLevels?.contains(binding.effort) != true) issues += BrainIssue("unsupported-effort", id)
            if (mode == MemberMode.WORK) {
                if (binding.runtimeId !in work.runtimes) issues += BrainIssue("unsupported-project-work", id)
                if (!projectExists) issues += BrainIssue("missing-project", id)
            }
        }
        configuration.workflow?.takeIf { it.enabled }?.let { workflow ->
            if (workflow.steps.isEmpty()) issues += BrainIssue("empty-workflow")
            workflow.steps.filter { it.specialistId == null }.forEach { issues += BrainIssue("missing-role", stepId = it.id) }
        }
        return issues
    }
    private fun resolved(session: Session, role: BrainRoleSnapshot): BrainRoleSnapshot {
        val models = runBlocking { providers.models(ProviderScope(session.scope.profile, role.binding.runtimeId)).models }
        val model = models.find { if (role.binding.modelId == SharedRules.RUNTIME_DEFAULT_MODEL) it.isDefault else it.modelId == role.binding.modelId } ?: error("Model unavailable")
        require(role.binding.effort == null || model.effortLevels?.contains(role.binding.effort) == true)
        return role.copy(binding = role.binding.copy(modelId = model.modelId))
    }

    private fun decide(session: Session) {
        val current = run(session)
        val document = store.read(session.scope.profile, session.scope.companionId) ?: error("Missing brain")
        val saved = document.configuration.coordinator
        val coordinator = resolved(session, BrainRoleSnapshot(saved.id, "Coordinator", checkNotNull(saved.binding), saved.instructions, MemberMode.TALK))
        synchronized(gate) { check(live(session)); session.coordinator = coordinator }
        val allowed = if (current.phase == "finish") setOf("answer", "ask") else allowedDecisions(current)
        val request = buildJsonObject {
            put("decisionId", uuid()); put("runRevision", current.summary.revision); put("allowed", JsonArray(allowed.map(::JsonPrimitive)))
            put("request", current.request); put("configuration", brainWire(current.configuration)); put("run", brainWire(current.summary)); put("attempts", brainWire(current.attempts)); put("artifacts", brainWire(current.artifacts)); put("nextStepId", current.configuration.workflow?.steps?.getOrNull(current.nextStep)?.id?.let(::JsonPrimitive) ?: JsonNull)
            put("input", handoff(session, current.artifacts.filter { it.id in current.inputs }.map { it.id })); put("latestOutput", current.attempts.lastOrNull()?.outputIds?.let { handoff(session, it) } ?: "")
            put("conversation", brainWire(synchronized(gate) { session.record.messages.takeLast(10) }))
        }.toString()
        var decision: JsonObject? = null
        repeat(2) { index ->
            if (decision == null) {
                val text = turn(session, coordinator.copy(instructions = COORDINATOR_PROTOCOL + "\nUser's coordinator instructions:\n" + coordinator.instructions), request + if (index == 1) "\nYour last response was invalid. Return exactly one allowed JSON decision." else "", coordinator = true).first
                decision = runCatching { parseDecision(text, allowed) }.getOrNull()
            }
        }
        val value = decision ?: run { pause(session, "The coordinator returned an invalid decision. No specialist was started.", "input"); return }
        when (value.string("kind")) {
            "answer" -> {
                require(current.summary.workflowId == null || current.phase == "finish")
                val text = value.string("text"); addMessage(session, "coordinator", text, coordinator)
                setRun(session) { it.copy(summary = it.summary.copy(state = "completed")) }; if (session.speak) speech.speak(text)
            }
            "ask" -> {
                addMessage(session, "coordinator", value.string("question"), coordinator)
                change(session) { it.copy(run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "awaiting-input")) }, waiting = BrainWaiting("input", value.string("question").take(512))) }
            }
            "delegate" -> {
                require(current.summary.workflowId == null)
                queue(session, null, value.string("specialistId"), value.string("task"), value.getValue("inputIds").jsonArray.map { it.jsonPrimitive.content })
            }
            "enter-workflow" -> {
                val workflow = checkNotNull(current.configuration.workflow).also { require(it.enabled) }
                val index = workflow.steps.indexOfFirst { it.id == value.string("entryStepId") }; require(index >= 0)
                val skips = brainJson.decodeFromJsonElement<List<BrainSkip>>(value.getValue("skipped"))
                require(skips.map { it.stepId }.toSet() == workflow.steps.take(index).map { it.id }.toSet() && skips.size == index)
                setRun(session) { it.copy(summary = it.summary.copy(workflowId = workflow.id, skipped = skips), nextStep = index, phase = "advance") }
                addMessage(session, "system", value.string("reason"))
                val step = workflow.steps[index]; queue(session, step, checkNotNull(step.specialistId), step.instructions, value.getValue("inputIds").jsonArray.map { it.jsonPrimitive.content })
            }
            "start-step" -> {
                val step = checkNotNull(current.configuration.workflow).steps[current.nextStep]; require(step.id == value.string("stepId"))
                queue(session, step, checkNotNull(step.specialistId), step.instructions, value.getValue("inputIds").jsonArray.map { it.jsonPrimitive.content })
            }
            "skip-step" -> {
                val workflow = checkNotNull(current.configuration.workflow); val step = workflow.steps[current.nextStep]; require(step.id == value.string("stepId"))
                addMessage(session, "system", "Skipped ${step.name}: ${value.string("reason")}")
                setRun(session) { it.copy(summary = it.summary.copy(skipped = it.summary.skipped.filter { skip -> skip.stepId != step.id } + BrainSkip(step.id, value.string("reason"))), nextStep = it.nextStep + 1, phase = if (it.nextStep + 1 == workflow.steps.size) "finish" else "advance") }
            }
            "step-result" -> result(session, value)
        }
    }
    private fun queue(session: Session, step: WorkflowStep?, specialistId: String, task: String, inputs: List<String>) {
        val current = run(session)
        val role = current.configuration.specialists.find { it.id == specialistId } ?: error("Unknown specialist")
        require(inputs.all { id -> current.artifacts.any { it.id == id && (it.source.attemptId == null || it.source.attemptId !in current.invalidated) } })
        val last = current.attempts.lastOrNull { it.id !in current.invalidated && it.state == "completed" }
        val handoff = (current.inputs + inputs + (last?.outputIds ?: emptyList())).distinct()
        val binding = role.binding ?: run { pause(session, "Assign a provider to ${role.name} in Configure brain.", "input"); return }
        val attempt = BrainAttempt(uuid(), current.summary.id, step?.id, BrainRoleSnapshot(role.id, role.name, binding, role.instructions, role.mode), current.summary.configurationRevision, handoff)
        setRun(session) { it.copy(attempts = it.attempts + attempt, summary = it.summary.copy(currentAttemptId = attempt.id, state = "running"), phase = "execute", task = task) }
    }
    private fun result(session: Session, decision: JsonObject) {
        val current = run(session); val attempt = current.attempts.last()
        require(attempt.id == decision.string("attemptId") && attempt.state == "completed")
        val outcome = decision.string("outcome")
        addMessage(session, "coordinator", decision.string("explanation"), session.coordinator)
        if (outcome == "blocked") { pause(session, decision.string("explanation"), "input"); return }
        setRun(session) { it.copy(attempts = it.attempts.map { item -> if (item.id == attempt.id) item.copy(outcome = outcome) else item }) }
        if (attempt.stepId == null) { setRun(session) { it.copy(phase = "plan", summary = it.summary.copy(state = "planning")) }; return }
        val workflow = checkNotNull(current.configuration.workflow)
        if (outcome == "changes-needed") {
            val target = workflow.steps[current.nextStep].returnTo
            if (target == null) { pause(session, "Changes are needed, but this step has no return path.", "input"); return }
            val index = workflow.steps.indexOfFirst { it.id == target.stepId }; require(index in 0 until current.nextStep)
            val stale = current.attempts.filter { item -> workflow.steps.indexOfFirst { it.id == item.stepId } >= index }.map { it.id }
            setRun(session) { it.copy(nextStep = index, phase = "advance", inputs = (it.inputs + attempt.outputIds).distinct(), invalidated = (it.invalidated + stale).distinct(), summary = it.summary.copy(state = "planning", returnsUsed = it.summary.returnsUsed + 1, skipped = it.summary.skipped.filter { skip -> workflow.steps.indexOfFirst { step -> step.id == skip.stepId } < index })) }
            addMessage(session, "system", "Return to ${workflow.steps[index].name}: ${decision.string("explanation")}")
        } else setRun(session) { it.copy(nextStep = it.nextStep + 1, phase = if (it.nextStep + 1 == workflow.steps.size) "finish" else "advance", summary = it.summary.copy(state = "planning")) }
    }

    private fun execute(session: Session) {
        val current = run(session)
        var attempt = current.attempts.last()
        if (current.summary.returnsUsed > current.returnsAllowance || current.summary.dispatchesUsed >= current.dispatchesAllowance) { pause(session, "The run reached its revision or dispatch allowance. Continue adds another bounded allowance.", "limit"); return }
        val issues = runBlocking { readiness(session.scope.profile, session.scope.companionId, current.configuration) }.filter { it.roleId == attempt.role.roleId }
        if (issues.isNotEmpty()) { pause(session, "${attempt.role.name} is not ready: ${issues.joinToString { it.reason }}. Update the brain or provider and resume.", "input"); return }
        val role = resolved(session, attempt.role)
        attempt = attempt.copy(role = role)
        setRun(session) { it.copy(attempts = it.attempts.dropLast(1) + attempt) }
        val step = current.configuration.workflow?.steps?.find { it.id == attempt.stepId }
        val approvalId = uuid()
        if (step?.askBeforeStarting == true) {
            change(session) { it.copy(run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "awaiting-approval"), attempts = run.attempts.dropLast(1) + attempt.copy(state = "awaiting-approval")) }, waiting = BrainWaiting("stage", "Approve ${step.name} with ${role.name} (${role.binding.modelId}).", approvalId, attempt.id)) }
            val choice = dialogs.ask(NativeDialog("Approve ${step.name}", listOf("Specialist: ${role.name} · ${role.binding.modelId}", "Task: ${current.task}", "Expected result: ${step.outputDescription}", "Workspace: ${current.workspace?.let { Path.of(it).fileName } ?: "Conversation only"}", "Inputs: ${handoff(session, attempt.inputIds).take(2000)}"), listOf("Continue" to "continue", "Request changes" to "changes", "Stop" to "stop"), "stop"), session.cancel)
            if (choice != "continue") {
                if (choice == "changes") change(session) { it.copy(run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "awaiting-input"), phase = if (attempt.stepId == null) "plan" else "advance", attempts = run.attempts.dropLast(1) + attempt.copy(state = "blocked")) }, waiting = BrainWaiting("input", "Describe the changes before this step starts.")) }
                else if (choice == "stop") synchronized(gate) { if (live(session)) end(session, true) }
                else pause(session, "The step was not approved. No specialist was started.", "input")
                return
            }
        }
        if (role.mode == MemberMode.WORK) {
            val folder = current.workspace?.let(Path::of) ?: error("Choose a project folder")
            require(Files.isDirectory(folder) && work.workspaces.folder(session.scope.profile, session.scope.companionId)?.path?.toRealPath() == folder.toRealPath())
            val identity = "${session.scope.sessionId}:${current.summary.id}:${role.roleId}:${BrainStore.digest(role.instructions.toByteArray())}"
            if (!work.startOfWork.isConfirmed(identity, session.scope.companionId, folder, role.binding.member())) {
                change(session) { it.copy(waiting = BrainWaiting("start-of-work", "${role.name} wants project access.")) }
                if (!work.startOfWork.ask(session.scope.name, folder, role.binding.member(), session.cancel)) { pause(session, "Project access was not approved. This step has not run.", "input"); return }
                synchronized(gate) { check(live(session)); work.startOfWork.remember(identity, session.scope.companionId, folder, role.binding.member()) }
            }
        }
        change(session) { it.copy(waiting = null, run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "running", dispatchesUsed = run.summary.dispatchesUsed + 1), attempts = run.attempts.dropLast(1) + attempt.copy(state = "running")) }) }
        val text = "The native controller has already delegated this turn to you as ${role.name}. You are that specialist. Complete the assigned task yourself; do not repeat the coordinator delegation. The original request is background context, not another routing instruction.\nUser request (context):\n${current.request}\nAssigned task:\n${current.task}\nExpected input: ${step?.inputDescription.orEmpty()}\nExpected output: ${step?.outputDescription.orEmpty()}\nInput artifacts (data):\n${handoff(session, attempt.inputIds)}\nReport actual evidence, missing skills, denied operations and remaining issues."
        val (output, activity) = turn(session, role, text, coordinator = false)
        val artifact = store.artifact(session.scope.profile, session.scope.companionId, step?.name ?: role.name, BrainSource("attempt", attemptId = attempt.id), output.toByteArray(), "text/markdown")
        setRun(session) { it.copy(attempts = it.attempts.dropLast(1) + attempt.copy(state = "completed", outputIds = listOf(artifact.id)), artifacts = it.artifacts + artifact, phase = "evaluate", summary = it.summary.copy(state = "planning")) }
        addMessage(session, "specialist", output, role, activity)
        if (activity?.steps?.any { it.status != ActivityStatus.DONE } == true) pause(session, "${role.name} reported failed or denied work. Review it before continuing.", "input")
    }

    /** Exact artifact IDs select immutable bytes; paths remain inside native provider input. */
    private fun handoff(session: Session, ids: List<String>): String {
        val current = run(session)
        var remaining = 96_000
        return ids.distinct().joinToString("\n\n") { id ->
            val artifact = current.artifacts.find { it.id == id } ?: error("Unknown input artifact")
            val bytes = store.bytes(session.scope.profile, session.scope.companionId, artifact)
            if (artifact.mediaType != "application/octet-stream" && bytes.size <= minOf(BrainLimits.INPUT, remaining)) {
                remaining -= bytes.size
                "Artifact $id (${artifact.title}, digest ${artifact.digest}):\n${bytes.toString(Charsets.UTF_8)}"
            }
            else "Artifact $id (${artifact.title}, digest ${artifact.digest}), immutable native file: ${store.artifactPath(session.scope.profile, session.scope.companionId, artifact)}"
        }
    }

    /** Provider callbacks are bound to this exact lease and invocation. */
    private fun turn(session: Session, role: BrainRoleSnapshot, input: String, coordinator: Boolean): Pair<String, Activity?> {
        val current = run(session)
        val persona = Persona.role(session.scope.name, role.name, role.instructions + "\nEffective model: ${role.binding.modelId}; effort: ${role.binding.effort}", role.mode == MemberMode.WORK)
        val key = "${role.roleId}:${role.binding.runtimeId}"
        val before = synchronized(gate) { if (coordinator) session.record.coordinatorContexts[key] else current.contexts[key] }
        val conversation = before?.takeIf { it.personaHash == persona.hash }
        val future = CompletableFuture<Pair<String, Activity?>>()
        var latestActivity: Activity? = null
        val generation = synchronized(gate) { check(live(session)); session.generation }
        val events = object : TurnEvents {
            private fun valid() = live(session) && session.generation == generation && !future.isDone
            override fun opened(conversation: String) = synchronized(gate) {
                if (!valid()) return@synchronized
                val native = NativeConversation(conversation, persona.hash, playerLogin = true)
                try { change(session) { if (coordinator) it.copy(coordinatorContexts = it.coordinatorContexts + (key to native)) else it.copy(run = it.run?.let { run -> run.copy(contexts = run.contexts + (key to native)) }) } }
                catch (failure: Exception) { future.completeExceptionally(failure) }
                Unit
            }
            override fun accepted() {}
            override fun partial(text: String) { /* Control responses remain private until validated. */ }
            override fun completed(text: String, conversation: String) { synchronized(gate) { if (valid()) { opened(conversation); future.complete(text to latestActivity) } } }
            override fun failed(failure: ChatFailure) { synchronized(gate) { if (valid()) future.completeExceptionally(IllegalStateException("Provider turn failed: ${failure::class.simpleName}")) } }
            override fun activity(activity: Activity) { synchronized(gate) { if (valid()) latestActivity = activity } }
            override fun waiting(waiting: ChatWaiting?) { synchronized(gate) { if (valid()) change(session) { it.copy(waiting = waiting?.let { value -> BrainWaiting("provider", value.summary) }) } } }
        }
        val executionId = "${session.scope.sessionId}:${current.summary.id}:${role.roleId}"
        val request = TurnRequest(session.scope.profile, session.scope.companionId, persona, role.binding.modelId, conversation, null, input,
            if (role.mode == MemberMode.WORK) WorkScope(Path.of(checkNotNull(current.workspace)), executionId, session.scope.name, role.binding.member()) else null, role.binding.effort)
        val cancellation = session.cancel.onCancel { future.completeExceptionally(InterruptedException()) }
        try {
            val handle = if (role.mode == MemberMode.WORK) {
                val worker = work.runtimes[role.binding.runtimeId] ?: error("Project execution unavailable")
                require(worker.readiness(session.scope.profile) == Readiness.Ready)
                synchronized(gate) { check(live(session) && session.generation == generation) }
                worker.startTurn(request, events)
            } else {
                val runtime = runtimes[role.binding.runtimeId] ?: error("Runtime unavailable")
                require(runtime.readiness(session.scope.profile) == Readiness.Ready)
                synchronized(gate) { check(live(session) && session.generation == generation) }
                runtime.startTurn(request, events)
            }
            synchronized(gate) { if (live(session) && session.generation == generation) session.handle = handle else handle.stop() }
            return future.get(2, TimeUnit.HOURS)
        } finally {
            cancellation.close()
            synchronized(gate) { if (active === session && session.generation == generation) session.handle?.stop() }
            if (role.mode == MemberMode.WORK) work.runtimes[role.binding.runtimeId]?.release()
            synchronized(gate) { if (active === session && session.generation == generation) session.handle = null }
        }
    }

    /** Resume/retry always reviews uncertain effects and changed configuration natively. */
    private fun resume(args: JsonObject, retry: Boolean): JsonElement = synchronized(gate) {
        val session = renewedSession(args)
        val record = checkNotNull(session.record.run)
        val requestId = args.string("requestId"); val digest = BrainStore.digest(args.toString().toByteArray())
        session.record.receipts[requestId]?.let { require(it == digest); return@synchronized snapshot(session) }
        require(!session.busy)
        require(record.summary.id == args.string("runId") && record.summary.revision == args.getValue("expectedRunRevision").jsonPrimitive.long)
        require(record.summary.state in setOf("paused", "awaiting-input", "awaiting-approval", "failed"))
        if (retry) require(record.attempts.lastOrNull()?.id == args.string("attemptId"))
        val target = args["targetRevision"]?.jsonPrimitive?.long?.let { revision -> store.read(session.scope.profile, session.scope.companionId)?.also { require(it.revision == revision) } }
        if (target != null && !adoptable(record.configuration, target.configuration)) {
            persist(session, session.record.copy(notice = "These changes alter roles or workflow structure. Stop this run and start a new request to use them; finished outputs are preserved."))
            return@synchronized snapshot(session)
        }
        session.cancel = CancelSignal(); session.busy = true
        persist(session, session.record.copy(receipts = (session.record.receipts + (requestId to digest)).entries.toList().takeLast(128).associate { it.toPair() }))
        val previousWaiting = session.record.waiting
        change(session) { it.copy(waiting = BrainWaiting("recovery", "Review the native resume dialog."), run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "awaiting-approval")) }) }
        val generation = ++session.generation
        executor.execute {
            try {
                val uncertain = record.attempts.lastOrNull()?.state in setOf("running", "interrupted", "failed")
                val lines = mutableListOf("Finished outputs are preserved.")
                if (uncertain || retry) lines += "The last attempt may have changed files or external state. Continuing retries that work and may repeat effects. Verify its result first."
                if (target != null) {
                    lines += "Adopt configuration revision ${target.revision}. Pending approvals will be requested again."
                    target.configuration.specialists.forEach { next -> val old = record.configuration.specialists.find { it.id == next.id }; if (next != old) lines += "${next.name}: ${old?.binding?.modelId} → ${next.binding?.modelId}; instructions: ${next.instructions}" }
                    target.configuration.workflow?.steps?.forEach { next -> if (next != record.configuration.workflow?.steps?.find { it.id == next.id }) lines += "${next.name}: approval ${next.askBeforeStarting}; instructions ${next.instructions}" }
                }
                if (previousWaiting?.kind == "limit") lines += "Continue allows ${BrainLimits.RETURNS} more returns and ${BrainLimits.DISPATCHES} more attempts."
                val approved = dialogs.ask(NativeDialog("Resume companion work", lines, listOf("Continue" to true, "Keep paused" to false), false), session.cancel) == true
                synchronized(gate) {
                    if (live(session) && session.generation == generation) {
                        session.busy = false
                        if (approved) {
                            val config = target?.configuration ?: record.configuration
                            val last = record.attempts.lastOrNull()
                            val retryAttempt = (uncertain || retry) && last != null
                            val phase = if (retryAttempt) "execute" else record.phase
                            val attempts = if (phase == "execute" && last != null) {
                                val specialist = config.specialists.first { it.id == last.role.roleId }
                                record.attempts + last.copy(id = uuid(), state = "queued", outputIds = emptyList(), outcome = null, role = last.role.copy(binding = checkNotNull(specialist.binding), instructions = specialist.instructions), configurationRevision = target?.revision ?: record.summary.configurationRevision)
                            } else record.attempts
                            change(session) { it.copy(waiting = null, notice = null, run = record.copy(configuration = config, phase = phase, attempts = attempts, task = if (phase == "execute" && last?.stepId != null) config.workflow?.steps?.find { it.id == last.stepId }?.instructions ?: record.task else record.task, summary = record.summary.copy(revision = session.record.run!!.summary.revision,configurationRevision = target?.revision ?: record.summary.configurationRevision, state = "planning", currentAttemptId = attempts.lastOrNull()?.id), returnsAllowance = record.summary.returnsUsed + BrainLimits.RETURNS, dispatchesAllowance = record.summary.dispatchesUsed + BrainLimits.DISPATCHES)) }
                            launch(session)
                        } else change(session) { it.copy(waiting = previousWaiting, run = it.run?.let { run -> run.copy(summary = run.summary.copy(state = "paused")) }) }
                    }
                }
            } catch (_: Throwable) { synchronized(gate) { if (active === session && session.generation == generation) { session.busy = false; if (live(session)) pause(session, "Resume could not complete. The run remains paused.") } } }
        }
        snapshot(session)
    }
}

/** Paused runs may adopt settings, never silently change their workflow structure. */
internal fun adoptable(before: BrainConfiguration, after: BrainConfiguration): Boolean {
    fun structure(value: BrainConfiguration) = value.copy(coordinator = value.coordinator.copy(binding = null, instructions = ""),
        specialists = value.specialists.map { it.copy(binding = null, instructions = "") },
        workflow = value.workflow?.let { workflow -> workflow.copy(steps = workflow.steps.map { it.copy(instructions = "", askBeforeStarting = false) }) })
    return structure(before) == structure(after)
}
