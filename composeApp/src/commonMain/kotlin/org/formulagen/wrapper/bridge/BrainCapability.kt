package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.encoding.CompositeDecoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonClassDiscriminator
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import kotlin.time.Duration.Companion.minutes

/**
 * Native companion coordination, one method per `brain.*` call of event-contracts `brain.ts`. No method answers a
 * native approval. Results name only runtimes the page declared on [read] or [open]; a result that would name another
 * throws [BridgeUpdateRequiredException] instead.
 */
interface BrainCapability {
    /** The saved brain and its current readiness, or absent. */
    suspend fun read(args: BrainRead): BrainReadResult
    /** Compare and set the configuration at [BrainSave.expectedRevision]. It never starts work. */
    suspend fun save(args: BrainSave): BrainSaveResult
    /** Acquire the coordinator conversation's lease, ending any other lease on it. */
    suspend fun open(args: BrainOpen): BrainSnapshot
    suspend fun close(lease: BrainLease)
    suspend fun send(args: BrainSend): BrainSnapshot
    suspend fun snapshot(args: BrainSnapshotRequest): BrainSnapshot
    suspend fun stop(lease: BrainLease): BrainSnapshot
    suspend fun stopSpeaking(lease: BrainLease): BrainSnapshot
    /** Run the run's last attempt again after a native review. */
    suspend fun retry(args: BrainRetry): BrainSnapshot
    /** Continue a paused run after a native review, optionally adopting [BrainResume.targetRevision]. */
    suspend fun resume(args: BrainResume): BrainSnapshot
    /** Reveal the host's pending dialog. The page cannot answer it. */
    suspend fun showApproval(args: BrainShowApproval)
    suspend fun addInput(args: BrainAddInput): BrainArtifact
    /** The native file chooser's file as a staged input; null when the person cancels. */
    suspend fun chooseInput(args: BrainChooseInput): BrainArtifact?
    suspend fun readOutput(args: BrainReadOutput): BrainOutputPage
}

/** Mirrors BRAIN_LIMITS in event-contracts, checked against `fixtures/limits.json`. [INPUTS] bounds the inputs one message attaches. */
object BrainLimits {
    const val CONDITION = 240
    const val CONFIGURATION = 48 * 1024
    const val CURSOR = 80
    const val DESCRIPTION = 512
    const val DISPATCHES = 60
    const val HISTORY = 40
    const val INPUT = 32000
    const val INPUTS = 30
    const val LABEL = 80
    const val OUTPUT = 16000
    const val PREVIEW = 512
    const val RETURNS = 3
    const val ROLE_INSTRUCTIONS = 4096
    const val SPECIALISTS = 20
    const val STEP_INSTRUCTIONS = 2048
    const val STEPS = 30
    /** `brainRevision`'s bound, `Number.MAX_SAFE_INTEGER`. */
    const val REVISION = 9_007_199_254_740_991L
}

/** Records keep every null; [brainWire] decides which nulls the contract sends. */
val brainJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }

@Serializable
data class BrainBinding(val runtimeId: RuntimeId, val modelId: String, val effort: ModelEffort? = null) {
    fun member() = TeamMember(runtimeId, modelId)
}

@Serializable
data class CoordinatorRole(val id: String, val binding: BrainBinding?, val instructions: String) {
    /** How the conversation shows this coordinator, or null while it has no binding. */
    fun snapshot() = binding?.let { BrainRoleSnapshot(id, COORDINATOR_NAME, it, instructions, MemberMode.TALK) }

    companion object {
        const val COORDINATOR_NAME = "Coordinator"
    }
}

@Serializable
data class SpecialistRole(
    val id: String,
    val name: String,
    val instructions: String,
    val mode: MemberMode,
    val binding: BrainBinding?,
    val slot: Int,
)

@Serializable
data class BrainReturn(val stepId: String, val condition: String)

@Serializable
data class WorkflowStep(
    val id: String,
    val name: String,
    val specialistId: String?,
    val instructions: String,
    val inputDescription: String,
    val outputDescription: String,
    val askBeforeStarting: Boolean,
    val sectionId: String?,
    val returnTo: BrainReturn?,
)

@Serializable
data class BrainSection(val id: String, val name: String)

@Serializable
data class BrainWorkflow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val steps: List<WorkflowStep>,
    val sections: List<BrainSection>,
)

