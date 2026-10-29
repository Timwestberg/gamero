package org.formulagen.wrapper.brain

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.workspace.*
import kotlin.test.*

class DesktopBrainTest {
    private class Runtime : ChatRuntime {
        override val id = RuntimeId.CODEX
        val turns = CopyOnWriteArrayList<TurnRequest>()
        var malformed = false
        var returnForChanges = false
        var enterAtSecond = false
        var longOutput = false
        var hold = false
        /** Answers a coordinator turn with its own decision; null falls back to the default script. */
        @Volatile var decide: ((JsonObject) -> String?)? = null
        /** The next specialist turn fails with this instead of answering. */
        @Volatile var failWorker: ChatFailure? = null
        @Volatile var held: TurnEvents? = null
        var checking: CountDownLatch? = null
        var releaseCheck: CountDownLatch? = null
        override fun readiness(profile: String): Readiness {
            checking?.countDown()
            releaseCheck?.await(5, TimeUnit.SECONDS)
            return Readiness.Ready
        }
        override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
            turns += turn
            if (hold) { held = events; return TurnHandle {} }
            val thread = turn.conversation?.id ?: brainId()
            events.opened(thread); events.accepted()
            val coordinator = turn.persona.instructions.contains("Decision shapes:")
            if (!coordinator) failWorker?.let { failWorker = null; events.failed(it); return TurnHandle {} }
            val response = if (coordinator) {
                if (malformed) "not JSON" else {
                    val input = brainJson.parseToJsonElement(turn.text).jsonObject
                    decide?.invoke(input) ?: run {
                        val allowed = input.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }
                        val configuration = brainJson.decodeFromJsonElement<BrainConfiguration>(input.getValue("configuration"))
                        val attempts = input.getValue("attempts").jsonArray
                        when {
                            "step-result" in allowed -> """{"kind":"step-result","attemptId":"${attempts.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"${if (returnForChanges && attempts.last().jsonObject["stepId"]?.jsonPrimitive?.content == configuration.workflow?.steps?.last()?.id) "changes-needed" else "ready"}","explanation":"Output is ready."}"""
                            "start-step" in allowed -> """{"kind":"start-step","stepId":${input.getValue("nextStepId")},"inputIds":[]}"""
                            "enter-workflow" in allowed -> """{"kind":"enter-workflow","entryStepId":"${configuration.workflow!!.steps[if (enterAtSecond) 1 else 0].id}","inputIds":[],"skipped":${if (enterAtSecond) """[{"stepId":"${configuration.workflow.steps.first().id}","reason":"The supplied plan is ready."}]""" else "[]"},"reason":"Start writing."}"""
                            "delegate" in allowed && attempts.isEmpty() -> delegate(configuration)
                            else -> """{"kind":"answer","text":"Finished the requested work."}"""
                        }
                    }
                }
            } else if (longOutput) "Output ".repeat(4000) else "Completed ${turn.persona.instructions.substringAfter("acting as ").substringBefore('.')}."
            events.completed(response, thread)
            return TurnHandle {}
        }
        override fun summarize(request: SummaryRequest) = "Summary"
        override fun release() {}
        override fun close() {}
    }
    private class Speech : ChatSpeech {
        val spoken = mutableListOf<String>()
        override fun status() = ChatVoice()
        override fun speak(text: String) { spoken += text }
        override fun stop() {}
        override fun close() {}
    }
    private class Providers : ProviderCapability {
        @Volatile var connected = true
        override suspend fun perform(action: ProviderAction, scope: ProviderScope) = ProviderStatus(scope.runtimeId, if (connected) ProviderState.CONNECTED else ProviderState.DISCONNECTED)
        override suspend fun list(profile: String) = ProviderList(listOf(RuntimeId.CODEX), emptyList())
        override suspend fun listForClient(args: ProviderListForClientArgs) = list(args.profile)
        override suspend fun models(scope: ProviderScope) = ProviderModels(listOf(ProviderModel("Test model", true, "test-model", listOf(ModelEffort.HIGH))), scope.runtimeId)
        override suspend fun usage(scope: ProviderScope) = ProviderUsage(scope.runtimeId, 0)
        override suspend fun setup(scope: ProviderScope) = ProviderSetup(scope.runtimeId, ProviderSetupState.READY)
        override suspend fun startLogin(scope: ProviderScope) = ProviderLogin(scope.runtimeId, false)
    }
    private class Fixture(workflow: Boolean = false, transform: (BrainConfiguration) -> BrainConfiguration = { it }, answer: Int = 0, open: Boolean = true) : AutoCloseable {
        val root: Path = Files.createTempDirectory("brain-controller")
        val store = BrainStore(root)
        val configuration = transform(testConfiguration(workflow))
        val runtime = Runtime()
        val speech = Speech()
        val providers = Providers()
        val scope = ChatScope("local", brainId(), "Ember", brainId())
        /** Every native dialog shown, in order; [answer] picks each one's button. */
        val shown = CopyOnWriteArrayList<NativeDialog<*>>()
        @Volatile var answer: (NativeDialog<*>) -> Int = { answer }
        @Volatile var chosen: Path? = null
        val dialogs = NativeDialogs(DialogPresenter { dialog, reply -> shown += dialog; reply(answer(dialog)); {} })
        val controller = DesktopBrain(root, store, listOf(runtime), ChatWork(WorkspaceStore(root, FolderPicker { _, _ -> null }), StartOfWork(dialogs), emptyList()), providers, dialogs,
            FilePicker { chosen }, speech, {})
        init { store.save(scope.profile, scope.companionId, 0, brainId(), configuration); if (open) call("open") }
        fun call(method: String, more: JsonObject = buildJsonObject {}) = runBlocking { controller.call(method, JsonObject(brainWire(scope).jsonObject + more)) }
        fun send(requestId: String = brainId(), text: String = "Please write this", inputIds: List<String> = emptyList()) = call("send", buildJsonObject {
            put("requestId", requestId); put("expectedRevision", 1); put("text", text); put("inputIds", JsonArray(inputIds.map(::JsonPrimitive))); put("speak", true)
        })
        fun snapshot() = brainJson.decodeFromJsonElement<BrainSnapshot>(call("snapshot"))
        fun await(state: BrainRunState): BrainSnapshot {
            repeat(500) { val value = snapshot(); if (value.run?.state == state) return value; Thread.sleep(10) }
            error("State not reached: $state, ${call("snapshot")}")
        }
        fun resume(run: BrainRun, more: JsonObject = buildJsonObject {}, method: String = "resume") =
            call(method, JsonObject(buildJsonObject { put("runId", run.id); put("expectedRunRevision", run.revision); put("requestId", brainId()) } + more))
        fun workers() = runtime.turns.filterNot { it.persona.instructions.contains("Decision shapes:") }
        override fun close() { controller.close() }
    }
    private fun withAllStagesApproved(config: BrainConfiguration) = config.copy(workflow = config.workflow!!.let { it.copy(steps = it.steps.map { step -> step.copy(askBeforeStarting = true) }) })

    @Test fun workflowKeepsRolesSeparateAndOnlySpeaksValidatedCoordinatorText() = Fixture(true).use { fixture ->
        fixture.send(); val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(2, value.attempts.size); assertEquals(2, value.artifacts.size)
        assertEquals(fixture.configuration.specialists.map { it.id }, value.attempts.map { it.role.roleId })
        val workers = fixture.workers()
        assertEquals(2, workers.size); assertTrue(workers.all { it.conversation == null })
        assertEquals(listOf("Finished the requested work."), fixture.speech.spoken)
        assertTrue(value.messages.none { it.text.contains("\"kind\"") })
        val saved = fixture.store.readRecord("local", fixture.scope.companionId)
        assertEquals(2, saved.run?.contexts?.size)
    }
    @Test fun repeatingSendDoesNotLaunchAgainAndMalformedControlCannotDispatch() = Fixture().use { fixture ->
        val requestId = brainId(); fixture.send(requestId); fixture.await(BrainRunState.COMPLETED)
        val count = fixture.runtime.turns.size; fixture.send(requestId); assertEquals(count, fixture.runtime.turns.size)
    }
    @Test fun malformedDecisionWaitsForTheUserWithoutAnySpecialist() = Fixture().use { fixture ->
        fixture.runtime.malformed = true; fixture.send(); val value = fixture.await(BrainRunState.AWAITING_INPUT)
        assertEquals(2, fixture.runtime.turns.size); assertTrue(value.attempts.isEmpty())
        assertEquals(BrainWaitingKind.INPUT, value.waiting?.kind)
    }
    @Test fun closeStopsTheLeaseAndLateCallbacksCannotChangeHistory() = Fixture().use { fixture ->
        fixture.runtime.hold = true; fixture.send()
        repeat(100) { if (fixture.runtime.held == null) Thread.sleep(10) }
        fixture.call("close")
        val before = fixture.store.readRecord("local", fixture.scope.companionId)
        fixture.runtime.held?.completed("Forged late result", brainId())
        assertEquals(before, fixture.store.readRecord("local", fixture.scope.companionId))
        assertEquals(BrainRunState.PAUSED, before.run?.summary?.state)
    }
    @Test fun stopDuringReadinessCannotDispatchIntoTheNextRun() = Fixture().use { fixture ->
        val checking = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.runtime.checking = checking; fixture.runtime.releaseCheck = release
        fixture.send(); assertTrue(checking.await(5, TimeUnit.SECONDS))
        fixture.call("stop")
        fixture.runtime.checking = null; fixture.runtime.releaseCheck = null
        fixture.send()
        release.countDown()
        fixture.await(BrainRunState.COMPLETED)
        assertEquals(4, fixture.runtime.turns.size)
        assertEquals(1, fixture.store.readRecord("local", fixture.scope.companionId).run!!.attempts.size)
    }
    @Test fun midwayEntryRequiresReasonsAndRunsOnlyTheRemainingStages() = Fixture(true).use { fixture ->
        fixture.runtime.enterAtSecond = true; fixture.send(); val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(1, value.attempts.size)
        assertEquals(fixture.configuration.workflow!!.steps.first().id, value.run!!.skipped.single().stepId)
        assertEquals(fixture.configuration.workflow.steps.last().id, value.attempts.single().stepId)
    }
    @Test fun nativeStageStopCannotDispatchAWorker() = Fixture(true, ::withAllStagesApproved, answer = 3).use { fixture ->
        fixture.send(); val value = fixture.await(BrainRunState.CANCELLED)
        assertEquals(1, fixture.runtime.turns.size)
        assertEquals(0, value.run!!.dispatchesUsed)
    }
    @Test fun escapeOnAStageDialogPausesTheRunInsteadOfStoppingIt() = Fixture(true, ::withAllStagesApproved).use { fixture ->
        fixture.answer = { it.dismissIndex }
        fixture.send(); val value = fixture.await(BrainRunState.PAUSED)
        assertEquals(StageChoice.PAUSE, fixture.shown.single().dismiss)
        assertEquals(BrainWaitingKind.RECOVERY, value.waiting?.kind)
        assertEquals(BrainAttemptState.QUEUED, value.attempts.single().state)
        assertTrue(fixture.workers().isEmpty())
    }
    @Test fun adHocDelegationToAWorkflowSpecialistAsksLikeItsStage() = Fixture(true, { config ->
        config.copy(workflow = config.workflow!!.let { it.copy(steps = listOf(it.steps[0], it.steps[1].copy(specialistId = it.steps[0].specialistId, askBeforeStarting = true))) })
    }, answer = 3).use { fixture ->
        fixture.runtime.decide = { input -> if ("delegate" in input.getValue("allowed").toString()) delegate(fixture.configuration) else null }
        fixture.send(); val value = fixture.await(BrainRunState.CANCELLED)
        assertEquals("Approve Writer's task", fixture.shown.single().title)
        assertNull(value.attempts.single().stepId)
        assertTrue(fixture.workers().isEmpty())
    }
    @Test fun requestingChangesAtAStageWaitsForThemAndStartsNothing() = Fixture(true, ::withAllStagesApproved, answer = 1).use { fixture ->
        fixture.send(); val waiting = fixture.await(BrainRunState.AWAITING_INPUT)
        assertEquals(BrainWaitingKind.INPUT, waiting.waiting?.kind); assertEquals(BrainAttemptState.BLOCKED, waiting.attempts.single().state)
        assertEquals(0, waiting.run!!.dispatchesUsed); assertTrue(fixture.workers().isEmpty())
        fixture.answer = { 0 }
        fixture.send(text = "Use a friendlier tone"); val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(listOf(BrainAttemptState.BLOCKED, BrainAttemptState.COMPLETED, BrainAttemptState.COMPLETED), value.attempts.map { it.state })
        assertTrue(fixture.workers().first().text.contains("User clarification: Use a friendlier tone"))
    }
    @Test fun revisionLoopStopsBeforeTheFourthAutomaticReturn() = Fixture(true, { config ->
        config.copy(workflow = config.workflow!!.let { it.copy(steps = listOf(it.steps[0], it.steps[1].copy(returnTo = BrainReturn(it.steps[0].id, "Changes needed")))) })
    }).use { fixture ->
        fixture.runtime.returnForChanges = true; fixture.send(); val value = fixture.await(BrainRunState.PAUSED)
        assertEquals(BrainWaitingKind.LIMIT, value.waiting?.kind)
        assertEquals(8, value.run!!.dispatchesUsed)
        assertEquals(8, value.attempts.count { it.state == BrainAttemptState.COMPLETED })
        assertTrue(fixture.store.readRecord("local", fixture.scope.companionId).run!!.invalidated.isNotEmpty())
    }
    @Test fun projectRolesNeverSilentlyFallBackToTalk() = Fixture(false, { config -> config.copy(specialists = config.specialists.map { it.copy(mode = MemberMode.WORK) }) }).use { fixture ->
        fixture.send(); val value = fixture.await(BrainRunState.PAUSED)
        assertEquals(1, fixture.runtime.turns.size); assertEquals(0, value.run!!.dispatchesUsed)
        assertTrue(value.waiting!!.summary.contains("not ready"))
    }
    @Test fun artifactsRetainFullOutputBeyondTranscriptPreviewLimit() = Fixture().use { fixture ->
        fixture.runtime.longOutput = true; fixture.send(); val value = fixture.await(BrainRunState.COMPLETED)
        val artifact = value.artifacts.single()
        assertEquals(28000, fixture.store.bytes("local", fixture.scope.companionId, artifact).size)
        assertEquals(BrainLimits.OUTPUT, value.messages.first { it.author == BrainAuthor.SPECIALIST }.text.length)
    }
    @Test fun interruptedRunsReopenPausedAndRejectReusedRequestContent() = Fixture().use { fixture ->
        fixture.runtime.hold = true; val id = brainId(); fixture.send(id)
        fixture.call("close"); fixture.call("open")
        assertEquals(BrainRunState.PAUSED, fixture.await(BrainRunState.PAUSED).run?.state)
        assertFails { fixture.call("send", buildJsonObject { put("requestId", id); put("expectedRevision", 1); put("text", "Different request"); put("inputIds", JsonArray(emptyList())); put("speak", true) }) }; Unit
    }

    @Test fun savingDuringATurnPinsSpecialistsButChangesTheNextCoordinatorInvocation() = Fixture().use { fixture ->
        fixture.runtime.hold = true; fixture.send()
        repeat(100) { if (fixture.runtime.held == null) Thread.sleep(10) }
        val changed = fixture.configuration.copy(coordinator = fixture.configuration.coordinator.copy(instructions = "New coordinator instruction"), specialists = fixture.configuration.specialists.map { it.copy(instructions = "New specialist instruction") })
        fixture.store.save("local", fixture.scope.companionId, 1, brainId(), changed)
        fixture.runtime.hold = false
        fixture.runtime.held!!.completed(delegate(fixture.configuration), brainId())
        val result = fixture.await(BrainRunState.COMPLETED)
        assertEquals(1L, result.run!!.configurationRevision)
        assertEquals(2L, result.configurationRevision)
        assertEquals(fixture.configuration.specialists.first().instructions, result.attempts.single().role.instructions)
        assertTrue(fixture.runtime.turns.last().persona.instructions.contains("New coordinator instruction"))
    }
    @Test fun recoveryDenialPreservesTheRunAndIdempotentResumeCannotPromptTwice() = Fixture(answer = 1).use { fixture ->
        fixture.runtime.hold = true; fixture.send(); fixture.call("close"); fixture.call("open")
        val paused = fixture.await(BrainRunState.PAUSED).run!!
        val request = buildJsonObject { put("runId", paused.id); put("expectedRunRevision", paused.revision); put("requestId", brainId()) }
        fixture.call("resume", request); fixture.await(BrainRunState.PAUSED)
        fixture.call("resume", request)
        assertEquals(BrainRunState.PAUSED, fixture.await(BrainRunState.PAUSED).run!!.state)
        assertTrue(fixture.store.readRecord("local", fixture.scope.companionId).receipts.containsKey(request.getValue("requestId").jsonPrimitive.content))
    }

    @Test fun aMessageAnswersAnInputPauseEvenAfterReopening() = Fixture().use { fixture ->
        fixture.runtime.decide = { input -> if ("Clarified" in input.getValue("request").jsonPrimitive.content) null else """{"kind":"ask","question":"Which paragraph?"}""" }
        fixture.send(); assertEquals("Which paragraph?", fixture.await(BrainRunState.AWAITING_INPUT).waiting?.summary)
        fixture.call("close"); fixture.call("open")
        assertEquals(BrainRunState.AWAITING_INPUT, fixture.snapshot().run?.state)
        fixture.send(text = "  Clarified: the first one.  "); val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals("Clarified: the first one.", value.messages.last { it.author == BrainAuthor.USER }.text)
        assertEquals(1, value.attempts.size)
        assertTrue(fixture.workers().single().text.contains("User clarification: Clarified: the first one."))
    }
    @Test fun skippedStagesRecordTheirReasonAndTheRestStillRun() = Fixture(true).use { fixture ->
        val (first, second) = fixture.configuration.workflow!!.steps
        fixture.runtime.decide = { input ->
            val next = input.getValue("nextStepId")
            if ("skip-step" in input.getValue("allowed").toString() && next == JsonPrimitive(second.id)) """{"kind":"skip-step","stepId":"${second.id}","reason":"Nothing to edit."}""" else null
        }
        fixture.send(); val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(listOf(first.id), value.attempts.map { it.stepId })
        assertEquals(listOf(BrainSkip(second.id, "Nothing to edit.")), value.run!!.skipped)
        assertTrue(value.messages.any { it.author == BrainAuthor.SYSTEM && it.text == "Skipped Editor: Nothing to edit." })
    }
    @Test fun aBlockedStageReturnsToTheCoordinatorAndRunsAgainOnlyWhenAsked() = Fixture(true).use { fixture ->
        var blocked = false
        fixture.runtime.decide = { input ->
            if (!blocked && "step-result" in input.getValue("allowed").toString()) {
                blocked = true
                """{"kind":"step-result","attemptId":"${input.getValue("attempts").jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"blocked","explanation":"The feature-planner skill is missing."}"""
            } else null
        }
        fixture.send(); val waiting = fixture.await(BrainRunState.AWAITING_INPUT)
        assertEquals(listOf(BrainAttemptState.BLOCKED), waiting.attempts.map { it.state }); assertEquals(1, fixture.workers().size)
        assertEquals("The feature-planner skill is missing.", waiting.waiting?.summary)
        fixture.send(text = "I installed it; try again."); val value = fixture.await(BrainRunState.COMPLETED)
        val steps = fixture.configuration.workflow!!.steps.map { it.id }
        assertEquals(listOf(steps[0], steps[0], steps[1]), value.attempts.map { it.stepId })
    }
    @Test fun retryRunsTheLastAttemptAgainAfterReview() = Fixture().use { fixture ->
        var blocked = false
        fixture.runtime.decide = { input ->
            if (!blocked && "step-result" in input.getValue("allowed").toString()) {
                blocked = true
                """{"kind":"step-result","attemptId":"${input.getValue("attempts").jsonArray.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"blocked","explanation":"The provider timed out mid-answer."}"""
            } else null
        }
        fixture.send(); val waiting = fixture.await(BrainRunState.AWAITING_INPUT)
        fixture.resume(waiting.run!!, buildJsonObject { put("attemptId", waiting.attempts.single().id) }, method = "retry")
        val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(listOf(BrainAttemptState.BLOCKED, BrainAttemptState.COMPLETED), value.attempts.map { it.state })
        assertNotEquals(value.attempts[0].id, value.attempts[1].id)
        assertTrue(fixture.shown.single().lines.any { it.contains("runs again with the same input") })
        assertEquals(2, fixture.workers().size)
    }
    @Test fun resumingBeforeDispatchReplacesTheQueuedAttemptAndAdoptsTheTargetRevision() = Fixture().use { fixture ->
        fixture.providers.connected = false
        fixture.send(); val paused = fixture.await(BrainRunState.PAUSED)
        val queued = paused.attempts.single(); assertEquals(BrainAttemptState.QUEUED, queued.state)
        val changed = fixture.configuration.copy(specialists = fixture.configuration.specialists.map { it.copy(instructions = "Adopted instruction") })
        fixture.store.save("local", fixture.scope.companionId, 1, brainId(), changed)
        fixture.providers.connected = true
        fixture.resume(paused.run!!, buildJsonObject { put("targetRevision", 2) })
        val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(listOf(queued.id), value.attempts.map { it.id })
        assertEquals(2L, value.run!!.configurationRevision); assertEquals(2L, value.attempts.single().configurationRevision)
        assertEquals("Adopted instruction", value.attempts.single().role.instructions)
        assertTrue(fixture.workers().single().persona.instructions.contains("Adopted instruction"))
        assertTrue(fixture.shown.single().lines.none { it.contains("may have changed files") })
    }
    @Test fun aProviderRefusalExplainsItselfAndLeavesNothingUncertain() = Fixture().use { fixture ->
        fixture.runtime.failWorker = ChatFailure.UsageLimit(null)
        fixture.send(); val paused = fixture.await(BrainRunState.PAUSED)
        assertTrue(paused.notice!!.contains("usage limit"), paused.notice)
        assertEquals(BrainAttemptState.FAILED, paused.attempts.single().state)
        fixture.resume(paused.run!!)
        val value = fixture.await(BrainRunState.COMPLETED)
        assertEquals(listOf(BrainAttemptState.FAILED, BrainAttemptState.COMPLETED), value.attempts.map { it.state })
        assertTrue(fixture.shown.single().lines.none { it.contains("may have changed files") })
    }
    @Test fun theTeamConversationCarriesOverFromItsLegacyLocation() = Fixture(open = false).use { fixture ->
        val legacy = fixture.root.resolve("local/codex/companion-chats/${fixture.scope.companionId}.json")
        Files.createDirectories(legacy.parent)
        Files.writeString(legacy, """{"version":1,"threadId":"${brainId()}","messages":[{"id":"${brainId()}","role":"user","text":"Remember the lantern."},{"id":"${brainId()}","role":"assistant","text":"I will."}]}""")
        fixture.call("open")
        assertEquals(listOf("Remember the lantern.", "Previous conversation (runtime-default): I will."), fixture.snapshot().messages.map { it.text })
    }
    @Test fun anUnreadableTeamConversationNeverBlocksOpening() = Fixture(open = false).use { fixture ->
        val history = fixture.root.resolve("local/companion-chats/${fixture.scope.companionId}.json")
        Files.createDirectories(history.parent); Files.writeString(history, "broken")
        fixture.call("open")
        assertTrue(fixture.snapshot().messages.isEmpty()); assertEquals("broken", Files.readString(history))
    }
    @Test fun attachedInputsReachTalkRolesAsBoundedTextAndNeverAsPaths() = Fixture().use { fixture ->
        val folder = Files.createTempDirectory("brain-inputs")
        fun choose(name: String, bytes: ByteArray) = brainJson.decodeFromJsonElement<BrainArtifact>(fixture.run {
            chosen = folder.resolve(name).also { Files.write(it, bytes) }
            call("chooseInput", buildJsonObject { put("messageId", brainId()) })
        })
        val plan = choose("PLAN.MD", "# Plan\n".toByteArray() + "é".repeat(BrainLimits.INPUT).toByteArray())
        val data = choose("data.JSON", """{"ready":true}""".toByteArray())
        val image = choose("kite.png", byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0, 0, 0))
        assertEquals(listOf("text/markdown", "text/plain", BrainStore.BINARY), listOf(plan, data, image).map { it.mediaType })
        fixture.chosen = null
        assertEquals(JsonNull, fixture.call("chooseInput", buildJsonObject { put("messageId", brainId()) }))
        fixture.send(inputIds = listOf(plan.id, data.id, image.id)); fixture.await(BrainRunState.COMPLETED)
        for (turn in fixture.runtime.turns) assertFalse(turn.text.contains(fixture.root.toString()), "A tool-free role received a native path")
        val worker = fixture.workers().single().text
        assertTrue(worker.contains("truncated to its first") && worker.contains("[Truncated]"))
        assertTrue(worker.contains("""{"ready":true}"""))
        assertTrue(worker.contains("a binary file of 7 bytes"))
    }

    @Test fun openingLegacyAndBrainConcurrentlyLeavesOnlyOneOwner() {
        val root = Files.createTempDirectory("brain-ownership")
        val ownership = Any(); val entered = CountDownLatch(1); val finish = CountDownLatch(1)
        val dialogs = NativeDialogs(DialogPresenter { _, answer -> answer(0); {} })
        lateinit var brain: DesktopBrain
        val legacy = DesktopChat(root, listOf(Runtime()), Speech(), beforeOpen = {
            brain.invalidate(); entered.countDown(); check(finish.await(5, TimeUnit.SECONDS))
        }, ownershipGate = ownership)
        brain = DesktopBrain(root, BrainStore(root), listOf(Runtime()), ChatWork(WorkspaceStore(root, FolderPicker { _, _ -> null }), StartOfWork(dialogs), emptyList()),
            Providers(), dialogs, FilePicker { null }, Speech(), legacy::invalidate, ownershipGate = ownership)
        val old = ChatScope("local", brainId(), "Legacy", brainId())
        val next = ChatScope("local", brainId(), "Coordinator", brainId())
        val pool = Executors.newFixedThreadPool(2)
        try {
            val openingLegacy = pool.submit { runBlocking { legacy.open(old) } }
            check(entered.await(5, TimeUnit.SECONDS))
            val openingBrain = pool.submit { runBlocking { brain.call("open", brainWire(next).jsonObject) } }
            finish.countDown()
            openingLegacy.get(5, TimeUnit.SECONDS); openingBrain.get(5, TimeUnit.SECONDS)
            assertEquals(ChatState.ERROR, runBlocking { legacy.snapshot(old) }.state)
            runBlocking { brain.call("snapshot", brainWire(next).jsonObject) }
        } finally { finish.countDown(); pool.shutdownNow(); brain.close(); legacy.close() }
    }

    /** A page that predates Grok, which declares no runtimes, reads only the legacy pair; one that declares Grok reads it. */
    @Test fun aPageThatCannotDecodeARuntimeIsAskedToUpdateInsteadOfSentIt() = Fixture(transform = ::grok, open = false).use { fixture ->
        val key = buildJsonObject { put("profile", fixture.scope.profile); put("companionId", fixture.scope.companionId) }
        fun read(declared: JsonObject = buildJsonObject {}) = runBlocking { fixture.controller.call("read", JsonObject(key + declared)) }
        assertFailsWith<BridgeUpdateRequiredException> { read() }
        assertFailsWith<BridgeUpdateRequiredException> { read(declaring("codex", "claude-code", "future-runtime")) }
        assertIs<BrainReadResult.Found>(brainJson.decodeFromJsonElement(BrainReadResult.serializer(), read(declaring(*GROK_PAGE))))
        assertFailsWith<BridgeUpdateRequiredException> { fixture.call("open") }
        fixture.call("open", declaring(*GROK_PAGE))
        // A lease keeps the vocabulary it opened with.
        assertFailsWith<BridgeUpdateRequiredException> { fixture.call("open") }
        assertEquals(1L, fixture.snapshot().configurationRevision)
    }

    @Test fun aPageThatCannotReadTheBrainLeavesAnotherPagesLeaseOpen() = Fixture(transform = ::grok, open = false).use { fixture ->
        fixture.call("open", declaring(*GROK_PAGE))
        val older = ChatScope(fixture.scope.profile, fixture.scope.companionId, fixture.scope.name, brainId())
        assertFailsWith<BridgeUpdateRequiredException> { runBlocking { fixture.controller.call("open", brainWire(older).jsonObject) } }
        assertEquals(1L, fixture.snapshot().configurationRevision)
    }

    @Test fun anOpenLeaseIsAskedToUpdateOnceTheBrainNamesARuntimeItsPageCannotRead() = Fixture().use { fixture ->
        fixture.snapshot()
        fixture.store.save(fixture.scope.profile, fixture.scope.companionId, 1, brainId(), grok(fixture.configuration))
        assertFailsWith<BridgeUpdateRequiredException> { fixture.call("snapshot") }
        // Nor can it start work it could not then show.
        assertFailsWith<BridgeUpdateRequiredException> { fixture.send() }
        assertTrue(fixture.runtime.turns.isEmpty())
        assertNull(fixture.store.readRecord(fixture.scope.profile, fixture.scope.companionId).run)
    }

    private companion object {
        val GROK_PAGE = arrayOf("codex", "claude-code", "grok-build")
        fun declaring(vararg runtimes: String) = buildJsonObject { putJsonArray("supportedRuntimes") { runtimes.forEach { add(it) } } }
        /** Every specialist on Grok Build. */
        fun grok(configuration: BrainConfiguration) =
            configuration.copy(specialists = configuration.specialists.map { it.copy(binding = it.binding?.copy(runtimeId = RuntimeId.GROK_BUILD)) })
        fun delegate(configuration: BrainConfiguration) =
            """{"kind":"delegate","specialistId":"${configuration.specialists.first().id}","task":"Write a paragraph","inputIds":[]}"""
    }
}
