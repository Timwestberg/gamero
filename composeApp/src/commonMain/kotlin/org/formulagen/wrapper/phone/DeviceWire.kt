package org.formulagen.wrapper.phone

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalOption

/*
 * The approval device payloads of event-contracts `hovel/companions` (`device.schemas.ts`) that a phone's native
 * code reads and sends, one Kotlin type per schema, named after it, and the device routes it calls.
 */

/** `deviceApproval`: an approval with the names of where it comes from, for the phone's dialog. */
@Serializable
internal data class DeviceApproval(
    val approval: AgentApproval,
    val companionName: String,
    val computerName: String,
    val projectName: String,
)

/** `deviceApprovalList`: the owner's open approvals, newest first. */
@Serializable
internal data class DeviceApprovalList(val approvals: List<DeviceApproval>)

/** `approvalAnswer`: the phone's answer, against the revision it read. The device token says who answers. */
@Serializable
internal data class ApprovalAnswer(val expectedRevision: Long, val option: ApprovalOption)

/** `APPROVAL_ANSWER_STATUSES`. */
@Serializable
internal enum class ApprovalAnswerStatus {
    @SerialName("answered") ANSWERED,
    @SerialName("lost") LOST,
}

/** `approvalAnswerResult`: this answer won (`answered`), or the approval had already ended another way (`lost`). */
@Serializable
internal data class ApprovalAnswerResult(val approval: AgentApproval, val status: ApprovalAnswerStatus)

/**
 * The device routes, `HOVEL_COMPANION_ROUTES.device` under `HOVEL_DEVICE_BASE`, which the phone calls with its own
 * `x-hovel-device-token`. A fixture test checks this table against the server's.
 */
internal enum class DeviceRoute(val method: String, val template: String) {
    /** → `deviceApprovalList`. */
    APPROVAL_LIST("GET", "/approvals"),

    /** → `deviceApproval`, open or not. */
    APPROVAL_READ("GET", "/approvals/:approvalId"),

    /** `approvalAnswer` → `approvalAnswerResult`. */
    APPROVAL_ANSWER("POST", "/approvals/:approvalId/answer");

    /** The full path for one device, with [approvalId] filled in where the route names one. */
    fun path(ownerId: String, deviceId: String, approvalId: String? = null): String {
        require(SharedRules.isRecordId(ownerId) && SharedRules.isRecordId(deviceId)) { "A device route names lower-case ids" }
        val hasApproval = ":approvalId" in template
        require(hasApproval == (approvalId != null) && (approvalId == null || SharedRules.isRecordId(approvalId))) {
            "A device route names its approval by a lower-case id"
        }
        val route = if (approvalId == null) template else template.replace(":approvalId", approvalId)
        return DEVICE_BASE.replace(":ownerId", ownerId).replace(":deviceId", deviceId) + route
    }

    companion object {
        /** `HOVEL_DEVICE_BASE`. */
        const val DEVICE_BASE = "/v1/hovel/devices/:ownerId/:deviceId"

        /** `HOVEL_DEVICE_TOKEN_HEADER`. */
        const val TOKEN_HEADER = "x-hovel-device-token"

        /** Where the web app's edge serves hovel-service, under the app's own origin. */
        const val API_PREFIX = "/api/hovel"
    }
}
