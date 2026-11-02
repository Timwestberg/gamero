package org.formulagen.wrapper.conversations

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/*
 * The approval payloads of event-contracts `hovel/companions` (`approval.schemas.ts`), one Kotlin type per schema,
 * named after it. Shared by every platform: the Mac opens and closes approvals, and a registered phone reads and
 * answers them. The Mac's routing payloads are in jvmMain's ConversationWire.kt.
 */

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
