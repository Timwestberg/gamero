package org.formulagen.wrapper.brain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.runtimes.NativeConversation

/**
 * What the controller does next with a run: [PLAN] asks the coordinator what to do with the request, [EXECUTE] runs the
 * queued attempt, [EVALUATE] has the coordinator grade its result, [ADVANCE] start or skip the next workflow step, and
 * [FINISH] answer once every step is done. [THREAD_REPLY] is a conversation's reply in a thread, which the coordinator
 * forwards, answers or turns into a new task ([THREAD_REPLY_DECISIONS]); it never belongs to a run.
 */
@Serializable
internal enum class BrainPhase {
    @SerialName("plan") PLAN,
    @SerialName("execute") EXECUTE,
    @SerialName("evaluate") EVALUATE,
    @SerialName("advance") ADVANCE,
    @SerialName("finish") FINISH,
    @SerialName("thread-reply") THREAD_REPLY,
}

/**
 * One request's run with the configuration pinned when it began. [nextStep] is the next workflow step; [invalidated]
 * attempts belong to a pass a return made stale. [contexts] are the specialists' native conversations in this run.
 */
@Serializable
internal data class RunRecord(
    val summary: BrainRun,
    val configuration: BrainConfiguration,
    val workspace: String?,
    val request: String,
    val inputs: List<String>,
    val attempts: List<BrainAttempt> = emptyList(),
    val artifacts: List<BrainArtifact> = emptyList(),
    val nextStep: Int = 0,
    val phase: BrainPhase = BrainPhase.PLAN,
    val task: String = "",
    val returnsAllowance: Int = BrainLimits.RETURNS,
    val dispatchesAllowance: Int = BrainLimits.DISPATCHES,
    val contexts: Map<String, NativeConversation> = emptyMap(),
    val invalidated: List<String> = emptyList(),
    val stageConsents: Map<String, StageConsentRecord> = emptyMap(),
)

/** A companion's coordinator conversation, its current run and the request receipts that keep retries single. */
@Serializable
internal data class BrainRecord(
    val version: Int = 1,
    val messages: List<BrainEntry> = emptyList(),
    val run: RunRecord? = null,
    val waiting: BrainWaiting? = null,
    val receipts: Map<String, String> = emptyMap(),
    val staged: List<BrainArtifact> = emptyList(),
    val coordinatorContexts: Map<String, NativeConversation> = emptyMap(),
    val notice: String? = null,
)
