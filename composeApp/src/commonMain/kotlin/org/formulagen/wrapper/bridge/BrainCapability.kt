package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import kotlin.time.Duration.Companion.minutes

/** Native companion coordination; web calls cannot answer a permission dialog. */
interface BrainCapability { suspend fun call(method: String, args: JsonObject): JsonElement }

object BrainLimits {
    const val SPECIALISTS = 20
    const val STEPS = 30
    const val CONFIGURATION = 48 * 1024
    const val ROLE_INSTRUCTIONS = 4096
    const val STEP_INSTRUCTIONS = 2048
    const val DESCRIPTION = 512
    const val LABEL = 80
    const val RETURNS = 3
    const val DISPATCHES = 60
    const val INPUT = 32000
    const val OUTPUT = 16000
}

/** Explicit nulls are significant in the new protocol. */
val brainJson = Json { encodeDefaults = true; explicitNulls = true; ignoreUnknownKeys = false }
@Serializable data class BrainBinding(val runtimeId: RuntimeId, val modelId: String, val effort: ModelEffort? = null) {
    fun member() = TeamMember(runtimeId, modelId)
}
@Serializable data class CoordinatorRole(val id: String, val binding: BrainBinding?, val instructions: String)
@Serializable data class SpecialistRole(val id: String, val name: String, val instructions: String, val mode: MemberMode, val binding: BrainBinding?, val slot: Int)
@Serializable data class BrainReturn(val stepId: String, val condition: String)
@Serializable data class WorkflowStep(val id: String, val name: String, val specialistId: String?, val instructions: String,
    val inputDescription: String, val outputDescription: String, val askBeforeStarting: Boolean, val sectionId: String?, val returnTo: BrainReturn?)
@Serializable data class BrainSection(val id: String, val name: String)
@Serializable data class BrainWorkflow(val id: String, val name: String, val enabled: Boolean, val steps: List<WorkflowStep>, val sections: List<BrainSection>)
@Serializable data class BrainConfiguration(val version: Int, val coordinator: CoordinatorRole, val specialists: List<SpecialistRole>, val workflow: BrainWorkflow?)
@Serializable data class BrainDocument(val revision: Long, val configuration: BrainConfiguration)
@Serializable data class BrainIssue(val reason: String, val roleId: String? = null, val stepId: String? = null)
@Serializable data class BrainRoleSnapshot(val roleId: String, val name: String, val binding: BrainBinding, val instructions: String, val mode: MemberMode)
@Serializable data class BrainSource(val kind: String, val messageId: String? = null, val attemptId: String? = null)
@Serializable data class BrainArtifact(val id: String, val title: String, val digest: String, val source: BrainSource, val mediaType: String, val preview: String)
@Serializable data class BrainAttempt(val id: String, val runId: String, val stepId: String?, val role: BrainRoleSnapshot, val configurationRevision: Long,
    val inputIds: List<String>, val outputIds: List<String> = emptyList(), val state: String = "queued", val outcome: String? = null)
@Serializable data class BrainSkip(val stepId: String, val reason: String)
@Serializable data class BrainRun(val id: String, val revision: Long, val configurationRevision: Long, val workflowId: String?, val state: String,
    val currentAttemptId: String?, val skipped: List<BrainSkip> = emptyList(), val returnsUsed: Int = 0, val dispatchesUsed: Int = 0)
@Serializable data class BrainWaiting(val kind: String, val summary: String, val approvalId: String? = null, val attemptId: String? = null)
@Serializable data class BrainEntry(val id: String, val author: String, val text: String, val role: BrainRoleSnapshot? = null, val activity: Activity? = null)
@Serializable data class BrainSnapshot(val configurationRevision: Long, val coordinator: BrainRoleSnapshot?, val messages: List<BrainEntry>, val run: BrainRun?,
    val attempts: List<BrainAttempt>, val artifacts: List<BrainArtifact>, val waiting: BrainWaiting?, val voice: ChatVoice, val cursor: String, val notice: String? = null)

/** Drop optional nulls while retaining required nullable fields. */
fun brainWire(element: JsonElement): JsonElement = when (element) {
    is JsonObject -> JsonObject(element.filterNot { (key, value) -> value == JsonNull && key in setOf("effort", "roleId", "stepId", "messageId", "attemptId", "approvalId", "outcome", "role", "activity", "notice") && !(key == "stepId" && "runId" in element) }.mapValues { brainWire(it.value) })
    is JsonArray -> JsonArray(element.map(::brainWire))
    else -> element
}
inline fun <reified T> brainWire(value: T): JsonElement = brainWire(brainJson.encodeToJsonElement(value))