@Serializable
data class BrainConfiguration(
    val version: Int,
    val coordinator: CoordinatorRole,
    val specialists: List<SpecialistRole>,
    val workflow: BrainWorkflow?,
) {
    /** Every runtime a role of this brain is bound to. */
    fun runtimeIds(): Set<RuntimeId> = (listOf(coordinator.binding) + specialists.map { it.binding }).mapNotNull { it?.runtimeId }.toSet()
}

@Serializable
data class BrainDocument(val revision: Long, val configuration: BrainConfiguration)

/** `brainReadinessIssue.reason`. */
@Serializable
enum class BrainIssueReason {
    @SerialName("missing-binding") MISSING_BINDING,
    @SerialName("missing-role") MISSING_ROLE,
    @SerialName("unavailable-provider") UNAVAILABLE_PROVIDER,
    @SerialName("unavailable-model") UNAVAILABLE_MODEL,
    @SerialName("unsupported-effort") UNSUPPORTED_EFFORT,
    @SerialName("missing-project") MISSING_PROJECT,
    @SerialName("unsupported-project-work") UNSUPPORTED_PROJECT_WORK,
    @SerialName("empty-workflow") EMPTY_WORKFLOW,
}

@Serializable
data class BrainIssue(val reason: BrainIssueReason, val roleId: String? = null, val stepId: String? = null)

@Serializable
data class BrainRoleSnapshot(
    val roleId: String,
    val name: String,
    val binding: BrainBinding,
    val instructions: String,
    val mode: MemberMode,
)

@Serializable
enum class BrainSourceKind {
    @SerialName("user") USER,
    @SerialName("attempt") ATTEMPT,
}

/** Who made an artifact: the person, in [messageId], or a specialist's [attemptId]. */
@Serializable
data class BrainSource(val kind: BrainSourceKind, val messageId: String? = null, val attemptId: String? = null)

@Serializable
data class BrainArtifact(
    val id: String,
    val title: String,
    val digest: String,
    val source: BrainSource,
    val mediaType: String,
    val preview: String,
)

/**
 * `queued` and `awaiting-approval` were never dispatched; `running` was. `blocked` will not go on as it is,
 * `failed` could not have changed anything, and `interrupted` stopped mid-turn with uncertain effects.
 */
@Serializable
enum class BrainAttemptState {
    @SerialName("queued") QUEUED,
    @SerialName("awaiting-approval") AWAITING_APPROVAL,
    @SerialName("running") RUNNING,
    @SerialName("completed") COMPLETED,
    @SerialName("blocked") BLOCKED,
    @SerialName("failed") FAILED,
    @SerialName("interrupted") INTERRUPTED;

    /** Never reached a provider, so resuming replaces it in place. */
    val notStarted get() = this == QUEUED || this == AWAITING_APPROVAL
    /** Reached a provider without a result, so resuming runs it again. */
    val dispatchedWithoutResult get() = this == RUNNING || this == INTERRUPTED || this == FAILED
}

/** How the coordinator graded a completed attempt. */
@Serializable
enum class BrainAttemptOutcome {
    @SerialName("ready") READY,
    @SerialName("changes-needed") CHANGES_NEEDED,
}

@Serializable
data class BrainAttempt(
    val id: String,
    val runId: String,
    val stepId: String?,
    val role: BrainRoleSnapshot,
    val configurationRevision: Long,
    val inputIds: List<String>,
    val outputIds: List<String> = emptyList(),
    val state: BrainAttemptState = BrainAttemptState.QUEUED,
    val outcome: BrainAttemptOutcome? = null,
)

@Serializable
data class BrainSkip(val stepId: String, val reason: String)

/**
 * `planning` and `running` work; `awaiting-approval` waits on a native stage dialog and `awaiting-input` on the
 * person's next message. `paused` waits for an explicit resume. The rest have ended.
 */
@Serializable
enum class BrainRunState {
    @SerialName("planning") PLANNING,
    @SerialName("running") RUNNING,
    @SerialName("awaiting-input") AWAITING_INPUT,
    @SerialName("awaiting-approval") AWAITING_APPROVAL,
    @SerialName("paused") PAUSED,
    @SerialName("completed") COMPLETED,
    @SerialName("cancelled") CANCELLED,
    @SerialName("failed") FAILED;

