package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.KeepGeneratedSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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
 * The BRAIN_LIMITS of event-contracts this Mac applies, and those of its LIBRARY_LIMITS it needs ([WORKFLOWS] and the
 * provider skills it reports), checked against `fixtures/limits.json`; the rest are the page's and the server's.
 * [INPUTS] bounds the inputs one message attaches.
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
    /** `LIBRARY_LIMITS.workflowsPerBrain`: the workflows one version 2 brain holds. */
    const val WORKFLOWS = 10
    /** `LIBRARY_LIMITS.providerSkills`: the installed skills this Mac reports for one provider. */
    const val PROVIDER_SKILLS = 64
    /** `LIBRARY_LIMITS.providerSkillName`: the longest provider skill name. */
    const val PROVIDER_SKILL_NAME = 64
    /** `LIBRARY_LIMITS.useText`: a provider skill's description, a Hovel skill's Use it when and a skill group's. */
    const val USE_TEXT = 512
    /**
     * `LIBRARY_LIMITS.skillsPerSpecialistInBrain`: the most skills one specialist uses in a brain, those it learned
     * (at most 12) and those the brain's workflows bring for its steps.
     */
    const val SKILLS_PER_SPECIALIST = 24
    /** `LIBRARY_LIMITS.skillGroups`: the groups one specialist sorts its skills into. */
    const val SKILL_GROUPS = 4
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

/**
 * A brain's specialist. [skills] and [skillGroups] are version 2's (`compiledSpecialist`): its task carries its skills,
 * sorted by group, and a provider skill this Mac cannot use pauses it before it starts (`RoleReadiness.kt`). A group
 * only sorts skills (contract 13, `skillGroups`); the brain sends only groups that hold one of [skills], and none as
 * an absent field ([BrainConfigurationSerializer]).
 */
@Serializable
data class SpecialistRole(
    val id: String,
    val name: String,
    val instructions: String,
    val mode: MemberMode,
    val binding: BrainBinding?,
    val slot: Int,
    val skills: List<SkillRef> = emptyList(),
    val skillGroups: List<SkillGroup> = emptyList(),
) {
    init {
        require(skills.size <= BrainLimits.SKILLS_PER_SPECIALIST && skillGroups.size <= BrainLimits.SKILL_GROUPS)
        require(skills.all { skill -> skill.group == null || skillGroups.any { it.id == skill.group } }) { "A skill sits in one of its specialist's groups" }
    }

    /** The group [skill] sits in, if any. */
    fun groupOf(skill: SkillRef): SkillGroup? = skill.group?.let { id -> skillGroups.find { it.id == id } }
}

/** `skillGroup`: a group a specialist sorts some of its skills into, such as "Game skills", and when to use them together. */
@Serializable
data class SkillGroup(val id: String, val name: String, val useWhen: String)

/**
 * `skillRef`: a skill a version 2 brain's specialist uses (contract 12), one of the brain's own ([Hovel], in
 * [BrainConfiguration.skills]), which works on every provider, or one installed in [Provider.runtimeId] on a Mac, named
 * as that provider names it, which works only when the specialist runs on that provider on a Mac that has it. [group]
 * names the specialist's [SkillGroup] it sits in, if any (contract 13); it is still its own skill.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("source")
sealed interface SkillRef {
    val group: String?

    @Serializable @SerialName("hovel")
    data class Hovel(val skillId: String, override val group: String? = null) : SkillRef

    @Serializable @SerialName("provider")
    data class Provider(val runtimeId: RuntimeId, val name: String, override val group: String? = null) : SkillRef
}

/** `brainSkill`: a Hovel skill a version 2 brain carries for its specialists ([SkillRef.Hovel]), sent with each task. */
@Serializable
data class BrainSkill(val id: String, val name: String, val instructions: String, val useWhen: String)

/**
 * `providerSkill`: a skill a provider has installed on this Mac, as the provider names ([name]) and describes it. The
 * Mac reports them in its provider directory; Hovel never holds a provider skill's contents.
 */
@Serializable
data class ProviderSkill(val name: String, val description: String) {
    init {
        require(isName(name)) { "A provider skill name is one lower-case token" }
        require(description.length <= BrainLimits.USE_TEXT)
    }

    companion object {
        /** `providerSkillName`: 1–64 characters, a lower-case letter or digit, then letters, digits and `.`, `_`, `:`, `-`. */
        private val NAME = Regex("^[a-z0-9][a-z0-9._:-]*$")

        fun isName(value: String) = value.length <= BrainLimits.PROVIDER_SKILL_NAME && NAME.matches(value)
    }
}

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

/**
 * One of a brain's workflows. [useFor] (what the coordinator follows it for) and [isDefault] (its first choice when a
 * request fits several) are version 2's (`brainWorkflowV2`): empty and false in version 1, whose one workflow needs
 * neither.
 */
@Serializable
data class BrainWorkflow(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val steps: List<WorkflowStep>,
    val sections: List<BrainSection>,
    val useFor: String = "",
    @SerialName("default") val isDefault: Boolean = false,
)

