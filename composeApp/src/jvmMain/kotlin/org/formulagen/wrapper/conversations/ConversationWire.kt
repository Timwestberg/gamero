package org.formulagen.wrapper.conversations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.formulagen.wrapper.bridge.ActivityStep
import org.formulagen.wrapper.bridge.BrainAttemptOutcome
import org.formulagen.wrapper.bridge.BrainAttemptState
import org.formulagen.wrapper.bridge.BrainDocument
import org.formulagen.wrapper.bridge.BrainRoleSnapshot
import org.formulagen.wrapper.bridge.BrainRunState
import org.formulagen.wrapper.bridge.BrainSkip
import org.formulagen.wrapper.bridge.FolderRole
import org.formulagen.wrapper.bridge.ProviderModel
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.brainWire

/*
 * The payloads of event-contracts `hovel/companions` that the Mac sends or reads, one Kotlin type
 * per schema, named after it. They travel in the contract's exact form: [wire] leaves out an absent
 * optional field (a property with a default) and always sends a nullable one, and [fromWire] refuses
 * an unknown key. Only the routing, stream, approval and folder shapes are here; the page reads the
 * rest from the server directly.
 */

/** [value] as the contract's JSON object. */
internal fun <T> wire(serializer: KSerializer<T>, value: T): JsonObject = brainWire(serializer, value).jsonObject

/** [element] read as [serializer]'s contract shape; an unknown key fails. */
internal fun <T> fromWire(serializer: KSerializer<T>, element: JsonElement): T = brainJson.decodeFromJsonElement(serializer, element)

/** `artifactRef`: a text or file as every surface lists it; the whole text is a separate read. */
@Serializable
internal data class ArtifactRef(val id: String, val mediaType: String, val preview: String, val title: String)

/** `MESSAGE_AUTHORS`. */
@Serializable
internal enum class MessageAuthor {
    @SerialName("user") USER,
    @SerialName("coordinator") COORDINATOR,
    @SerialName("specialist") SPECIALIST,
    @SerialName("system") SYSTEM,
}

/** `MESSAGE_STATES`: a user message's lifecycle. */
@Serializable
internal enum class MessageState(val wireName: String) {
    @SerialName("queued") QUEUED("queued"),
    @SerialName("claimed") CLAIMED("claimed"),
    @SerialName("running") RUNNING("running"),
    @SerialName("answered") ANSWERED("answered"),
    @SerialName("stopped") STOPPED("stopped"),
    @SerialName("failed") FAILED("failed"),
    @SerialName("expired") EXPIRED("expired"),
    @SerialName("cancelled") CANCELLED("cancelled"),
    @SerialName("reconciliation-required") RECONCILIATION_REQUIRED("reconciliation-required");

    /** One of `MESSAGE_PROGRESS_STATES` that settles a message. */
    val settles: Boolean get() = this in setOf(ANSWERED, STOPPED, FAILED, RECONCILIATION_REQUIRED)
}

/** `COORDINATOR_NOTE_KINDS`: what the coordinator did with a thread reply. */
@Serializable
internal enum class NoteKind {
    @SerialName("forwarded") FORWARDED,
    @SerialName("answered") ANSWERED,
    @SerialName("started") STARTED,
}

/** `coordinatorNote`: in a thread, the thread it links: this one, or the one it [NoteKind.STARTED]. */
@Serializable
internal data class CoordinatorNote(val kind: NoteKind, val threadId: String)

/** `conversationMessage`: one message of the main conversation (`threadId` null) or a thread. */
@Serializable
internal data class ConversationMessage(
    val author: MessageAuthor,
    val blockedBy: String? = null,
    val conversationId: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val feedback: String? = null,
    val id: String,
    val inputs: List<ArtifactRef>,
    val note: CoordinatorNote? = null,
    val revision: Long,
    val state: MessageState? = null,
    val text: String,
    val threadId: String?,
)

