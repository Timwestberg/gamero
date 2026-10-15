package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Checks a host's router against its column of `HOST_BRIDGE_PARITY`: it must
 * know exactly the methods and events the table names, and support exactly
 * the ones marked `supported`. Each platform factory's test calls this.
 *
 * [pending] names entries the table marks `supported` that this platform has
 * not shipped yet. An entry that is no longer pending fails the check, so the
 * list cannot go stale.
 */
internal fun assertMatchesParity(platform: HostPlatform, router: BridgeRouter, pending: Set<String> = emptySet()) {
    val column = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
        .getValue(platform.name.lowercase()).jsonObject
        .mapValues { it.value.jsonPrimitive.content == "supported" }
    val known = (router.knownMethods + router.knownEvents).toSet()
    val supported = (router.supportedMethods + router.supportedEvents).toSet()

    assertEquals(column.keys, known, "$platform knows other methods or events than the parity table")
    for ((name, inTable) in column) {
        if (name in pending) {
            assertTrue(inTable, "$platform: $name is pending but the parity table does not mark it supported")
            assertTrue(name !in supported, "$platform: $name is supported now; remove it from pending")
        } else {
            assertEquals(inTable, name in supported, "$platform: $name support differs from the parity table")
        }
    }
}
