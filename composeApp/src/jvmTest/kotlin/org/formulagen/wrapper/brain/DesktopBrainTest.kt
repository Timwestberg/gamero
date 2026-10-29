package org.formulagen.wrapper.brain

import java.nio.file.Files
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
            val response = if (turn.persona.instructions.contains("Decision shapes:")) {
                if (malformed) "not JSON" else {
                    val input = brainJson.parseToJsonElement(turn.text).jsonObject
                    val allowed = input.getValue("allowed").jsonArray.map { it.jsonPrimitive.content }
                    val configuration = brainJson.decodeFromJsonElement<BrainConfiguration>(input.getValue("configuration"))
                    val attempts = input.getValue("attempts").jsonArray
                    when {
                        "step-result" in allowed -> """{"kind":"step-result","attemptId":"${attempts.last().jsonObject.getValue("id").jsonPrimitive.content}","outcome":"${if (returnForChanges && attempts.last().jsonObject["stepId"]?.jsonPrimitive?.content == configuration.workflow?.steps?.last()?.id) "changes-needed" else "ready"}","explanation":"Output is ready."}"""
                        "start-step" in allowed -> """{"kind":"start-step","stepId":${input.getValue("nextStepId")},"inputIds":[]}"""
                        "enter-workflow" in allowed -> """{"kind":"enter-workflow","entryStepId":"${configuration.workflow!!.steps[if (enterAtSecond) 1 else 0].id}","inputIds":[],"skipped":${if (enterAtSecond) """[{"stepId":"${configuration.workflow.steps.first().id}","reason":"The supplied plan is ready."}]""" else "[]"},"reason":"Start writing."}"""
                        "delegate" in allowed && attempts.isEmpty() -> """{"kind":"delegate","specialistId":"${configuration.specialists.first().id}","task":"Write a paragraph","inputIds":[]}"""
                        else -> """{"kind":"answer","text":"Finished the requested work."}"""
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
        override suspend fun perform(action: ProviderAction, scope: ProviderScope) = ProviderStatus(scope.runtimeId, ProviderState.CONNECTED)
        override suspend fun list(profile: String) = ProviderList(listOf(RuntimeId.CODEX), emptyList())
        override suspend fun models(scope: ProviderScope) = ProviderModels(listOf(ProviderModel("Test model", true, "test-model", listOf(ModelEffort.HIGH))), scope.runtimeId)
        override suspend fun usage(scope: ProviderScope) = ProviderUsage(scope.runtimeId, 0)
    }
    private class Fixture(workflow: Boolean = false, transform: (BrainConfiguration) -> BrainConfiguration = { it }, answer: Int = 0) : AutoCloseable {
        val root = Files.createTempDirectory("brain-controller")
        val store = BrainStore(root)
        val configuration = transform(testConfiguration(workflow))
        val runtime = Runtime()
        val speech = Speech()
        val scope = ChatScope("local", brainId(), "Ember", brainId())
        val dialogs = NativeDialogs(DialogPresenter { _, reply -> reply(answer); {} })
        val controller = DesktopBrain(root, store, listOf(runtime), ChatWork(WorkspaceStore(root, FolderPicker { _, _ -> null }), StartOfWork(dialogs), emptyList()), Providers(), dialogs, speech, {})
        init { store.save(scope.profile, scope.companionId, 0, brainId(), configuration); call("open") }
        fun call(method: String, more: JsonObject = buildJsonObject {}) = runBlocking { controller.call(method, JsonObject(brainWire(scope).jsonObject + more)) }
        fun send(requestId: String = brainId()) = call("send", buildJsonObject { put("requestId", requestId); put("expectedRevision", 1); put("text", "Please write this"); put("inputIds", JsonArray(emptyList())); put("speak", true) })
        fun await(state: String): BrainSnapshot {
            repeat(500) { val value = brainJson.decodeFromJsonElement<BrainSnapshot>(call("snapshot")); if (value.run?.state == state) return value; Thread.sleep(10) }
            error("State not reached: $state, ${call("snapshot")}")
        }
        override fun close() { controller.close() }
    }
    @Test fun workflowKeepsRolesSeparateAndOnlySpeaksValidatedCoordinatorText() = Fixture(true).use { fixture ->
        fixture.send(); val value = fixture.await("completed")
        assertEquals(2, value.attempts.size); assertEquals(2, value.artifacts.size)
        assertEquals(fixture.configuration.specialists.map { it.id }, value.attempts.map { it.role.roleId })
        val workers = fixture.runtime.turns.filterNot { it.persona.instructions.contains("Decision shapes:") }
        assertEquals(2, workers.size); assertTrue(workers.all { it.conversation == null })
        assertEquals(listOf("Finished the requested work."), fixture.speech.spoken)
        assertTrue(value.messages.none { it.text.contains("\"kind\"") })
        val saved = fixture.store.readRecord("local", fixture.scope.companionId)
        assertEquals(2, saved.run?.contexts?.size)
    }
    @Test fun repeatingSendDoesNotLaunchAgainAndMalformedControlCannotDispatch() = Fixture().use { fixture ->
        val requestId = brainId(); fixture.send(requestId); fixture.await("completed")
        val count = fixture.runtime.turns.size; fixture.send(requestId); assertEquals(count, fixture.runtime.turns.size)
    }
    @Test fun malformedDecisionPausesWithoutAnySpecialist() = Fixture().use { fixture ->
        fixture.runtime.malformed = true; fixture.send(); val value = fixture.await("paused")
        assertEquals(2, fixture.runtime.turns.size); assertTrue(value.attempts.isEmpty())
    }
    @Test fun closeStopsTheLeaseAndLateCallbacksCannotChangeHistory() = Fixture().use { fixture ->
        fixture.runtime.hold = true; fixture.send()
        repeat(100) { if (fixture.runtime.held == null) Thread.sleep(10) }
        fixture.call("close")
        val before = fixture.store.readRecord("local", fixture.scope.companionId)
        fixture.runtime.held?.completed("Forged late result", brainId())
        assertEquals(before, fixture.store.readRecord("local", fixture.scope.companionId))
        assertEquals("paused", before.run?.summary?.state)
    }
    @Test fun stopDuringReadinessCannotDispatchIntoTheNextRun() = Fixture().use { fixture ->
        val checking = CountDownLatch(1); val release = CountDownLatch(1)
        fixture.runtime.checking = checking; fixture.runtime.releaseCheck = release
        fixture.send(); assertTrue(checking.await(5, TimeUnit.SECONDS))
        fixture.call("stop")
        fixture.runtime.checking = null; fixture.runtime.releaseCheck = null
        fixture.send()
        release.countDown()
        fixture.await("completed")
        assertEquals(4, fixture.runtime.turns.size)
        assertEquals(1, fixture.store.readRecord("local", fixture.scope.companionId).run!!.attempts.size)
    }
    @Test fun midwayEntryRequiresReasonsAndRunsOnlyTheRemainingStages() = Fixture(true).use { fixture ->
        fixture.runtime.enterAtSecond = true; fixture.send(); val value = fixture.await("completed")
        assertEquals(1, value.attempts.size)
        assertEquals(fixture.configuration.workflow!!.steps.first().id, value.run!!.skipped.single().stepId)
        assertEquals(fixture.configuration.workflow.steps.last().id, value.attempts.single().stepId)
    }
    @Test fun nativeStageStopCannotDispatchAWorker() = Fixture(true, { config -> config.copy(workflow = config.workflow!!.let { it.copy(steps = it.steps.map { step -> step.copy(askBeforeStarting = true) }) }) }, answer = 2).use { fixture ->
        fixture.send(); val value = fixture.await("cancelled")
        assertEquals(1, fixture.runtime.turns.size)
        assertEquals(0, value.run!!.dispatchesUsed)
    }
    @Test fun revisionLoopStopsBeforeTheFourthAutomaticReturn() = Fixture(true, { config ->
        config.copy(workflow = config.workflow!!.let { it.copy(steps = listOf(it.steps[0], it.steps[1].copy(returnTo = BrainReturn(it.steps[0].id, "Changes needed")))) })
    }).use { fixture ->
        fixture.runtime.returnForChanges = true; fixture.send(); val value = fixture.await("paused")
        assertEquals("limit", value.waiting?.kind)
        assertEquals(8, value.run!!.dispatchesUsed)
        assertEquals(8, value.attempts.count { it.state == "completed" })
        assertTrue(fixture.store.readRecord("local", fixture.scope.companionId).run!!.invalidated.isNotEmpty())
    }
    @Test fun projectRolesNeverSilentlyFallBackToTalk() = Fixture(false, { config -> config.copy(specialists = config.specialists.map { it.copy(mode = MemberMode.WORK) }) }).use { fixture ->
        fixture.send(); val value = fixture.await("paused")
        assertEquals(1, fixture.runtime.turns.size); assertEquals(0, value.run!!.dispatchesUsed)
        assertTrue(value.waiting!!.summary.contains("not ready"))
    }
    @Test fun artifactsRetainFullOutputBeyondTranscriptPreviewLimit() = Fixture().use { fixture ->
        fixture.runtime.longOutput = true; fixture.send(); val value = fixture.await("completed")
        val artifact = value.artifacts.single()
        assertEquals(28000, fixture.store.bytes("local", fixture.scope.companionId, artifact).size)
        assertEquals(BrainLimits.OUTPUT, value.messages.first { it.author == "specialist" }.text.length)
    }
    @Test fun interruptedRunsReopenPausedAndRejectReusedRequestContent() = Fixture().use { fixture ->
        fixture.runtime.hold = true; val id = brainId(); fixture.send(id)
        fixture.call("close"); fixture.call("open")
        assertEquals("paused", fixture.await("paused").run?.state)
        assertFails { fixture.call("send", buildJsonObject { put("requestId", id); put("expectedRevision", 1); put("text", "Different request"); put("inputIds", JsonArray(emptyList())); put("speak", true) }) }; Unit
    }

    @Test fun savingDuringATurnPinsSpecialistsButChangesTheNextCoordinatorInvocation() = Fixture().use { fixture ->
        fixture.runtime.hold = true; fixture.send()
        repeat(100) { if (fixture.runtime.held == null) Thread.sleep(10) }
        val changed = fixture.configuration.copy(coordinator = fixture.configuration.coordinator.copy(instructions = "New coordinator instruction"), specialists = fixture.configuration.specialists.map { it.copy(instructions = "New specialist instruction") })
        fixture.store.save("local", fixture.scope.companionId, 1, brainId(), changed)
        fixture.runtime.hold = false
        fixture.runtime.held!!.completed("""{"kind":"delegate","specialistId":"${fixture.configuration.specialists.first().id}","task":"Write a paragraph","inputIds":[]}""", brainId())
        val result = fixture.await("completed")
        assertEquals(1L, result.run!!.configurationRevision)
        assertEquals(2L, result.configurationRevision)
        assertEquals(fixture.configuration.specialists.first().instructions, result.attempts.single().role.instructions)
        assertTrue(fixture.runtime.turns.last().persona.instructions.contains("New coordinator instruction"))
    }
    @Test fun recoveryDenialPreservesTheRunAndIdempotentResumeCannotPromptTwice() = Fixture(answer = 1).use { fixture ->
        fixture.runtime.hold = true; fixture.send(); fixture.call("close"); fixture.call("open")
        val paused = fixture.await("paused").run!!
        val request = buildJsonObject { put("runId", paused.id); put("expectedRunRevision", paused.revision); put("requestId", brainId()) }
        fixture.call("resume", request); fixture.await("paused")
        fixture.call("resume", request)
        assertEquals("paused", fixture.await("paused").run!!.state)
        assertTrue(fixture.store.readRecord("local", fixture.scope.companionId).receipts.containsKey(request.getValue("requestId").jsonPrimitive.content))
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
            Providers(), dialogs, Speech(), legacy::invalidate, ownershipGate = ownership)
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

}