    /** The controller works on it. */
    val working get() = this == PLANNING || this == RUNNING
    /** Something is in flight: work, or a native stage dialog. */
    val active get() = working || this == AWAITING_APPROVAL
    /** Ended; a new request starts the next run. */
    val terminal get() = this == COMPLETED || this == CANCELLED || this == FAILED
    /** Waiting for the person, so `brain.resume` or `brain.retry` may continue it. */
    val resumable get() = this == PAUSED || this == AWAITING_INPUT || this == AWAITING_APPROVAL || this == FAILED
}

@Serializable
data class BrainRun(
    val id: String,
    val revision: Long,
    val configurationRevision: Long,
    val workflowId: String?,
    val state: BrainRunState,
    val currentAttemptId: String?,
    val skipped: List<BrainSkip> = emptyList(),
    val returnsUsed: Int = 0,
    val dispatchesUsed: Int = 0,
)

/**
 * Why a run waits: `input` for the person's next message; `stage`, `start-of-work` or `provider` for a native dialog;
 * `recovery` or `limit` for an explicit resume.
 */
@Serializable
enum class BrainWaitingKind {
    @SerialName("stage") STAGE,
    @SerialName("provider") PROVIDER,
    @SerialName("start-of-work") START_OF_WORK,
    @SerialName("input") INPUT,
    @SerialName("limit") LIMIT,
    @SerialName("recovery") RECOVERY,
}

@Serializable
data class BrainWaiting(val kind: BrainWaitingKind, val summary: String, val approvalId: String? = null, val attemptId: String? = null)

@Serializable
enum class BrainAuthor {
    @SerialName("user") USER,
    @SerialName("coordinator") COORDINATOR,
    @SerialName("specialist") SPECIALIST,
    @SerialName("system") SYSTEM,
}

/** One conversation entry. [role] is the role as it was when it wrote the entry, so renaming it later rewrites nothing. */
@Serializable
data class BrainEntry(
    val id: String,
    val author: BrainAuthor,
    val text: String,
    val role: BrainRoleSnapshot? = null,
    val activity: Activity? = null,
)

@Serializable
data class BrainSnapshot(
    val configurationRevision: Long,
    val coordinator: BrainRoleSnapshot?,
    val messages: List<BrainEntry>,
    val run: BrainRun?,
    val attempts: List<BrainAttempt>,
    val artifacts: List<BrainArtifact>,
    val waiting: BrainWaiting?,
    val voice: ChatVoice,
    val cursor: String,
    val notice: String? = null,
) {
    /** Every runtime this snapshot names. */
    fun runtimeIds(): Set<RuntimeId> =
        (listOfNotNull(coordinator) + messages.mapNotNull { it.role } + attempts.map { it.role }).map { it.binding.runtimeId }.toSet()
}

/** The four IDs every lease call carries. Mirrors `brainLease`, which is `chatScope` without `hearing`. */
sealed interface BrainLeased {
    val profile: String
    val companionId: String
    val name: String
    val sessionId: String

    fun lease() = BrainLease(profile, companionId, name, sessionId)
}

@Serializable
data class BrainLease(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
) : BrainLeased

/**
 * [supportedRuntimes] names the runtimes the page decodes, known or not. A page that predates it omits it, and the
 * host then assumes [SharedRules.LEGACY_RUNTIMES].
 */
@Serializable
data class BrainRead(val profile: String, val companionId: String, val supportedRuntimes: List<String>? = null)

@Serializable
data class BrainSave(
    val profile: String,
    val companionId: String,
    val expectedRevision: Long,
    val mutationId: String,
    val configuration: BrainConfiguration,
)

/** As [BrainRead.supportedRuntimes]; the lease keeps that vocabulary until it closes. */
@Serializable
data class BrainOpen(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val supportedRuntimes: List<String>? = null,
) : BrainLeased

/** [text] is trimmed before its bound applies, as for `chat.send`. */
@Serializable
data class BrainSend(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val requestId: String,
    val expectedRevision: Long,
    val text: String,
    val inputIds: List<String>,
    val speak: Boolean,
) : BrainLeased

@Serializable
data class BrainSnapshotRequest(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val cursor: String? = null,
) : BrainLeased

@Serializable
data class BrainRetry(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val runId: String,
    val expectedRunRevision: Long,
    val requestId: String,
    val attemptId: String,
) : BrainLeased

@Serializable
data class BrainResume(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val runId: String,
    val expectedRunRevision: Long,
    val requestId: String,
    val targetRevision: Long? = null,
) : BrainLeased

