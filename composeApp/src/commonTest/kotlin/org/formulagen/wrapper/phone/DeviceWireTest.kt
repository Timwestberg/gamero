package org.formulagen.wrapper.phone

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.bridge.ApprovalDeviceActivated
import org.formulagen.wrapper.bridge.ApprovalDeviceRef
import org.formulagen.wrapper.bridge.ApprovalDeviceRegistration
import org.formulagen.wrapper.bridge.ApprovalDeviceStatus
import org.formulagen.wrapper.bridge.ApprovalPresentOutcome
import org.formulagen.wrapper.bridge.ApprovalPresentResult
import org.formulagen.wrapper.bridge.HostBridgeFixtures
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.bridge.brainWire
import org.formulagen.wrapper.bridge.bridgeJson
import org.formulagen.wrapper.conversations.ApprovalState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The phone's types against the server's own payloads (`DeviceContractFixtures`) and the host-bridge envelope
 * fixtures: each reads and writes back unchanged, and the phone's route table is the one the server serves.
 */
class DeviceWireTest {
    private fun payload(name: String): JsonElement =
        Json.parseToJsonElement(DeviceContractFixtures.PAYLOADS.getValue(name)).jsonObject.getValue("value")

    private fun <T> roundTrip(serializer: KSerializer<T>, value: JsonElement): JsonElement =
        brainWire(serializer, brainJson.decodeFromJsonElement(serializer, value))

    @Test fun everyDevicePayloadReadsAndWritesBackUnchanged() {
        val serializers: Map<String, KSerializer<*>> = mapOf(
            "approvalDeviceRegistration" to ApprovalDeviceRegistration.serializer(),
            "deviceApproval" to DeviceApproval.serializer(),
            "deviceApprovalList" to DeviceApprovalList.serializer(),
            "approvalAnswer" to ApprovalAnswer.serializer(),
            "approvalAnswerResult" to ApprovalAnswerResult.serializer(),
        )
        val payloads = DeviceContractFixtures.PAYLOADS.keys.filter { it.startsWith("device.") }
        assertTrue(payloads.size >= 8)
        for (name in payloads) {
            val schema = Json.parseToJsonElement(DeviceContractFixtures.PAYLOADS.getValue(name)).jsonObject.getValue("schema").jsonPrimitive.content
            @Suppress("UNCHECKED_CAST")
            val serializer = serializers.getValue(schema) as KSerializer<Any?>
            assertEquals(payload(name), roundTrip(serializer, payload(name)), name)
        }
        val lost = brainJson.decodeFromJsonElement(ApprovalAnswerResult.serializer(), payload("device.lostExpired"))
        assertEquals(ApprovalAnswerStatus.LOST to ApprovalState.EXPIRED, lost.status to lost.approval.state)
    }

    @Test fun anUnknownKeyIsRefusedRatherThanShown() {
        val shown = payload("device.approval").jsonObject
        val extra = Json.parseToJsonElement(shown.toString().replaceFirst("{", "{\"answerHere\":true,"))
        assertFails { brainJson.decodeFromJsonElement(DeviceApproval.serializer(), extra) }
    }

    @Test fun thePhoneCallsExactlyTheDeviceRoutesTheServerServes() {
        val table = payload("routes.device").jsonObject
        val served = table.values.map { route -> route.jsonObject.let { it.getValue("method").jsonPrimitive.content to it.getValue("path").jsonPrimitive.content } }
        assertEquals(served.toSet(), DeviceRoute.entries.map { it.method to it.template }.toSet())
        val base = payload("routes.deviceBase").jsonObject
        assertEquals(base.getValue("base").jsonPrimitive.content, DeviceRoute.DEVICE_BASE)
        assertEquals(base.getValue("tokenHeader").jsonPrimitive.content, DeviceRoute.TOKEN_HEADER)
        val owner = "7fa0000b-0000-4000-8000-000000000001"
        val device = "79a0000b-0000-4000-8000-000000000001"
        val approval = "72a0000b-0000-4000-8000-000000000001"
        assertEquals("/v1/hovel/devices/$owner/$device/approvals/$approval/answer", DeviceRoute.APPROVAL_ANSWER.path(owner, device, approval))
        assertFails { DeviceRoute.APPROVAL_READ.path(owner, device) }
        assertFails { DeviceRoute.APPROVAL_LIST.path(owner, device, approval) }
        assertFails { DeviceRoute.APPROVAL_READ.path(owner, device, approval.uppercase()) }
        assertFails { DeviceRoute.APPROVAL_LIST.path(owner.uppercase(), device) }
    }

    @Test fun theBridgeResultsAreTheEnvelopeFixtures() {
        fun result(name: String) =
            Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject.getValue("value").jsonObject.getValue("result")
        assertEquals(result("success-approvals-present-answered.json"),
            bridgeJson.encodeToJsonElement(ApprovalPresentResult.serializer(), ApprovalPresentResult(ApprovalPresentOutcome.ANSWERED)))
        assertEquals(result("success-approvals-present-mac-only.json"),
            bridgeJson.encodeToJsonElement(ApprovalPresentResult.serializer(), ApprovalPresentResult(ApprovalPresentOutcome.MAC_ONLY)))
        val registration = bridgeJson.decodeFromJsonElement(ApprovalDeviceRegistration.serializer(), result("success-approval-device-prepare.json"))
        assertEquals(result("success-approval-device-prepare.json"), bridgeJson.encodeToJsonElement(ApprovalDeviceRegistration.serializer(), registration))
        assertEquals(result("success-approval-device-activate.json"),
            bridgeJson.encodeToJsonElement(ApprovalDeviceActivated.serializer(), ApprovalDeviceActivated("79a0000b-0000-4000-8000-000000000001")))
        assertEquals(result("success-approval-device-status.json"), bridgeJson.encodeToJsonElement(ApprovalDeviceStatus.serializer(),
            ApprovalDeviceStatus(ApprovalDeviceRef("79a0000b-0000-4000-8000-000000000001", "7fa0000b-0000-4000-8000-000000000001"))))
        assertEquals(result("success-approval-device-status-none.json"),
            bridgeJson.encodeToJsonElement(ApprovalDeviceStatus.serializer(), ApprovalDeviceStatus(null as ApprovalDeviceRef?)))
    }

    @Test fun aDeviceNameIsOneSafeLine() {
        assertEquals("iPhone", deviceName("  iPhone\n", "Phone"))
        assertEquals("Pixel 9", deviceName("Pixel‮ 9", "Phone"))
        assertEquals("Phone", deviceName(" \u0000 ", "Phone"))
        assertEquals("Phone", deviceName(null, "Phone"))
        assertEquals(DEVICE_NAME_LIMIT, deviceName("x".repeat(200), "Phone").length)
    }

    @Test fun aCredentialReadsOnlyInTheFormTheServerChecks() {
        val good = DeviceCredential("79a0000b-0000-4000-8000-000000000001", "7fa0000b-0000-4000-8000-000000000001", "iPhone", "ios", "e".repeat(64))
        assertEquals(good, DeviceCredential.read(DeviceCredential.write(good)))
        assertNull(DeviceCredential.read(DeviceCredential.write(good.copy(token = "E".repeat(64)))))
        assertNull(DeviceCredential.read(DeviceCredential.write(good.copy(platform = "macos"))))
        assertNull(DeviceCredential.read(DeviceCredential.write(good.copy(id = good.id.uppercase()))))
        assertNull(DeviceCredential.read("not json"))
        assertNull(DeviceCredential.read(null))
    }
}