/** Strict structure checks shared by bridge input and persisted records. */
object BrainRules {
    fun uuid(value: String) = BridgeProtocol.UUID_PATTERN.matches(value)
    fun label(value: String, max: Int = BrainLimits.LABEL) = value.isNotEmpty() && value == value.trimEcma() && value.length <= max
    fun binding(value: BrainBinding?) = value == null || SharedRules.isModelId(value.modelId)
    fun configuration(value: BrainConfiguration): Boolean {
        if (value.version != 1 || !uuid(value.coordinator.id) || !binding(value.coordinator.binding) || value.coordinator.instructions.length > BrainLimits.ROLE_INSTRUCTIONS || value.specialists.size > BrainLimits.SPECIALISTS) return false
        val roles = mutableSetOf(value.coordinator.id)
        val slots = mutableSetOf<Int>()
        if (value.specialists.any { !uuid(it.id) || !roles.add(it.id) || !slots.add(it.slot) || it.slot !in 0 until BrainLimits.SPECIALISTS || !label(it.name) || !binding(it.binding) || it.instructions.length > BrainLimits.ROLE_INSTRUCTIONS }) return false
        value.workflow?.let { workflow ->
            if (!uuid(workflow.id) || !label(workflow.name) || workflow.steps.size > BrainLimits.STEPS || workflow.sections.size > BrainLimits.STEPS) return false
            val sections = mutableSetOf<String>()
            if (workflow.sections.any { !uuid(it.id) || !label(it.name) || !sections.add(it.id) || workflow.steps.none { step -> step.sectionId == it.id } }) return false
            val steps = mutableSetOf<String>()
            val closed = mutableSetOf<String>()
            var previous: String? = null
            for (step in workflow.steps) {
                if (!uuid(step.id) || step.id in steps || !label(step.name) || step.instructions.length > BrainLimits.STEP_INSTRUCTIONS || step.inputDescription.length > BrainLimits.DESCRIPTION || step.outputDescription.length > BrainLimits.DESCRIPTION) return false
                if (step.specialistId != null && value.specialists.none { it.id == step.specialistId }) return false
                if (step.returnTo != null && (step.returnTo.stepId !in steps || !label(step.returnTo.condition, 240))) return false
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

    /** Each method accepts exactly its documented fields. */
    fun arguments(method: String, args: JsonObject): Boolean = runCatching {
        val key = setOf("profile", "companionId")
        val lease = key + setOf("name", "sessionId")
        val command = lease + setOf("runId", "expectedRunRevision", "requestId")
        val required = when (method) {
            "read" -> key
            "save" -> key + setOf("expectedRevision", "mutationId", "configuration")
            "send" -> lease + setOf("requestId", "expectedRevision", "text", "inputIds", "speak")
            "retry" -> command + "attemptId"
            "resume" -> command
            "showApproval" -> lease + setOf("runId", "approvalId")
            "addInput" -> lease + setOf("messageId", "title", "text")
            "chooseInput" -> lease + "messageId"
            "readOutput" -> lease + setOf("runId", "artifactId", "cursor")
            else -> lease
        }
        val optional = when (method) { "snapshot" -> setOf("cursor"); "resume" -> setOf("targetRevision"); else -> emptySet() }
        require(args.keys.containsAll(required) && args.keys.all { it in required + optional })
        fun string(key: String) = (args[key] as JsonPrimitive).also { require(it.isString) }.content
        require(SharedRules.isProfileId(string("profile")) && uuid(string("companionId")))
        if ("name" in required) require(SharedRules.isCompanionName(string("name")))
        for (field in listOf("sessionId", "requestId", "mutationId", "runId", "attemptId", "approvalId", "messageId", "artifactId")) if (field in args) require(uuid(string(field)))
        for (field in listOf("expectedRevision", "expectedRunRevision", "targetRevision")) if (field in args) {
            val raw = args.getValue(field).jsonPrimitive
            require(!raw.isString && raw.long in 0..9_007_199_254_740_991L)
            if (field == "targetRevision" || method == "send") require(raw.long > 0)
        }
        if (method == "save") {
            val raw = args.getValue("configuration")
            val decoded = brainJson.decodeFromJsonElement<BrainConfiguration>(raw)
            require(configuration(decoded))
            // Optional fields may be omitted, never sent as explicit null.
            require(raw == brainWire(decoded))
        }
        if (method == "send" || method == "addInput") require(string("text").let { it.isNotEmpty() && it.length <= (if (method == "send") ChatLimits.MESSAGE else BrainLimits.INPUT) && (method != "send" || it == it.trimEcma()) })
        if ("title" in args) require(label(string("title")))
        if ("speak" in args) require(!args.getValue("speak").jsonPrimitive.isString && args.getValue("speak").jsonPrimitive.booleanOrNull != null)
        if ("inputIds" in args) require(args.getValue("inputIds").jsonArray.let { it.size <= BrainLimits.STEPS && it.all { value -> value.jsonPrimitive.isString && uuid(value.jsonPrimitive.content) } })
        if (method == "snapshot" && "cursor" in args) require(string("cursor").length <= 80)
        if (method == "readOutput") require(args.getValue("cursor").jsonPrimitive.let { !it.isString && it.int >= 0 })
        true
    }.getOrDefault(false)
}

/** The same capability method set as BRAIN_METHODS in event-contracts. */
object BrainMethods {
    val names = listOf("read", "save", "open", "close", "send", "snapshot", "stop", "stopSpeaking", "retry", "resume", "showApproval", "addInput", "chooseInput", "readOutput")
    private val methods = names.map { name -> BridgeMethod("brain.$name", JsonObject.serializer(), JsonElement.serializer(), timeout = if (name == "chooseInput") 10.minutes else BridgeMethod.DEFAULT_TIMEOUT, accepts = { BrainRules.arguments(name, it) }) }
    private val binder = CapabilityBinder("brain", methods.map { method -> method.handle { brain: BrainCapability, args -> brain.call(method.name.removePrefix("brain."), args) } })
    fun pack(brain: Capability<BrainCapability>): CapabilityPack = binder.pack(brain)
}