@Serializable
data class BrainShowApproval(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val runId: String,
    val approvalId: String,
) : BrainLeased

/** [title] may arrive padded; it is trimmed before its bound applies. [text] is kept as given. */
@Serializable
data class BrainAddInput(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val messageId: String,
    val title: String,
    val text: String,
) : BrainLeased

@Serializable
data class BrainChooseInput(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val messageId: String,
) : BrainLeased

/** [cursor] is a character offset into the artifact's text. */
@Serializable
data class BrainReadOutput(
    override val profile: String,
    override val companionId: String,
    override val name: String,
    override val sessionId: String,
    val runId: String,
    val artifactId: String,
    val cursor: Int,
) : BrainLeased

@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface BrainReadResult {
    @Serializable @SerialName("absent")
    data object Absent : BrainReadResult

    @Serializable @SerialName("found")
    data class Found(val document: BrainDocument, val issues: List<BrainIssue>) : BrainReadResult
}

/** A configuration that fails [BrainRules.configuration] never reaches the store: the bridge answers `invalid`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("status")
sealed interface BrainSaveResult {
    @Serializable @SerialName("saved")
    data class Saved(val document: BrainDocument, val issues: List<BrainIssue>) : BrainSaveResult

    @Serializable @SerialName("conflict")
    data class Conflict(val currentRevision: Long) : BrainSaveResult
}

/** One page of an output's text; [nextCursor] is null on the last page. */
@Serializable
data class BrainOutputPage(val text: String, val nextCursor: Int?)

/**
 * [value] as the contract's JSON. The Kotlin declaration says which nulls travel: a nullable
 * property with a default is `.optional()` in event-contracts and is left out when null; one
 * without a default is `.nullable()` and is always sent, as `null` when empty.
 */
fun <T> brainWire(serializer: SerializationStrategy<T>, value: T): JsonElement =
    withoutOptionalNulls(brainJson.encodeToJsonElement(serializer, value), serializer.descriptor)
inline fun <reified T> brainWire(value: T): JsonElement = brainWire(serializer<T>(), value)

@OptIn(ExperimentalSerializationApi::class)
private fun withoutOptionalNulls(element: JsonElement, descriptor: SerialDescriptor): JsonElement = when {
    element is JsonObject && descriptor.kind == StructureKind.CLASS -> JsonObject(buildMap {
        for ((key, value) in element) {
            val index = descriptor.getElementIndex(key)
            if (index == CompositeDecoder.UNKNOWN_NAME) put(key, value)
            else if (value != JsonNull || !descriptor.isElementOptional(index)) {
                put(key, withoutOptionalNulls(value, descriptor.getElementDescriptor(index)))
            }
        }
    })
    // A sealed result is its discriminator plus the fields of the subclass that discriminator names.
    element is JsonObject && descriptor.kind == PolymorphicKind.SEALED -> {
        val discriminator = descriptor.annotations.filterIsInstance<JsonClassDiscriminator>().singleOrNull()?.discriminator
            ?: brainJson.configuration.classDiscriminator
        val type = element[discriminator]?.jsonPrimitive?.content
        descriptor.getElementDescriptor(1).elementDescriptors.firstOrNull { it.serialName == type }
            ?.let { withoutOptionalNulls(element, it) } ?: element
    }
    element is JsonObject && descriptor.kind == StructureKind.MAP ->
        JsonObject(element.mapValues { withoutOptionalNulls(it.value, descriptor.getElementDescriptor(1)) })
    element is JsonArray && descriptor.kind == StructureKind.LIST ->
        JsonArray(element.map { withoutOptionalNulls(it, descriptor.getElementDescriptor(0)) })
    else -> element
}

/**
 * Carries a brain type over the bridge as the contract shapes it. Writing uses [brainWire]. Reading accepts only that
 * exact form, as `z.strictObject` does: an optional field sent as `null`, a missing required one, or a quoted number or
 * boolean is refused like an unknown key.
 */
internal class BrainWireSerializer<T>(private val inner: KSerializer<T>) : KSerializer<T> {
    override val descriptor: SerialDescriptor = inner.descriptor
    override fun serialize(encoder: Encoder, value: T) = (encoder as JsonEncoder).encodeJsonElement(brainWire(inner, value))
    override fun deserialize(decoder: Decoder): T {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return brainJson.decodeFromJsonElement(inner, element).also { require(brainWire(inner, it) == element) }
    }
}

