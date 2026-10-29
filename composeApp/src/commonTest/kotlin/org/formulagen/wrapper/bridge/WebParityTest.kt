package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The browser catalog page (web/catalog) hears on the device and leaves chat
 * and providers to the Mac host. server-platform's parity table has no web
 * column, and HostBridgeFixtures is generated from it, so the browser's side
 * is checked here: every method the page calls on the host is one desktop
 * supports.
 */
class WebParityTest {
    private val pageCallsOnTheHost = listOf("chat.hearing", "chat.send", "chat.stop")

    @Test fun theMacHostSupportsEveryMethodTheBrowserPageCalls() {
        val parity = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
        val desktop = parity.getValue("desktop").jsonObject
        for (method in pageCallsOnTheHost) {
            assertEquals("supported", desktop.getValue(method).jsonPrimitive.content, method)
        }
    }
}
