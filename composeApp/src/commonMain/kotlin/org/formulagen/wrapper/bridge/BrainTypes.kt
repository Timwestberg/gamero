package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
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
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.MemberMode
import org.formulagen.wrapper.conversation.TeamMember

/*
 * A companion's brain as the account holds it, and the run state a conversation keeps on this Mac. The claim carries the
 * brain ([BrainDocument]); the types read and write the contract's JSON through [brainWire] and [BrainWireSerializer].
 */

/**
 * The BRAIN_LIMITS of event-contracts this Mac applies, checked against `fixtures/limits.json`; the rest are the page's
 * and the server's. [INPUTS] bounds the inputs one message attaches.
 */
object BrainLimits {
    const val DESCRIPTION = 512
    const val DISPATCHES = 60
    const val HISTORY = 40
    const val INPUT = 32000
    const val INPUTS = 30
    const val LABEL = 80
    const val OUTPUT = 16000
    const val PREVIEW = 512
    const val RETURNS = 3
    const val STEP_INSTRUCTIONS = 2048
    const val STEPS = 30
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

/** Hosted decision selection does not replace any chat runtime binding. */
@Serializable
enum class DecisionModelSelection {
    @SerialName("default") DEFAULT,
    @SerialName("jev") JEV,
    @SerialName("laya") LAYA,
}

@Serializable
data class BrainConfiguration(
    val version: Int,
    val coordinator: CoordinatorRole,
    val specialists: List<SpecialistRole>,
    val workflow: BrainWorkflow?,
    val decisionModel: DecisionModelSelection? = null,
    val specialistAdvice: Boolean? = null,
)

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
    /** Waiting for the person, so their retry of the message that drove it may continue it. */
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

/** Explicit choices for an ordinary coordinator question, never approval options. */
@Serializable
data class BrainQuestion(val choices: List<String> = emptyList()) {
    init {
        require(choices.size <= 4)
        require(choices.all { it.isNotBlank() && it.length <= 240 })
        require(choices.map { it.trim() }.distinct().size == choices.size)
    }
}

/** One conversation entry. [role] is the role as it was when it wrote the entry, so renaming it later rewrites nothing. */
@Serializable
data class BrainEntry(
    val id: String,
    val author: BrainAuthor,
    val text: String,
    val role: BrainRoleSnapshot? = null,
    val activity: Activity? = null,
    val question: BrainQuestion? = null,
)

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
 * Reads and writes a brain type as the contract shapes it. Writing uses [brainWire]. Reading accepts only that exact
 * form, as `z.strictObject` does: an optional field sent as `null`, a missing required one, or a quoted number or
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

/** Structure checks of the ids a coordinator's decision names. */
object BrainRules {
    fun uuid(value: String) = BridgeProtocol.UUID_PATTERN.matches(value)
    fun inputs(ids: List<String>) = ids.size <= BrainLimits.INPUTS && ids.all(::uuid)
}