/** Strict structure checks shared by bridge input and persisted records. */
object BrainRules {
    fun uuid(value: String) = BridgeProtocol.UUID_PATTERN.matches(value)
    fun label(value: String, max: Int = BrainLimits.LABEL) = value.isNotEmpty() && value == value.trimEcma() && value.length <= max
    fun binding(value: BrainBinding?) = value == null || SharedRules.isModelId(value.modelId)
    fun key(profile: String, companionId: String) = SharedRules.isProfileId(profile) && uuid(companionId)
    fun lease(value: BrainLeased) =
        key(value.profile, value.companionId) && SharedRules.isCompanionName(value.name) && uuid(value.sessionId)
    /** A `brainRevision` of at least [min]. */
    fun revision(value: Long, min: Long = 0) = value in min..BrainLimits.REVISION
    /** A run command names the run, the run revision it saw and its own request. */
    fun runCommand(runId: String, expectedRunRevision: Long, requestId: String) =
        uuid(runId) && revision(expectedRunRevision) && uuid(requestId)
    fun inputs(ids: List<String>) = ids.size <= BrainLimits.INPUTS && ids.all(::uuid)
    /** A declared client vocabulary, or none from a page that predates it. */
    fun runtimes(names: List<String>?) = names == null || SharedRules.validClientRuntimes(names)

    fun configuration(value: BrainConfiguration): Boolean {
        val coordinator = value.coordinator
        if (value.version != 1 || !uuid(coordinator.id) || !binding(coordinator.binding)) return false
        if (coordinator.instructions.length > BrainLimits.ROLE_INSTRUCTIONS) return false
        if (value.specialists.size > BrainLimits.SPECIALISTS) return false
        val roles = mutableSetOf(coordinator.id)
        val slots = mutableSetOf<Int>()
        val validRoles = value.specialists.all {
            uuid(it.id) && roles.add(it.id) && slots.add(it.slot) && it.slot in 0 until BrainLimits.SPECIALISTS && label(it.name) &&
                binding(it.binding) && it.instructions.length <= BrainLimits.ROLE_INSTRUCTIONS
        }
        if (!validRoles) return false
        value.workflow?.let { workflow ->
            if (!uuid(workflow.id) || !label(workflow.name)) return false
            if (workflow.steps.size > BrainLimits.STEPS || workflow.sections.size > BrainLimits.STEPS) return false
            val sections = mutableSetOf<String>()
            // Every section is named, distinct and holds at least one step.
            val validSections = workflow.sections.all {
                uuid(it.id) && label(it.name) && sections.add(it.id) && workflow.steps.any { step -> step.sectionId == it.id }
            }
            if (!validSections) return false
            val steps = mutableSetOf<String>()
            val closed = mutableSetOf<String>()
            var previous: String? = null
            for (step in workflow.steps) {
                if (!uuid(step.id) || step.id in steps || !label(step.name)) return false
                if (step.instructions.length > BrainLimits.STEP_INSTRUCTIONS) return false
                if (maxOf(step.inputDescription.length, step.outputDescription.length) > BrainLimits.DESCRIPTION) return false
                if (step.specialistId != null && value.specialists.none { it.id == step.specialistId }) return false
                // A return goes to an earlier step, under a short condition.
                val back = step.returnTo
                if (back != null && (back.stepId !in steps || !label(back.condition, BrainLimits.CONDITION))) return false
                if (step.sectionId != null && step.sectionId !in sections) return false
                if (step.sectionId != previous) {
                    previous?.let(closed::add)
                    if (step.sectionId in closed) return false
                    previous = step.sectionId
                }
                steps.add(step.id)
            }
        }
        return brainWire(value).toString().length <= BrainLimits.CONFIGURATION
    }
}

/**
 * The same capability method set as BRAIN_METHODS in event-contracts. Each method reads its arguments in exactly the
 * contract's form ([BrainWireSerializer]) and writes its result with the contract's nulls.
 */
object BrainMethods {
    private fun <A, R> method(name: String, args: KSerializer<A>, result: KSerializer<R>, accepts: (A) -> Boolean) =
        BridgeMethod("brain.$name", BrainWireSerializer(args), result, accepts = accepts)
    private fun <A : BrainLeased> snapshot(name: String, args: KSerializer<A>, accepts: (A) -> Boolean = { true }) =
        method(name, args, BrainWireSerializer(BrainSnapshot.serializer())) { BrainRules.lease(it) && accepts(it) }

