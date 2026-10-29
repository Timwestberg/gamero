package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** The browser column. Hearing runs on device. Chat and providers stay on the Mac host. */
class WebParityTest {
    @Test fun webSupportsHearingAndDoesNotHostTheChatProviders() {
        val parity = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
        val desktop = parity.getValue("desktop").jsonObject
        val web = parity.getValue("web").jsonObject
        assertEquals(desktop.keys, web.keys)
        assertEquals("supported", web.getValue("chat.hearing").jsonPrimitive.content)
        assertEquals("unsupported", web.getValue("chat.send").jsonPrimitive.content)
        assertEquals("unsupported", web.getValue("chat.open").jsonPrimitive.content)
        assertEquals("unsupported", web.getValue("providers.usage").jsonPrimitive.content)
        assertEquals("unsupported", web.getValue("providers.status").jsonPrimitive.content)
    }
}
