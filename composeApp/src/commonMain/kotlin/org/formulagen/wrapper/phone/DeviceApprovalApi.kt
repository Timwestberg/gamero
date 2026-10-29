package org.formulagen.wrapper.phone

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.TrustedOrigin
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.brainWire

/** One request the phone's native code sends. Built here from the device's own credential, never by the page. */
internal data class DeviceRequest(val method: String, val url: String, val headers: Map<String, String>, val body: String?)

/** What the server answered: its status and its body as text. */
internal data class DeviceResponse(val status: Int, val body: String)

/**
 * The platform's HTTP client: NSURLSession on iOS, HttpURLConnection on Android, a fake server in tests. It throws
 * only when no answer arrived; any status is a [DeviceResponse].
 */
internal fun interface DeviceHttp {
    suspend fun send(request: DeviceRequest): DeviceResponse
}

/** The server refused a device call with [status]; 401, 404 and 409 have their own subclasses. */
internal open class DeviceCallException(val status: Int) : IllegalStateException("hovel-service answered HTTP $status")

/** 401: the device is unknown, revoked, or its token is wrong. The phone forgets its registration. */
internal class DeviceUnauthorizedException : DeviceCallException(401)

/** 404: the approval is not the owner's, or it is gone with its conversation. */
internal class DeviceNotFoundException : DeviceCallException(404)

/** 409: the answer can never apply (Mac-only, not offered, or another revision). */
internal class DeviceRefusedException : DeviceCallException(409)

/** 403: the approval's Mac has not confirmed this phone. */
internal class DeviceNotPairedException : DeviceCallException(403)

/**
 * The device routes over [http], for the web app at [appUrl]: hovel-service under its origin's `/api/hovel`, as the
 * Mac reaches it. Every request carries the device token and the contract header; every answer's `data` is read
 * strictly as its contract shape, so an unknown key fails rather than being shown.
 */
internal class DeviceApprovalApi(private val http: DeviceHttp, appUrl: String) {
    private val base = TrustedOrigin(appUrl).rule + DeviceRoute.API_PREFIX

    /** The device as the server keeps it: pending or paired, and the Macs that confirmed it. */
    suspend fun status(device: DeviceCredential): ApprovalDevice =
        send(DeviceRoute.STATUS, device, null, null, ApprovalDevice.serializer())

    /** A new pairing code, for this phone's own native screen. */
    suspend fun pairingCode(device: DeviceCredential): DevicePairingCode =
        send(DeviceRoute.PAIRING_CODE, device, null, "{}", DevicePairingCode.serializer())

    /** Revoke this phone's device with its own token. */
    suspend fun revoke(device: DeviceCredential): ApprovalDevice =
        send(DeviceRoute.REVOKE, device, null, "{}", ApprovalDevice.serializer())

    suspend fun list(device: DeviceCredential): DeviceApprovalList =
        send(DeviceRoute.APPROVAL_LIST, device, null, null, DeviceApprovalList.serializer())

    suspend fun read(device: DeviceCredential, approvalId: String): DeviceApproval =
        send(DeviceRoute.APPROVAL_READ, device, approvalId, null, DeviceApproval.serializer())

    suspend fun answer(device: DeviceCredential, approvalId: String, answer: ApprovalAnswer): ApprovalAnswerResult =
        send(DeviceRoute.APPROVAL_ANSWER, device, approvalId, brainWire(ApprovalAnswer.serializer(), answer).toString(),
            ApprovalAnswerResult.serializer())

    private suspend fun <T> send(route: DeviceRoute, device: DeviceCredential, approvalId: String?, body: String?, serializer: KSerializer<T>): T {
        val headers = buildMap {
            put("Accept", "application/json")
            put(HovelContract.HEADER, HovelContract.CURRENT.toString())
            put(DeviceRoute.TOKEN_HEADER, device.token)
            if (body != null) put("Content-Type", "application/json")
        }
        val url = base + route.path(device.ownerId, device.id, approvalId)
        val response = http.send(DeviceRequest(route.method, url, headers, body))
        when (response.status) {
            in 200..299 -> {}
            401 -> throw DeviceUnauthorizedException()
            403 -> throw DeviceNotPairedException()
            404 -> throw DeviceNotFoundException()
            409 -> throw DeviceRefusedException()
            else -> throw DeviceCallException(response.status)
        }
        val data: JsonElement = brainJson.parseToJsonElement(response.body).jsonObject.getValue("data")
        return brainJson.decodeFromJsonElement(serializer, data)
    }
}
