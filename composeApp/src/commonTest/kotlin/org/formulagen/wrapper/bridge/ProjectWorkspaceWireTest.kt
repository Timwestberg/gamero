package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The project workspace methods as native code declares them equal event-contracts `project-workspace.ts`: their
 * names and capability, arguments that are record ids and nothing else, and the three statuses of the envelope
 * fixtures. The router fixtures run them through the router in [BridgeRouterTest].
 */
class ProjectWorkspaceWireTest {
    private val project = "70a0000b-0000-4000-8000-000000000001"
    private val thread = "78a0000b-0000-4000-8000-000000000001"

    private fun envelope(name: String): JsonObject = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue(name)).jsonObject
    private fun args(text: String) = Json.parseToJsonElement(text).jsonObject

    @Test fun theMethodsAreTheirOwnCapability() {
        assertEquals("projectWorkspace", ProjectWorkspaceMethods.binder.name)
        assertEquals(listOf("projectWorkspace.open", "projectWorkspace.viewChanges"), ProjectWorkspaceMethods.binder.handlers.map { it.method.name })
        val parity = Json.parseToJsonElement(HostBridgeFixtures.PARITY).jsonObject
        for (platform in HostPlatform.entries) {
            val column = parity.getValue(platform.wireName).jsonObject
            for (method in ProjectWorkspaceMethods.binder.handlers.map { it.method.name }) {
                val expected = if (platform == HostPlatform.DESKTOP) "supported" else "unsupported"
                assertEquals(expected, column.getValue(method).jsonPrimitive.content, "$platform $method")
            }
        }
    }

    @Test fun argumentsAreRecordIdsAlone() {
        assertEquals(ProjectScope(project), ProjectWorkspaceMethods.OPEN.decodeArgs(args("""{"projectId":"$project"}""")))
        assertEquals(
            ProjectWorkspaceViewChangesArgs(project, thread),
            ProjectWorkspaceMethods.VIEW_CHANGES.decodeArgs(args("""{"projectId":"$project","threadId":"$thread"}""")),
        )
        for (refused in listOf(
            """{"projectId":"${project.uppercase()}"}""",
            """{"projectId":"$project","path":"/Users/person/hovel-notes"}""",
            """{"projectId":"$project","command":"nvim"}""",
        )) assertNull(ProjectWorkspaceMethods.OPEN.decodeArgs(args(refused)), refused)
        for (refused in listOf(
            """{"projectId":"$project"}""",
            """{"projectId":"$project","threadId":"${thread.uppercase()}"}""",
            """{"projectId":"$project","threadId":"../other"}""",
            """{"projectId":"$project","threadId":"$thread","files":["src/main.ts"]}""",
        )) assertNull(ProjectWorkspaceMethods.VIEW_CHANGES.decodeArgs(args(refused)), refused)
    }

    @Test fun resultsRoundTripTheEnvelopeFixtures() {
        val statuses = mutableSetOf<ProjectWorkspaceStatus>()
        for (name in HostBridgeFixtures.ENVELOPES.keys.filter { it.startsWith("success-project-workspace-") }) {
            val fixture = envelope(name)
            assertTrue(fixture.getValue("method").jsonPrimitive.content.startsWith("projectWorkspace."), name)
            val result: JsonElement = fixture.getValue("value").jsonObject.getValue("result")
            val decoded = bridgeJson.decodeFromJsonElement(ProjectWorkspaceResult.serializer(), result)
            assertEquals(result, bridgeJson.encodeToJsonElement(ProjectWorkspaceResult.serializer(), decoded), name)
            statuses += decoded.status
        }
        assertEquals(ProjectWorkspaceStatus.entries.toSet(), statuses, "a fixture for every status")
    }

    @Test fun aResultIsOneOfTheThreeStatuses() {
        val missing = ProjectWorkspaceResult(ProjectWorkspaceStatus.MISSING_FOLDER)
        assertEquals("""{"status":"missing-folder"}""", bridgeJson.encodeToString(ProjectWorkspaceResult.serializer(), missing))
        assertFails { bridgeJson.decodeFromString(ProjectWorkspaceResult.serializer(), """{"status":"busy"}""") }
        assertFails { bridgeJson.decodeFromString(ProjectWorkspaceResult.serializer(), """{"status":"opened","path":"/Users/p"}""") }
    }
}
