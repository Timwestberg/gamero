package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.conversations.ApprovalDevice
import org.formulagen.wrapper.conversations.ApprovalDeviceList
import org.formulagen.wrapper.conversations.ApprovalDevicePairing
import org.formulagen.wrapper.conversations.ApprovalDeviceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Pair a phone…" against a fake host transport with the server's rules: a code pairs once, a wrong one counts, and
 * after five wrong codes this Mac waits.
 */
class PhonePairingTest {
    private val computer = "75a0000b-0000-4000-8000-000000000001"
    private val phone = "79a0000b-0000-4000-8000-000000000001"

    /** The server's native device routes for one Mac, in memory. */
    private inner class FakeNative : NativeExchange {
        val calls = mutableListOf<Triple<String, String, JsonObject?>>()
        /** The code the phone shows, until it pairs. */
        var showing: String? = "042917"
        val paired = mutableListOf<String>()
        var wrong = 0
        var offline = false

        override fun exchange(method: String, route: String, body: JsonObject?): JsonObject {
            calls += Triple(method, route, body)
            if (offline) throw IllegalStateException("offline")
            assertTrue(NativeRoute.allows(method, route), "$method $route")
            return when {
                method == "GET" && route == "devices" -> data(ApprovalDeviceList.serializer(), ApprovalDeviceList(paired.map(::device)))
                method == "POST" && (route == "devices/pair" || route == "devices/lookup") -> {
                    if (wrong >= 5) throw HovelStatusException(429, "too-many-attempts")
                    val code = body!!.getValue("code").jsonPrimitive.content
                    if (code != showing) {
                        wrong++
                        throw HovelStatusException(404)
                    }
                    if (route == "devices/lookup") {
                        return data(ApprovalDevice.serializer(), device(phone).copy(pairedWith = emptyList(), state = ApprovalDeviceState.PENDING))
                    }
                    showing = null
                    paired += phone
                    data(ApprovalDevice.serializer(), device(phone))
                }
                method == "POST" && route.endsWith("/unpair") -> {
                    val id = route.split('/')[1]
                    if (id !in paired) throw HovelStatusException(404)
                    paired -= id
                    data(ApprovalDevice.serializer(), device(id).copy(pairedWith = emptyList(), state = ApprovalDeviceState.PENDING))
                }
                else -> throw HovelStatusException(404)
            }
        }

        private fun device(id: String) = ApprovalDevice(id, "iPhone\u2028Allow", listOf(ApprovalDevicePairing(computer, "Studio Mac",
            "2026-10-29T12:00:00.000Z")), null, "ios", "2026-10-29T11:56:00.000Z", null, ApprovalDeviceState.PAIRED)

        private fun <T> data(serializer: kotlinx.serialization.KSerializer<T>, value: T) =
            brainJson.encodeToJsonElement(serializer, value).jsonObject
    }

    private val now = java.time.Instant.parse("2026-10-29T12:00:00Z")

    @Test fun theMacAsksAboutThePhoneACodeNamesBeforeItPairs() {
        val native = FakeNative()
        val pairing = PhonePairing({ native }) { now }
        val found = pairing.lookup("042 917") as PhonePairing.Outcome.Found
        assertEquals(PhonePairing.Candidate(phone, "iPhoneAllow", "ios", 4), found.phone)
        assertEquals("Pair iPhoneAllow (iPhone, registered 4 min ago)?", pairingMessage(found))
        // looking up pairs nothing
        assertTrue(native.paired.isEmpty())
        assertEquals("042917", native.showing)
        assertEquals(listOf("devices/lookup"), native.calls.map { it.second })
        assertEquals("Pair Pixel (Android, registered just now)?", PhonePairing.Candidate(phone, "Pixel", "android", 0).question)
    }

    @Test fun theTypedCodePairsThePhoneOnThisMacsOwnRoute() {
        val native = FakeNative()
        val pairing = PhonePairing({ native }) { now }
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.phones())
        val done = pairing.pair(" 042-917 ") as PhonePairing.Outcome.Done
        // the name is cleaned to one line for the window
        val shown = PhonePairing.PairedPhone(phone, "iPhoneAllow")
        assertEquals(shown, done.paired)
        assertEquals(listOf(shown), done.phones)
        val sent = native.calls.single { it.second == "devices/pair" }
        assertEquals("POST", sent.first)
        assertEquals(JsonObject(mapOf("code" to kotlinx.serialization.json.JsonPrimitive("042917"))), sent.third)
        assertEquals("Paired with iPhoneAllow. It can now answer this Mac's approvals.", pairingMessage(done))
        // a code pairs once
        assertEquals(PhonePairing.Outcome.WrongCode, pairing.pair("042917"))
    }

    @Test fun aMalformedCodeIsNeverSent() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        for (typed in listOf("", "04291", "0429177", "04291a", "０４２９１７")) {
            assertEquals(PhonePairing.Outcome.Malformed, pairing.pair(typed), typed)
        }
        assertTrue(native.calls.isEmpty())
        assertEquals("042917", PhonePairing.codeOf("042 917"))
        assertNull(PhonePairing.codeOf("042_917"))
    }

    @Test fun wrongCodesCountAndThenThisMacWaits() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        repeat(5) { assertEquals(PhonePairing.Outcome.WrongCode, pairing.pair("111111")) }
        assertEquals(PhonePairing.Outcome.TooManyAttempts, pairing.pair("042917"))
        assertTrue(native.paired.isEmpty())
        assertEquals("Too many wrong codes on your Macs. Wait a few minutes, then show a new code on the phone.",
            pairingMessage(PhonePairing.Outcome.TooManyAttempts))
    }

    @Test fun unpairingLetsOnePhoneGo() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        pairing.pair("042917")
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.unpair(phone))
        assertEquals("devices/$phone/unpair", native.calls.single { it.second.endsWith("/unpair") }.second)
        // one already gone is let go alike
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.unpair(phone))
    }

    @Test fun aMacNotConnectedOrOfflineSaysSo() {
        assertEquals(PhonePairing.Outcome.NotConnected, PhonePairing({ null }).pair("042917"))
        val native = FakeNative().apply { offline = true }
        assertEquals(PhonePairing.Outcome.Failed, PhonePairing({ native }).pair("042917"))
        val revoked = NativeExchange { _, _, _ -> throw HovelStatusException(401) }
        assertEquals(PhonePairing.Outcome.NotConnected, PhonePairing({ revoked }).phones())
    }
}