/** `artifactInput`: a text a sent message attaches. A heard voice turn attaches none. */
@Serializable
internal data class ArtifactInput(val id: String, val mediaType: String, val text: String, val title: String)

/**
 * `messageSend`: the owner's message, which the Mac sends only for a heard voice turn, in the main
 * conversation. Its `id` is the client's; a retry with the same id must be deep-equal.
 */
@Serializable
internal data class MessageSend(val id: String, val inputs: List<ArtifactInput>, val text: String, val threadId: String?)

/** `messageAppend`: a reply or note the Mac appends; never a user message. */
@Serializable
internal data class MessageAppend(
    val author: MessageAuthor,
    val id: String,
    val note: CoordinatorNote? = null,
    val text: String,
    val threadId: String?,
) {
    init {
        require(author != MessageAuthor.USER) { "The Mac never writes the person's messages" }
        require(author != MessageAuthor.SPECIALIST || threadId != null) { "A specialist writes only in its thread" }
        require(note == null || (author == MessageAuthor.COORDINATOR && threadId != null)) { "Only the coordinator notes, in a thread" }
        require(note == null || (note.kind == NoteKind.STARTED) == (note.threadId != threadId)) { "A started thread is a new one; the others stay in this one" }
    }
}

/** `conversationThread`: one delegated task, with its retries and returns. Its activity lines are shareable text. */
@Serializable
internal data class ConversationThread(
    val activity: List<ActivityStep>,
    val conversationId: String,
    val id: String,
    val outcome: BrainAttemptOutcome? = null,
    val outputs: List<ArtifactRef>,
    val parentMessageId: String,
    val revision: Long,
    val role: BrainRoleSnapshot,
    val state: BrainAttemptState,
    val stepId: String?,
    val title: String,
)

/** `conversationRun`: a brain run whose current attempt is a thread. */
@Serializable
internal data class ConversationRun(
    val configurationRevision: Long,
    val currentThreadId: String?,
    val dispatchesUsed: Int,
    val id: String,
    val returnsUsed: Int,
    val revision: Long,
    val skipped: List<BrainSkip>,
    val state: BrainRunState,
    val workflowId: String?,
)

/** `conversation`. */
@Serializable
internal data class Conversation(
    val archived: Boolean,
    val id: String,
    val lastActivityAt: String,
    val projectId: String,
    val revision: Long,
    val run: ConversationRun?,
    val speak: Boolean,
    val title: String,
)

/** `projectFolder`: a folder's display data, never its path. */
@Serializable
internal data class WireProjectFolder(val id: String, val name: String, val readableBy: List<RuntimeId>, val role: FolderRole)

/** `companionProject`. */
@Serializable
internal data class CompanionProject(
    val archived: Boolean,
    val companionId: String,
    val computerId: String,
    val folders: List<WireProjectFolder>,
    val id: String,
    val name: String,
    val revision: Long,
)

/** `companion`. The Mac reads only its name; the look stays as sent. Named apart from Kotlin's `Companion` objects. */
@Serializable
internal data class AccountCompanion(val id: String, val look: JsonElement, val name: String, val revision: Long)

/** `claimRequest`: claims a conversation's next queued message with a `claimId` journaled first. */
@Serializable
internal data class ClaimRequest(val claimId: String, val conversationId: String)

/** `claimResult`: everything a claimed message needs, and the brain the run pins. */
@Serializable
internal data class ClaimResult(
    val brain: BrainDocument,
    val claimId: String,
    val companion: AccountCompanion,
    val conversation: Conversation,
    val history: List<ConversationMessage>,
    val message: ConversationMessage,
    val project: CompanionProject,
    val recalledMemory: String? = null,
    val thread: ConversationThread?,
)