    val READ = method("read", BrainRead.serializer(), BrainWireSerializer(BrainReadResult.serializer())) {
        BrainRules.key(it.profile, it.companionId) && BrainRules.runtimes(it.supportedRuntimes)
    }
    val SAVE = method("save", BrainSave.serializer(), BrainWireSerializer(BrainSaveResult.serializer())) {
        BrainRules.key(it.profile, it.companionId) && BrainRules.revision(it.expectedRevision) && BrainRules.uuid(it.mutationId) &&
            BrainRules.configuration(it.configuration)
    }
    val OPEN = snapshot("open", BrainOpen.serializer()) { BrainRules.runtimes(it.supportedRuntimes) }
    val CLOSE = method("close", BrainLease.serializer(), NoResult.serializer(), BrainRules::lease)
    val SEND = snapshot("send", BrainSend.serializer()) {
        BrainRules.uuid(it.requestId) && BrainRules.revision(it.expectedRevision, min = 1) &&
            it.text.trimEcma().length in 1..ChatLimits.MESSAGE && BrainRules.inputs(it.inputIds)
    }
    val SNAPSHOT = snapshot("snapshot", BrainSnapshotRequest.serializer()) { (it.cursor?.length ?: 0) <= BrainLimits.CURSOR }
    val STOP = snapshot("stop", BrainLease.serializer())
    val STOP_SPEAKING = snapshot("stopSpeaking", BrainLease.serializer())
    val RETRY = snapshot("retry", BrainRetry.serializer()) {
        BrainRules.runCommand(it.runId, it.expectedRunRevision, it.requestId) && BrainRules.uuid(it.attemptId)
    }
    val RESUME = snapshot("resume", BrainResume.serializer()) {
        val target = it.targetRevision
        BrainRules.runCommand(it.runId, it.expectedRunRevision, it.requestId) && (target == null || BrainRules.revision(target, min = 1))
    }
    val SHOW_APPROVAL = method("showApproval", BrainShowApproval.serializer(), NoResult.serializer()) {
        BrainRules.lease(it) && BrainRules.uuid(it.runId) && BrainRules.uuid(it.approvalId)
    }
    val ADD_INPUT = method("addInput", BrainAddInput.serializer(), BrainWireSerializer(BrainArtifact.serializer())) {
        BrainRules.lease(it) && BrainRules.uuid(it.messageId) && it.text.length in 1..BrainLimits.INPUT &&
            BrainRules.label(it.title.trimEcma())
    }
    /** The person may take a while in the native file chooser. */
    val CHOOSE_INPUT = BridgeMethod(
        "brain.chooseInput",
        BrainWireSerializer(BrainChooseInput.serializer()),
        BrainWireSerializer(BrainArtifact.serializer()).nullable,
        timeout = 10.minutes,
    ) { BrainRules.lease(it) && BrainRules.uuid(it.messageId) }
    val READ_OUTPUT = method("readOutput", BrainReadOutput.serializer(), BrainWireSerializer(BrainOutputPage.serializer())) {
        BrainRules.lease(it) && BrainRules.uuid(it.runId) && BrainRules.uuid(it.artifactId) && it.cursor >= 0
    }

    fun pack(brain: Capability<BrainCapability>): CapabilityPack = binder.pack(brain)
    internal val binder = CapabilityBinder("brain", listOf<CapabilityHandler<BrainCapability>>(
        READ.handle { brain, args -> brain.read(args) },
        SAVE.handle { brain, args -> brain.save(args) },
        OPEN.handle { brain, args -> brain.open(args) },
        CLOSE.handle { brain, args -> brain.close(args); NoResult },
        SEND.handle { brain, args -> brain.send(args) },
        SNAPSHOT.handle { brain, args -> brain.snapshot(args) },
        STOP.handle { brain, args -> brain.stop(args) },
        STOP_SPEAKING.handle { brain, args -> brain.stopSpeaking(args) },
        RETRY.handle { brain, args -> brain.retry(args) },
        RESUME.handle { brain, args -> brain.resume(args) },
        SHOW_APPROVAL.handle { brain, args -> brain.showApproval(args); NoResult },
        ADD_INPUT.handle { brain, args -> brain.addInput(args) },
        CHOOSE_INPUT.handle { brain, args -> brain.chooseInput(args) },
        READ_OUTPUT.handle { brain, args -> brain.readOutput(args) },
    ))
}
