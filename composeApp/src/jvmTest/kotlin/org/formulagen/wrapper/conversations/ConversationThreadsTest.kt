package org.formulagen.wrapper.conversations

import kotlin.test.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.brain.*
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.conversation.ActivityStep
import org.formulagen.wrapper.conversation.MemberMode

class ConversationThreadsTest {
    private val document = brain("Implementer" to MemberMode.WORK, "Reviewer" to MemberMode.TALK, workflow = true)
    private val configuration = document.configuration
    private val implementer = configuration.specialists[0]
    private val reviewer = configuration.specialists[1]

    private fun role(role: SpecialistRole) = BrainRoleSnapshot(role.id, role.name, checkNotNull(role.binding), role.instructions, role.mode)
    private fun attempt(role: SpecialistRole, stepId: String? = null, state: BrainAttemptState = BrainAttemptState.QUEUED) =
        BrainAttempt(id(), id(), stepId, role(role), 4, emptyList(), state = state)
    private fun record(vararg attempts: BrainAttempt, task: String = "add sign-in") = ConversationRecord(brain = BrainRecord(run = RunRecord(
        BrainRun(id(), 1, 4, null, BrainRunState.RUNNING, attempts.lastOrNull()?.id), configuration, null, "request", emptyList(),
        attempts = attempts.toList(), task = task)))

    @Test fun aTitleIsRoleAndTaskOnOneLine() {
        assertEquals("Implementer · add sign-in", threadTitle("Implementer", "add sign-in"))
        assertEquals("Implementer · add sign-in now", threadTitle("Implementer", "  add\nsign-in\t\tnow  "))
        assertEquals("Implementer · task", threadTitle("Implementer", "\n"))
        val long = threadTitle("Implementer", "x".repeat(500))
        assertTrue(long.length <= 120 && long.startsWith("Implementer · x"))
    }

    @Test fun aTitleKeepsJoinedEmojiAndIsAlwaysOneTheServerTakes() {
        val coder = "\ud83d\udc69\u200d\ud83d\udcbb"
        val fire = "\u2764\ufe0f\u200d\ud83d\udd25"
        assertEquals("Implementer · ship the $coder page $fire", threadTitle("Implementer", "ship the $coder page $fire"))
        // a cut at the limit never leaves half a character
        for (pad in 100..112) {
            val title = threadTitle("Implementer", "x".repeat(pad) + coder.repeat(5))
            assertTrue(title.length <= CompanionLimits.THREAD_TITLE, title)
            assertTrue(SharedRules.isDisplayText(title), title)
            assertTrue(title.indices.none { title[it].isHighSurrogate() && (it + 1 >= title.length || !title[it + 1].isLowSurrogate()) }, title)
        }
    }

    @Test fun aNewTaskOpensAThreadUnderAnAnnouncement() {
        val first = attempt(implementer)
        var ids = 0
        val next = record(first).withThreads(null) { "00000000-0000-4000-8000-00000000000${ids++}" }
        val thread = next.threads.getValue(first.id)
        assertEquals(first.id, next.attemptThreads[first.id], "an attempt is a thread")
        assertEquals("Implementer · add sign-in", thread.title)
        val announcement = next.brain.messages.single()
        assertEquals(announcement.id, thread.parentMessageId)
        assertEquals(BrainAuthor.COORDINATOR, announcement.author)
        assertEquals("Asked Implementer: add sign-in", announcement.text)
        assertEquals(next, next.withThreads(null) { error("no new thread") }, "an attempt keeps its thread")
    }

    @Test fun aRetryStaysInItsThread() {
        val first = attempt(implementer, state = BrainAttemptState.INTERRUPTED)
        val opened = record(first).withThreads(null, ::id)
        val retry = first.copy(id = id(), state = BrainAttemptState.QUEUED)
        val retried = opened.copy(brain = opened.brain.copy(run = opened.brain.run!!.copy(attempts = listOf(first, retry)))).withThreads(first.id, ::id)
        assertEquals(first.id, retried.attemptThreads[retry.id])
        assertEquals(1, retried.threads.size)
        assertEquals(opened.brain.messages, retried.brain.messages, "no second announcement")
    }

    @Test fun aWorkflowReturnStaysInTheStepsThreadAndAnotherTaskOpensAnother() {
        val step1 = configuration.workflow!!.steps[0]
        val step2 = configuration.workflow!!.steps[1]
        val a = attempt(implementer, step1.id, BrainAttemptState.COMPLETED)
        val b = attempt(reviewer, step2.id, BrainAttemptState.COMPLETED)
        val opened = record(a, b).withThreads(null, ::id)
        assertEquals(2, opened.threads.size)
        assertEquals("Reviewer · Step 2", opened.threads.getValue(b.id).title)
        assertEquals("Started Step 2 with Reviewer.", opened.brain.messages.last().text)
        val back = attempt(implementer, step1.id)
        val adHoc = attempt(implementer)
        val next = opened.copy(brain = opened.brain.copy(run = opened.brain.run!!.copy(attempts = listOf(a, b, back, adHoc)))).withThreads(null, ::id)
        assertEquals(a.id, next.attemptThreads[back.id], "a return reuses its step's thread")
        assertEquals(adHoc.id, next.attemptThreads[adHoc.id], "a separate task never shares context")
        assertEquals(3, next.threads.size)
    }

    @Test fun aThreadTakesItsLatestAttemptsStateAsItsNextRevision() {
        val first = attempt(implementer)
        val opened = record(first).withThreads(null, ::id).withThreadStates()
        assertEquals(0, opened.threads.getValue(first.id).revision)
        val running = opened.copy(brain = opened.brain.copy(run = opened.brain.run!!.withLast(first.copy(state = BrainAttemptState.RUNNING))))
            .withThreadStates()
        assertEquals(BrainAttemptState.RUNNING, running.threads.getValue(first.id).state)
        assertEquals(1, running.threads.getValue(first.id).revision)
        assertEquals(running, running.withThreadStates(), "unchanged threads keep their revision")
    }

    @Test fun theRunNamesItsCurrentAttemptByItsThread() {
        val first = attempt(implementer)
        val opened = record(first).withThreads(null, ::id)
        val run = opened.conversationRun()!!
        assertEquals(first.id, run.currentThreadId)
        assertEquals(BrainRunState.RUNNING, run.state)
        assertNull(ConversationRecord().conversationRun())
    }

    @Test fun anOutputJoinsTheThreadAndActivityReplacesItsLines() {
        val thread = ThreadState("T", id(), 0, role(implementer), null, BrainAttemptState.RUNNING)
        val artifact = BrainArtifact(id(), "Report", "a".repeat(64), BrainSource(BrainSourceKind.ATTEMPT, attemptId = id()), "text/markdown", "Preview")
        val step = ActivityStep(ActivityKind.EDIT, "src/app.ts", ActivityStatus.DONE)
        val next = thread.withOutput(artifact, listOf(step))
        assertEquals(listOf(ArtifactRef(artifact.id, "text/markdown", "Preview", "Report")), next.outputs)
        assertEquals(listOf(step), next.activity)
        assertEquals(listOf(step), next.withOutput(null, emptyList()).activity, "no new lines keeps the last ones")
    }
}