/** `claimResponse`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
internal sealed interface ClaimResponse {
    @Serializable @SerialName("claimed")
    data class Claimed(val claim: ClaimResult) : ClaimResponse

    @Serializable @SerialName("empty")
    data object Empty : ClaimResponse

    /** The message needs a newer contract than this Mac sent; it stays queued until the Mac updates or it expires. */
    @Serializable @SerialName("mac-update-required")
    data class MacUpdateRequired(val minimumContract: Int) : ClaimResponse

    /**
     * The conversation's claimed or running user message, which the person asked to stop, now under this claim:
     * the replay of a claim that never learned its message, or a claim that took over one the Mac lost. The Mac
     * settles it `stopped` at its revision; nothing runs on.
     */
    @Serializable @SerialName("stop-requested")
    data class StopRequested(val message: ConversationMessage) : ClaimResponse
}

/** `APPROVAL_KINDS`. */
@Serializable
internal enum class ApprovalKind {
    @SerialName("permission") PERMISSION,
    @SerialName("start-of-work") START_OF_WORK,
    @SerialName("stage") STAGE,
    @SerialName("resume-review") RESUME_REVIEW,
}

/** `APPROVAL_OPTIONS`. */
@Serializable
internal enum class ApprovalOption {
    @SerialName("allow-once") ALLOW_ONCE,
    @SerialName("deny") DENY,
    @SerialName("continue") CONTINUE,
    @SerialName("request-changes") REQUEST_CHANGES,
    @SerialName("not-now") NOT_NOW,
    @SerialName("stop") STOP,
}

/** `APPROVAL_STATES`. */
@Serializable
internal enum class ApprovalState {
    @SerialName("open") OPEN,
    @SerialName("answered") ANSWERED,
    @SerialName("expired") EXPIRED,
    @SerialName("withdrawn") WITHDRAWN,
}

/** `APPROVAL_SURFACES`. */
@Serializable
internal enum class ApprovalSurface {
    @SerialName("mac") MAC,
    @SerialName("phone") PHONE,
}

/** `approvalPresentation`: folder-relative lines anyone may answer, or `mac-only`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("reach")
internal sealed interface ApprovalPresentation {
    @Serializable @SerialName("anywhere")
    data class Anywhere(val lines: List<String>) : ApprovalPresentation

    @Serializable @SerialName("mac-only")
    data class MacOnly(val summary: String) : ApprovalPresentation
}

/** `approvalAnswerRecord`. */
@Serializable
internal data class ApprovalAnswerRecord(val by: ApprovalSurface, val deviceId: String, val option: ApprovalOption)

/** `agentApproval`. */
@Serializable
internal data class AgentApproval(
    val answer: ApprovalAnswerRecord? = null,
    val computerId: String,
    val conversationId: String,
    val expiresAt: String,
    val id: String,
    val kind: ApprovalKind,
    val options: List<ApprovalOption>,
    val presentation: ApprovalPresentation,
    val revision: Long,
    val state: ApprovalState,
    val threadId: String?,
)

/** `approvalOutcome`: how an approval ended on the Mac. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface ApprovalOutcome {
    @Serializable @SerialName("answered")
    data class Answered(val option: ApprovalOption) : ApprovalOutcome

    @Serializable @SerialName("withdrawn")
    data object Withdrawn : ApprovalOutcome
}

/** `messageCheckpoint`: a durable, idempotent step of a running message. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("kind")
internal sealed interface MessageCheckpoint {
    @Serializable @SerialName("thread.upserted")
    data class ThreadUpserted(val thread: ConversationThread) : MessageCheckpoint

    @Serializable @SerialName("message.appended")
    data class MessageAppended(val message: MessageAppend) : MessageCheckpoint

    /** A text artifact carries its [text]; a binary one stays on the Mac. */
    @Serializable @SerialName("artifact.stored")
    data class ArtifactStored(val artifact: ArtifactRef, val text: String? = null) : MessageCheckpoint

    @Serializable @SerialName("run.changed")
    data class RunChanged(val run: ConversationRun) : MessageCheckpoint

    @Serializable @SerialName("approval.opened")
    data class ApprovalOpened(val approval: AgentApproval) : MessageCheckpoint

    @Serializable @SerialName("approval.closed")
    data class ApprovalClosed(val approvalId: String, val outcome: ApprovalOutcome) : MessageCheckpoint
}

