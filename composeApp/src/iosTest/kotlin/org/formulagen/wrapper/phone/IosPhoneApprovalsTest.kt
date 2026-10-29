package org.formulagen.wrapper.phone

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The iPhone's own pieces: CommonCrypto's SHA-256 is the server's, tokens and ids are fresh, and the Keychain keeps one item. */
class IosPhoneApprovalsTest {
    @Test
    fun theTokenHashIsTheSha256TheServerChecks() {
        // samples.test-support.ts: the phone's tokenHash is the SHA-256 of this text
        val registration = Json.parseToJsonElement(DeviceContractFixtures.PAYLOADS.getValue("device.registration"))
            .jsonObject.getValue("value").jsonObject
        assertEquals(registration.getValue("tokenHash").jsonPrimitive.content, IosDeviceCrypto.tokenHash("the phone keeps this token"))
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", IosDeviceCrypto.tokenHash(""))
    }

    @Test
    fun tokensAndIdsAreFreshAndInTheServersForm() {
        val tokens = List(4) { IosDeviceCrypto.newToken() }
        assertEquals(4, tokens.toSet().size)
        assertTrue(tokens.all { Regex("[0-9a-f]{64}").matches(it) })
        val id = IosDeviceCrypto.newId()
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(id), id)
        assertNotEquals(id, IosDeviceCrypto.newId())
    }

    @Test
    fun theKeychainKeepsOneItemAndForgetsIt() {
        val vault = IosDeviceVault(service = "org.formulagen.gamero.approval-device.test", account = "test")
        try {
            vault.clear()
        } catch (unavailable: IllegalStateException) {
            // a test binary without a keychain entitlement cannot reach the Keychain; the app can
            println("Keychain unavailable to this test binary: ${unavailable.message}")
            return
        }
        assertNull(vault.read())
        vault.write("first")
        vault.write("second")
        assertEquals("second", vault.read())
        vault.clear()
        assertNull(vault.read())
    }
}
