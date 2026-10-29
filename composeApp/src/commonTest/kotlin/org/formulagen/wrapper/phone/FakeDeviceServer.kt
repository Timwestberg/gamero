package org.formulagen.wrapper.phone

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalAnswerRecord
import org.formulagen.wrapper.conversations.ApprovalKind
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalPresentation
import org.formulagen.wrapper.conversations.ApprovalState
import org.formulagen.wrapper.conversations.ApprovalSurface

internal const val OWNER = "7fa0000b-0000-4000-8000-000000000001"
internal const val COMPUTER = "75a0000b-0000-4000-8000-000000000001"
internal const val APP_URL = "https://hovel.example/hovel"

/** An open approval of [OWNER]'s, on the studio Mac, expiring at [expiresAt]. */
internal fun openApproval(
    id: String,
    presentation: ApprovalPresentation = ApprovalPresentation.Anywhere(listOf("Run ./deploy.sh", "Edit [design-docs]/plan.md")),
    expiresAt: String = "2026-10-29T12:30:00.000Z",
    kind: ApprovalKind = ApprovalKind.PERMISSION,
    options: List<ApprovalOption> = listOf(ApprovalOption.ALLOW_ONCE, ApprovalOption.DENY),
) = DeviceApproval(
    AgentApproval(null, COMPUTER, "71a0000b-0000-4000-8000-000000000001", expiresAt, id, kind, options, presentation, 0,
        ApprovalState.OPEN, null),
    companionName = "Ember", computerName = "Studio Mac", projectName = "Hovel notes",
)

/**
 * hovel-service's device routes, in memory, with the server's rules: the device token must be one the owner
 * registered, an answer is first-answer-wins against the stored revision, and a Mac-only approval is refused.
 * [beforeAnswer] runs just before an answer lands, so a test can let another surface win the race.
 */
internal class FakeDeviceServer : DeviceHttp {
    /** Device id to the token the account registered for it. */
    val devices = mutableMapOf<String, String>()
    val approvals = mutableMapOf<String, DeviceApproval>()
    val requests = mutableListOf<DeviceRequest>()
    var beforeAnswer: () -> Unit = {}
    /** When set, every call fails as if the network were down. */
    var offline = false

    override suspend fun send(request: DeviceRequest): DeviceResponse {
        requests += request
        if (offline) throw IllegalStateException("offline")
        val path = request.url.removePrefix("https://hovel.example/api/hovel/v1/hovel/devices/").split('/')
        val (owner, device) = path[0] to path[1]
        if (request.headers[HovelContract.HEADER] != "1") return DeviceResponse(400, "{}")
        if (owner != OWNER || devices[device] == null || devices[device] != request.headers[DeviceRoute.TOKEN_HEADER]) {
            return DeviceResponse(401, "{}")
        }
        val rest = path.drop(2)
        return when {
            request.method == "GET" && rest == listOf("approvals") ->
                ok(DeviceApprovalList.serializer(), DeviceApprovalList(approvals.values.filter { it.approval.state == ApprovalState.OPEN }))
            request.method == "GET" && rest.size == 2 && rest[0] == "approvals" ->
                approvals[rest[1]]?.let { ok(DeviceApproval.serializer(), it) } ?: DeviceResponse(404, "{}")
            request.method == "POST" && rest.size == 3 && rest[2] == "answer" -> answer(device, rest[1], request.body!!)
            else -> DeviceResponse(404, "{}")
        }
    }

    /** Another surface answers [id] first: the Mac, or another phone. */
    fun answerElsewhere(id: String, option: ApprovalOption, by: ApprovalSurface = ApprovalSurface.MAC) {
        val shown = approvals.getValue(id)
        val deviceId = if (by == ApprovalSurface.MAC) COMPUTER else "79a0000b-0000-4000-8000-0000000000ff"
        approvals[id] = shown.copy(approval = shown.approval.copy(answer = ApprovalAnswerRecord(by, deviceId, option),
            revision = shown.approval.revision + 1, state = ApprovalState.ANSWERED))
    }

    private fun answer(device: String, id: String, body: String): DeviceResponse {
        beforeAnswer()
        val shown = approvals[id] ?: return DeviceResponse(404, "{}")
        val answer = brainJson.decodeFromString(ApprovalAnswer.serializer(), body)
        val stored = shown.approval
        if (stored.state != ApprovalState.OPEN) {
            val mine = stored.answer?.let { it.by == ApprovalSurface.PHONE && it.deviceId == device && it.option == answer.option } == true
            return ok(ApprovalAnswerResult.serializer(), ApprovalAnswerResult(stored, if (mine) ApprovalAnswerStatus.ANSWERED else ApprovalAnswerStatus.LOST))
        }
        if (stored.presentation is ApprovalPresentation.MacOnly || answer.option !in stored.options || answer.expectedRevision != stored.revision) {
            return DeviceResponse(409, "{}")
        }
        val won = stored.copy(answer = ApprovalAnswerRecord(ApprovalSurface.PHONE, device, answer.option), revision = stored.revision + 1,
            state = ApprovalState.ANSWERED)
        approvals[id] = shown.copy(approval = won)
        return ok(ApprovalAnswerResult.serializer(), ApprovalAnswerResult(won, ApprovalAnswerStatus.ANSWERED))
    }

    private fun <T> ok(serializer: KSerializer<T>, value: T): DeviceResponse {
        val data: JsonElement = brainJson.encodeToJsonElement(serializer, value)
        return DeviceResponse(200, buildJsonObject { put("data", data) }.toString())
    }

    val answers: List<DeviceRequest> get() = requests.filter { it.method == "POST" }
}

/** The platform store, in memory. */
internal class FakeVault(var item: String? = null) : DeviceVault {
    override fun read() = item
    override fun write(value: String) { item = value }
    override fun clear() { item = null }
}

/** Counting "randomness" and a stand-in digest; the real SHA-256 is checked on each platform. */
internal class FakeCrypto : DeviceCrypto {
    private var next = 0
    override fun randomBytes(count: Int) = ByteArray(count) { (next++).toByte() }
    override fun sha256(bytes: ByteArray) = ByteArray(32) { index -> bytes.getOrElse(index) { 0 } }
}

/**
 * Dialogs a test answers: [confirmAnswer] for registration, [answer] for the next approval (completed by the test),
 * and what was shown or closed unanswered.
 */
internal class ScriptedDialogs : ApprovalDialogs {
    var confirmAnswer = true
    var answer = CompletableDeferred<ApprovalOption?>()
    val confirmed = mutableListOf<DevicePrompt>()
    val asked = mutableListOf<ApprovalPrompt>()
    val told = mutableListOf<ApprovalNotice>()
    /** Notices stay up until the test completes this; by default they close at once. */
    var noticeClosed: CompletableDeferred<Unit>? = null
    var closedUnanswered = 0

    override suspend fun confirm(prompt: DevicePrompt): Boolean {
        confirmed += prompt
        return confirmAnswer
    }

    override suspend fun ask(prompt: ApprovalPrompt): ApprovalOption? {
        asked += prompt
        try {
            return answer.await()
        } catch (cancelled: CancellationException) {
            closedUnanswered++
            throw cancelled
        }
    }

    override suspend fun tell(notice: ApprovalNotice) {
        told += notice
        try {
            noticeClosed?.await()
        } catch (cancelled: CancellationException) {
            closedUnanswered++
            throw cancelled
        }
    }
}
