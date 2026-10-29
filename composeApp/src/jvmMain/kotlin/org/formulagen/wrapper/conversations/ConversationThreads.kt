package org.formulagen.wrapper.conversations

import org.formulagen.wrapper.bridge.ActivityStep
import org.formulagen.wrapper.bridge.BrainArtifact
import org.formulagen.wrapper.bridge.BrainAttempt
import org.formulagen.wrapper.bridge.BrainAuthor
import org.formulagen.wrapper.bridge.BrainLimits
import org.formulagen.wrapper.bridge.BrainRoleSnapshot
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.WorkflowStep
import org.formulagen.wrapper.bridge.trimEcma
import org.formulagen.wrapper.brain.RunRecord
import org.formulagen.wrapper.brain.withEntry
import org.formulagen.wrapper.util.shortened

/*
 * Threads as pure functions of a conversation's record. An attempt is a thread: a task's first attempt
 * opens one, announced in the conversation by a coordinator message, and its retries and workflow
 * returns stay in it, so its specialist keeps one provider conversation for the whole task.
 */

/** A thread's title: role and task on one line, as "Implementer · add sign-in". */
internal fun threadTitle(role: String, task: String): String {
    val oneLine = task.map { if (it.code < 0x20 || it.code == 0x7F) ' ' else it }.joinToString("").replace(Regex(" {2,}"), " ").trimEcma()
    return "$role · ${oneLine.ifEmpty { "task" }}".shortened(CompanionLimits.THREAD_TITLE).trimEcma()
}

/** The coordinator message a new thread hangs under. */
internal fun announcement(role: BrainRoleSnapshot, step: WorkflowStep?, task: String): String =
    (if (step != null) "Started ${step.name} with ${role.name}." else "Asked ${role.name}: $task").take(BrainLimits.OUTPUT)

/**
 * [after] with a thread for each new attempt of its run: [rerunOf]'s thread for the attempt a resume
 * reruns, the thread of an earlier attempt of the same workflow step (a return), or else a new
 * thread with the attempt's id, announced by a coordinator message made with [newId].
 */
internal fun ConversationRecord.withThreads(rerunOf: String?, newId: () -> String): ConversationRecord {
    val run = brain.run ?: return this
    var record = this
    for (attempt in run.attempts) {
        if (attempt.id in record.attemptThreads) continue
        val existing = when {
            rerunOf != null && attempt == run.attempts.last() -> record.attemptThreads[rerunOf]
            attempt.stepId != null -> run.attempts.firstOrNull { it.stepId == attempt.stepId && it.id in record.attemptThreads }
                ?.let { record.attemptThreads[it.id] }
            else -> null
        }
        record = if (existing != null) record.copy(attemptThreads = record.attemptThreads + (attempt.id to existing))
        else record.opened(attempt, run, newId())
    }
    return record
}

/** A new thread for [attempt], under a coordinator message [announcementId] that names it. */
private fun ConversationRecord.opened(attempt: BrainAttempt, run: RunRecord, announcementId: String): ConversationRecord {
    val step = run.configuration.workflow?.steps?.find { it.id == attempt.stepId }
    val title = threadTitle(attempt.role.name, step?.name ?: run.task)
    val thread = ThreadState(title, announcementId, 0, attempt.role, attempt.stepId, attempt.state, attempt.outcome)
    return copy(
        brain = brain.withEntry(announcementId, BrainAuthor.COORDINATOR, announcement(attempt.role, step, run.task)),
        attemptThreads = attemptThreads + (attempt.id to attempt.id),
        threads = threads + (attempt.id to thread),
    )
}

/**
 * Each thread of the run as its latest attempt leaves it: role, step, state and outcome. A thread
 * that changed moves to its next revision; others, and threads outside the run, stay as they are.
 */
internal fun ConversationRecord.withThreadStates(): ConversationRecord {
    val run = brain.run ?: return this
    val latest = run.attempts.groupBy { attemptThreads[it.id] }.mapValues { it.value.last() }
    val updated = threads.mapValues { (id, thread) ->
        val attempt = latest[id] ?: return@mapValues thread
        val next = thread.copy(role = attempt.role, stepId = attempt.stepId, state = attempt.state, outcome = attempt.outcome)
        if (next == thread) thread else next.copy(revision = thread.revision + 1)
    }
    return copy(threads = updated)
}

/** [thread] after its specialist produced [artifact], with its latest shareable [activity] lines. */
internal fun ThreadState.withOutput(artifact: BrainArtifact?, activity: List<ActivityStep>): ThreadState {
    val outputs = artifact?.let { (outputs + ArtifactRef(it.id, it.mediaType, it.preview, it.title)).takeLast(CompanionLimits.THREAD_OUTPUTS) } ?: outputs
    return copy(outputs = outputs, activity = activity.ifEmpty { this.activity })
}

/** The record's run as the conversation shows it: the run, with its current attempt named by its thread. */
internal fun ConversationRecord.conversationRun(): ConversationRun? = brain.run?.summary?.let { run ->
    ConversationRun(run.configurationRevision, run.currentAttemptId?.let(attemptThreads::get), run.dispatchesUsed, run.id, run.returnsUsed,
        run.revision, run.skipped, run.state, run.workflowId)
}
