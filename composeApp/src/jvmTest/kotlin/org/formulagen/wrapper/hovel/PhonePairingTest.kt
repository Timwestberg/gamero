package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.conversations.ApprovalDevice
import org.formulagen.wrapper.conversations.ApprovalDeviceList
import org.formulagen.wrapper.conversations.ApprovalDevicePairing
import org.formulagen.wrapper.conversations.ApprovalDeviceState
import org.formulagen.wrapper.conversations.DevicePairLookup
import org.formulagen.wrapper.conversations.PairingRefusalCodes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.PhonePairingOutcome
import org.formulagen.wrapper.conversations.Screen
import org.formulagen.wrapper.conversations.eventually
import kotlin.test.Test
import kotlin.test.assertFailsWith
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
        /** When the code the phone shows runs out; a confirmation after it, of the right code, is refused uncounted. */
        var ranOut = false

        override fun exchange(method: String, route: String, body: JsonObject?): JsonObject {
            calls += Triple(method, route, body)
            if (offline) throw IllegalStateException("offline")
            assertTrue(NativeRoute.allows(method, route), "$method $route")
            return when {
                method == "GET" && route == "devices" -> data(ApprovalDeviceList.serializer(), ApprovalDeviceList(paired.map(::device)))
                method == "POST" && (route == "devices/pair" || route == "devices/lookup") -> {
                    if (wrong >= 5) throw HovelStatusException(429, PairingRefusalCodes.TOO_MANY_ATTEMPTS)
                    val code = body!!.getValue("code").jsonPrimitive.content
                    val named = body["deviceId"]?.jsonPrimitive?.content
                    if (route == "devices/pair" && named == null) throw HovelStatusException(400)
                    if (code == showing && named == phone && ranOut) throw HovelRejectedException(PairingRefusalCodes.EXPIRED)
                    if (code != showing || (named != null && named != phone)) {
                        wrong++
                        throw HovelStatusException(404)
                    }
                    if (route == "devices/lookup") {
                        val pending = device(phone).copy(pairedWith = emptyList(), state = ApprovalDeviceState.PENDING)
                        return data(DevicePairLookup.serializer(), DevicePairLookup("2026-10-29T12:10:00.000Z", pending))
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
    /** The phone the window asked about, as its lookup named it. */
    private val candidate = PhonePairing.Candidate(phone, "iPhone", "ios", 4, now.plusSeconds(600))

    @Test fun theMacAsksAboutThePhoneACodeNamesBeforeItPairs() {
        val native = FakeNative()
        val pairing = PhonePairing({ native }) { now }
        val found = pairing.lookup("042 917") as PhonePairing.Outcome.Found
        assertEquals(PhonePairing.Candidate(phone, "iPhoneAllow", "ios", 4, java.time.Instant.parse("2026-10-29T12:10:00Z")), found.phone)
        assertEquals("Pair iPhoneAllow (iPhone, registered 4 min ago)?", pairingMessage(found))
        // looking up pairs nothing
        assertTrue(native.paired.isEmpty())
        assertEquals("042917", native.showing)
        assertEquals(listOf("devices/lookup"), native.calls.map { it.second })
        assertEquals("Pair Pixel (Android, registered just now)?", PhonePairing.Candidate(phone, "Pixel", "android", 0, now).question)
    }

    @Test fun theTypedCodePairsThePhoneOnThisMacsOwnRoute() {
        val native = FakeNative()
        val pairing = PhonePairing({ native }) { now }
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.phones())
        val done = pairing.pair(" 042-917 ", candidate) as PhonePairing.Outcome.Done
        // the name is cleaned to one line for the window
        val shown = PhonePairing.PairedPhone(phone, "iPhoneAllow")
        assertEquals(shown, done.paired)
        assertEquals(listOf(shown), done.phones)
        val sent = native.calls.single { it.second == "devices/pair" }
        assertEquals("POST", sent.first)
        assertEquals(JsonObject(mapOf("code" to kotlinx.serialization.json.JsonPrimitive("042917"),
            "deviceId" to kotlinx.serialization.json.JsonPrimitive(phone))), sent.third)
        assertEquals("Paired with iPhoneAllow. It can now answer this Mac's approvals.", pairingMessage(done))
        // a code pairs once
        assertEquals(PhonePairing.Outcome.WrongCode, pairing.pair("042917", candidate))
    }

    @Test fun theConfirmationNamesThePhoneItAskedAboutAndALateOneIsNotAGuess() {
        val native = FakeNative()
        val pairing = PhonePairing({ native }) { now }
        // the right code never pairs another phone
        val other = candidate.copy(id = "79a0000b-0000-4000-8000-000000000002")
        assertEquals(PhonePairing.Outcome.WrongCode, pairing.pair("042917", other))
        assertEquals(1, native.wrong)
        // the asked-about phone's code ran out before the person confirmed: said so, not counted
        native.ranOut = true
        val late = pairing.pair("042917", candidate)
        assertEquals(PhonePairing.Outcome.Expired, late)
        assertEquals(1, native.wrong)
        assertEquals("That code ran out. Show a new code on the phone, then type it again.", pairingMessage(late))
        native.ranOut = false
        pairing.pair("042917", candidate)
        val sent = native.calls.last { it.second == "devices/pair" }.third
        assertEquals(phone, sent?.getValue("deviceId")?.jsonPrimitive?.content)
        assertEquals(listOf(phone), native.paired)
    }

    @Test fun aMalformedCodeIsNeverSent() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        for (typed in listOf("", "04291", "0429177", "04291a", "０４２９１７")) {
            assertEquals(PhonePairing.Outcome.Malformed, pairing.pair(typed, candidate), typed)
        }
        assertTrue(native.calls.isEmpty())
        assertEquals("042917", PhonePairing.codeOf("042 917"))
        assertNull(PhonePairing.codeOf("042_917"))
    }

    @Test fun wrongCodesCountAndThenThisMacWaits() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        repeat(5) { assertEquals(PhonePairing.Outcome.WrongCode, pairing.pair("111111", candidate)) }
        assertEquals(PhonePairing.Outcome.TooManyAttempts, pairing.pair("042917", candidate))
        assertTrue(native.paired.isEmpty())
        assertEquals("Too many wrong codes on your Macs. Wait a few minutes, then show a new code on the phone.",
            pairingMessage(PhonePairing.Outcome.TooManyAttempts))
    }

    @Test fun unpairingLetsOnePhoneGo() {
        val native = FakeNative()
        val pairing = PhonePairing({ native })
        pairing.pair("042917", candidate)
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.unpair(phone))
        assertEquals("devices/$phone/unpair", native.calls.single { it.second.endsWith("/unpair") }.second)
        // one already gone is let go alike
        assertEquals(PhonePairing.Outcome.Done(emptyList()), pairing.unpair(phone))
    }

    /** The Mac's side of "Add a phone": the page hands over [code], and [screen] is the Mac's own dialog. */
    private fun confirming(native: NativeExchange?, screen: Screen, clock: java.time.Instant = now) =
        DesktopPhonePairing(PhonePairing({ native }) { clock }, NativeDialogs(screen))

    @Test fun aCodeFromThePagePairsOnlyOnTheMacsOwnYes() = runBlocking {
        val native = FakeNative()
        val screen = Screen { 0 }
        assertEquals(PhonePairingOutcome.PAIRED, confirming(native, screen).confirm("042917").outcome)
        val dialog = screen.shown.single()
        assertEquals("Pair this phone with this Mac?", dialog.title)
        assertEquals("Pair iPhoneAllow (iPhone, registered 4 min ago)?", dialog.lines.first())
        // Not now is the default: closing the dialog, Escape or Enter never pairs
        assertEquals(listOf("Pair", "Not now"), dialog.buttons.map { it.first })
        assertEquals(false, dialog.dismiss)
        assertEquals(listOf("devices/lookup", "devices/pair", "devices"), native.calls.map { it.second })
        // the confirmation names the phone the dialog asked about
        assertEquals(phone, native.calls[1].third?.getValue("deviceId")?.jsonPrimitive?.content)
        assertEquals(listOf(phone), native.paired)
        assertEquals(listOf(dialog), screen.dismissed.toList())
    }

    @Test fun aNoOnTheMacPairsNothing() = runBlocking {
        val native = FakeNative()
        assertEquals(PhonePairingOutcome.DECLINED, confirming(native, Screen { 1 }).confirm("042917").outcome)
        assertEquals(listOf("devices/lookup"), native.calls.map { it.second })
        assertTrue(native.paired.isEmpty())
    }

    @Test fun theMacsQuestionGoesWhenTheCodeRunsOut() = runBlocking {
        val native = FakeNative()
        // nobody answers, and the code has a fifth of a second left
        val screen = Screen { null }
        val outcome = confirming(native, screen, java.time.Instant.parse("2026-10-29T12:09:59.800Z")).confirm("042917").outcome
        assertEquals(PhonePairingOutcome.EXPIRED, outcome)
        assertEquals(screen.shown.toList(), screen.dismissed.toList())
        assertTrue(native.paired.isEmpty())
        // a code that had run out when the Mac looked asks nothing
        val late = Screen { 0 }
        assertEquals(PhonePairingOutcome.EXPIRED, confirming(native, late, java.time.Instant.parse("2026-10-29T12:10:00Z")).confirm("042917").outcome)
        assertTrue(late.shown.isEmpty())
        // and one that ran out between the question and the yes says so
        native.ranOut = true
        assertEquals(PhonePairingOutcome.EXPIRED, confirming(native, Screen { 0 }).confirm("042917").outcome)
        assertTrue(native.paired.isEmpty())
    }

    @Test fun aCodeThatNamesNoPhoneOrAMacThatCannotAskShowsNoDialog() = runBlocking {
        val screen = Screen { 0 }
        val native = FakeNative()
        assertEquals(PhonePairingOutcome.EXPIRED, confirming(native, screen).confirm("111111").outcome)
        assertEquals(1, native.wrong)
        assertEquals(PhonePairingOutcome.FAILED, confirming(null, screen).confirm("042917").outcome)
        assertEquals(PhonePairingOutcome.FAILED, confirming(FakeNative().apply { offline = true }, screen).confirm("042917").outcome)
        assertEquals(PhonePairingOutcome.FAILED, confirming(FakeNative().apply { wrong = 5 }, screen).confirm("042917").outcome)
        assertTrue(screen.shown.isEmpty())
    }

    @Test fun oneQuestionAtATimeAndItGoesWithThePagesCall() = runBlocking {
        val native = FakeNative()
        val screen = Screen { null }
        val pairing = confirming(native, screen)
        val asked = async(Dispatchers.Default) { pairing.confirm("042917") }
        eventually { screen.shown.size == 1 }
        assertFailsWith<BridgeBusyException> { pairing.confirm("042917") }
        // the page gave its call up: the dialog is taken down, and nothing pairs
        asked.cancelAndJoin()
        eventually { screen.dismissed.size == 1 }
        assertTrue(native.paired.isEmpty())
        // the next question can ask
        screen.answer = { 0 }
        assertEquals(PhonePairingOutcome.PAIRED, pairing.confirm("042917").outcome)
    }

    @Test fun aMacNotConnectedOrOfflineSaysSo() {
        assertEquals(PhonePairing.Outcome.NotConnected, PhonePairing({ null }).pair("042917", candidate))
        val native = FakeNative().apply { offline = true }
        assertEquals(PhonePairing.Outcome.Failed, PhonePairing({ native }).pair("042917", candidate))
        val revoked = NativeExchange { _, _, _ -> throw HovelStatusException(401) }
        assertEquals(PhonePairing.Outcome.NotConnected, PhonePairing({ revoked }).phones())
    }
}