/** `messageCheckpointRequest`. */
@Serializable
internal data class MessageCheckpointRequest(val checkpoint: MessageCheckpoint, val claimId: String, val claimedMessageId: String)

/** `replyDelta`: a streamed piece of reply [messageId], relayed and never stored. */
@Serializable
internal data class ReplyDelta(val messageId: String, val seq: Int, val text: String, val threadId: String?)

/** `replyDeltaRequest`. */
@Serializable
internal data class ReplyDeltaRequest(val claimId: String, val claimedMessageId: String, val delta: ReplyDelta)

/** `messageProgress`. */
@Serializable
internal data class MessageProgress(val claimId: String, val claimedMessageId: String, val expectedRevision: Long, val state: MessageState) {
    init { require(state == MessageState.RUNNING || state.settles) { "Progress moves a message to running or settles it" } }
}

/** `hostStreamEvent`: work for this computer, or a change it must act on. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
internal sealed interface HostStreamEvent {
    @Serializable @SerialName("work.available")
    data class WorkAvailable(val conversationId: String) : HostStreamEvent

    @Serializable @SerialName("message.cancelled")
    data class MessageCancelled(val messageId: String) : HostStreamEvent

    @Serializable @SerialName("stop.requested")
    data class StopRequested(val conversationId: String) : HostStreamEvent

    @Serializable @SerialName("approval.answered")
    data class ApprovalAnswered(val approvalId: String) : HostStreamEvent

    @Serializable @SerialName("project.changed")
    data class ProjectChanged(val projectId: String) : HostStreamEvent
}

/** `PROVIDER_READINESS`. */
@Serializable
internal enum class ProviderReadiness {
    @SerialName("ready") READY,
    @SerialName("signed-out") SIGNED_OUT,
    @SerialName("not-installed") NOT_INSTALLED,
    @SerialName("unavailable") UNAVAILABLE,
}

/** `providerRuntime`: one runtime on this computer. */
@Serializable
internal data class ProviderRuntime(val canWork: Boolean, val models: List<ProviderModel>, val readiness: ProviderReadiness, val runtimeId: RuntimeId)

/** `providerDirectory`: what this computer publishes about its runtimes. */
@Serializable
internal data class ProviderDirectory(val observedAt: String, val runtimes: List<ProviderRuntime>) {
    /**
     * The heartbeat's `providerState` for this directory: `ready` when a runtime is ready, else `auth-required` when one
     * is signed out, else `unavailable`.
     */
    fun providerState(): String = when {
        runtimes.any { it.readiness == ProviderReadiness.READY } -> "ready"
        runtimes.any { it.readiness == ProviderReadiness.SIGNED_OUT } -> "auth-required"
        else -> "unavailable"
    }
}

/** `runtimeAllowance`: a runtime's usage as an `allowanceSnapshot`, built by [allowanceSnapshot]. */
@Serializable
internal data class RuntimeAllowance(val runtimeId: RuntimeId, val snapshot: JsonObject)

/**
 * The heartbeat's body: hovel-service's heartbeat (`providerState` and the declared [features]) plus
 * `computerHeartbeat`'s provider directory and usage. The contract number travels in the request header.
 */
@Serializable
internal data class HostHeartbeat(
    val allowances: List<RuntimeAllowance>,
    val directory: ProviderDirectory,
    val features: List<String>,
    /** `ready`, `auth-required` or `unavailable`, from [directory] ([ProviderDirectory.providerState]). */
    val providerState: String,
)

/** `projectFoldersSave`: the folders this Mac records for a project. */
@Serializable
internal data class ProjectFoldersSave(val expectedRevision: Long, val folders: List<WireProjectFolder>)

/** `artifactTextPage`: one page of an artifact's text, from the requested cursor; [nextCursor] is null at the end. */
@Serializable
internal data class ArtifactTextPage(val nextCursor: Int?, val text: String)
