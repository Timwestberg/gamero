package org.formulagen.wrapper.workspace

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.formulagen.wrapper.bridge.HostBridgeFixtures
import org.formulagen.wrapper.conversations.fromWire
import org.formulagen.wrapper.conversations.wire

/**
 * Parity with server-platform's workspace profile fixtures. The server accepts or refuses each one against its own
 * catalog; this Mac reads every one leniently and writes it back unchanged, so tool, tab and limit ids it does not
 * know, and values outside its ranges, survive it. Only a key the contract does not name is refused.
 */
class WorkspaceProfileWireTest {
    private val fixtures = WorkspaceProfileFixtures.FILES.mapValues { Json.parseToJsonElement(it.value).jsonObject }
    private val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject

    private val types: Map<String, KSerializer<*>> = mapOf(
        "workspaceProfile" to WireWorkspaceProfile.serializer(),
        "workspaceProfileDocument" to WireWorkspaceProfileDocument.serializer(),
        "workspaceProfileSave" to WireWorkspaceProfileSave.serializer(),
    )

    /** The fixtures whose keys the contract does not name; every other one reads. */
    private val refused = setOf("profile-extra-key.json", "profile-tool-path.json", "document-extra-key.json")

    private fun value(name: String): JsonElement = fixtures.getValue(name).getValue("value")
    private fun profile(name: String) = fromWire(WireWorkspaceProfile.serializer(), value(name))

    @Suppress("UNCHECKED_CAST")
    private fun roundTrip(serializer: KSerializer<*>, element: JsonElement): JsonElement {
        val typed = serializer as KSerializer<Any?>
        return wire(typed, fromWire(typed, element))
    }

    @Test fun everyFixtureReadsAndWritesBackUnchangedUnlessItNamesAnUnknownKey() {
        assertTrue(fixtures.keys.containsAll(refused), "the refused fixtures exist")
        for ((name, fixture) in fixtures) {
            val serializer = types.getValue(fixture.getValue("schema").jsonPrimitive.content)
            if (name in refused) {
                assertFails(name) { roundTrip(serializer, fixture.getValue("value")) }
            } else {
                assertEquals(fixture.getValue("value"), roundTrip(serializer, fixture.getValue("value")), name)
            }
        }
        val keptHere = fixtures.filter { (name, fixture) -> name !in refused && !fixture.getValue("valid").jsonPrimitive.boolean }
        assertTrue(keptHere.isNotEmpty(), "some profiles the server refuses still read here")
    }

    @Test fun idsThisMacDoesNotKnowAreKeptWithTheirValues() {
        val tools = limits.getValue("WORKSPACE_TOOLS").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val tabs = limits.getValue("WORKSPACE_TABS").jsonArray.map { it.jsonPrimitive.content }.toSet()
        val settings = limits.getValue("WORKSPACE_LIMITS").jsonObject.keys - "tabs"

        val tool = profile("profile-unknown-tool.json")
        assertEquals(setOf("helix", "neovim"), tool.tools.keys)
        assertFalse("helix" in tools)
        assertEquals(WireToolChoice("hovel", "hovel"), tool.tools.getValue("helix"))

        val tab = profile("profile-unknown-tab.json")
        assertEquals(listOf("shell", "browser", "changes"), tab.tabs)
        assertFalse("browser" in tabs)

        val limit = profile("profile-unknown-limit.json")
        assertEquals(mapOf("notALimit" to 10_000, "threadFiles" to 500), limit.limits)
        assertFalse("notALimit" in settings)

        // a choice value this Mac does not know stays the string it came as, so the wire types never refuse it
        val choice = value("profile-unknown-choice.json")
        assertEquals(WireToolChoice("system", "mine"), profile("profile-unknown-choice.json").tools.getValue("neovim"))
        assertEquals(choice, roundTrip(WireWorkspaceProfile.serializer(), choice))

        // a value outside this Mac's range, and a repeated tab, are kept as they are; resolving them is separate
        assertEquals(mapOf("projectThreads" to 200, "threadFiles" to 49), profile("profile-limit-out-of-range.json").limits)
        assertEquals(listOf("shell", "changes", "files", "changes"), profile("profile-repeated-tab.json").tabs)
    }

    @Test fun theDefaultProfileAndItsDocumentAreTheServersDefault() {
        val default = WireWorkspaceProfile(emptyMap(), "starship", listOf("shell", "changes", "files", "editor"), emptyMap(), 1)
        assertEquals(default, profile("profile-default.json"))
        assertEquals(WireWorkspaceProfileDocument(default, 0), fromWire(WireWorkspaceProfileDocument.serializer(), value("document-default.json")))
        val save = fromWire(WireWorkspaceProfileSave.serializer(), value("save.json"))
        assertEquals(3L, save.expectedRevision)
        assertEquals(WireToolChoice("installed", "mine"), save.profile.tools.getValue("neovim"))
    }

    @Test fun aMissingLimitsReadsAsNoneAndTheMacAlwaysWritesThem() {
        val bare = Json.parseToJsonElement("""{"prompt":"shell","tabs":["shell"],"tools":{},"version":1}""")
        val read = fromWire(WireWorkspaceProfile.serializer(), bare)
        assertEquals(emptyMap(), read.limits)
        assertEquals(JsonObject(emptyMap()), wire(WireWorkspaceProfile.serializer(), read).getValue("limits"))
    }
}