/** Hosted decision selection does not replace any chat runtime binding. */
@Serializable
enum class DecisionModelSelection {
    @SerialName("default") DEFAULT,
    @SerialName("jev") JEV,
    @SerialName("laya") LAYA,
}

/**
 * `brainConfiguration`, either version, read the same way: [workflows] is version 1's one workflow (or none) or version
 * 2's list, whose step ids are unique across them. Version 2 also carries [skills], the Hovel skills its specialists'
 * [SpecialistRole.skills] name. [BrainConfigurationSerializer] keeps each version's own shape.
 */
@OptIn(ExperimentalSerializationApi::class)
@KeepGeneratedSerializer
@Serializable(with = BrainConfigurationSerializer::class)
data class BrainConfiguration(
    val version: Int,
    val coordinator: CoordinatorRole,
    val specialists: List<SpecialistRole>,
    val workflows: List<BrainWorkflow>,
    val decisionModel: DecisionModelSelection? = null,
    val specialistAdvice: Boolean? = null,
    val skills: List<BrainSkill> = emptyList(),
) {
    init {
        require(version == 1 || version == 2) { "Unknown brain version $version" }
        require(workflows.size <= if (version == 1) 1 else BrainLimits.WORKFLOWS)
        // Version 1 holds nothing of version 2's, so writing it back loses nothing.
        require(version == 2 || (skills.isEmpty() && specialists.all { it.skills.isEmpty() && it.skillGroups.isEmpty() } &&
            workflows.all { it.useFor.isEmpty() && !it.isDefault }))
    }

    /** The workflows the coordinator may follow, in the brain's order. */
    val enabledWorkflows: List<BrainWorkflow> get() = workflows.filter { it.enabled }

    /** The step [id] names, in whichever workflow holds it. */
    fun step(id: String?): WorkflowStep? = id?.let { workflows.firstNotNullOfOrNull { workflow -> workflow.steps.find { it.id == id } } }
}

/**
 * Reads and writes a [BrainConfiguration] in its version's shape. Version 1 holds `workflow`, one or `null`, and none
 * of version 2's fields; version 2 holds `workflows`, `skills`, each workflow's `useFor` and `default`, and each
 * specialist's `skills` and, when it has any, `skillGroups`. Reading refuses a field of the other version, as
 * `z.strictObject` does.
 */
internal object BrainConfigurationSerializer : JsonTransformingSerializer<BrainConfiguration>(BrainConfiguration.generatedSerializer()) {
    private val WORKFLOW_V2 = setOf("useFor", "default")
    private const val SKILLS = "skills"
    private const val GROUPS = "skillGroups"

    private fun isVersion1(value: JsonObject) = value["version"] == JsonPrimitive(1)

    override fun transformDeserialize(element: JsonElement): JsonElement {
        val value = element.jsonObject
        if (!isVersion1(value)) return value
        val workflow = value["workflow"] ?: throw SerializationException("A version 1 brain names its workflow or null")
        val specialists = value["specialists"]?.jsonArray.orEmpty()
        if ("workflows" in value || SKILLS in value || specialists.any { SKILLS in it.jsonObject || GROUPS in it.jsonObject } ||
            (workflow as? JsonObject)?.keys.orEmpty().any { it in WORKFLOW_V2 }) {
            throw SerializationException("Only a version 2 brain holds several workflows or skills")
        }
        return JsonObject(buildMap {
            for ((key, item) in value) {
                if (key == "workflow") put("workflows", JsonArray(listOfNotNull(item.takeIf { it != JsonNull }))) else put(key, item)
            }
        })
    }

    override fun transformSerialize(element: JsonElement): JsonElement {
        val value = element.jsonObject
        if (!isVersion1(value)) {
            // A version 2 specialist with no groups sends none, as the contract's optional `skillGroups`.
            val specialists = value["specialists"]?.jsonArray ?: return value
            return JsonObject(value + ("specialists" to JsonArray(specialists.map { specialist ->
                val groups = specialist.jsonObject[GROUPS]
                if (groups is JsonArray && groups.isEmpty()) JsonObject(specialist.jsonObject - GROUPS) else specialist
            })))
        }
        return JsonObject(buildMap {
            for ((key, item) in value) when (key) {
                "workflows" -> put("workflow", item.jsonArray.singleOrNull()?.let { JsonObject(it.jsonObject - WORKFLOW_V2) } ?: JsonNull)
                "specialists" -> put(key, JsonArray(item.jsonArray.map { JsonObject(it.jsonObject - SKILLS - GROUPS) }))
                SKILLS -> Unit
                else -> put(key, item)
            }
        })
    }
}

@Serializable
data class BrainDocument(val revision: Long, val configuration: BrainConfiguration)

/**
 * `brainReadinessIssue.reason`. `missing-skill`: a provider skill the specialist uses cannot be used for it on this Mac
 * (`RoleReadiness.kt`).
 */
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
    @SerialName("missing-skill") MISSING_SKILL,
}

/** Why a role cannot run. A `missing-skill` issue names its [skill]. */
@Serializable
data class BrainIssue(
    val reason: BrainIssueReason,
    val roleId: String? = null,
    val stepId: String? = null,
    val skill: SkillRef.Provider? = null,
)

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
