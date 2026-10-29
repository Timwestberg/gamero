package org.formulagen.wrapper.conversations

import kotlin.test.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.ProviderUsageWindow
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.hovel.NativeRoute
import org.formulagen.wrapper.hovel.hovelHostFeatures

/**
 * Parity with server-platform: every payload in [CompanionContractFixtures] was built from the
 * server's samples and accepted by its zod schema. The Mac reads each one with its own type and
 * writes it back unchanged, so what it reads and what it sends are the contract's shapes.
 */
class CompanionContractFixtureTest {
    private val fixtures = CompanionContractFixtures.PAYLOADS.mapValues { Json.parseToJsonElement(it.value).jsonObject }

    private val types: Map<String, KSerializer<*>> = mapOf(
        "claimResponse" to ClaimResponse.serializer(),
        "claimRequest" to ClaimRequest.serializer(),
        "messageCheckpointRequest" to MessageCheckpointRequest.serializer(),
        "replyDeltaRequest" to ReplyDeltaRequest.serializer(),
        "messageProgress" to MessageProgress.serializer(),
        "agentApproval" to AgentApproval.serializer(),
        "hostStreamEvent" to HostStreamEvent.serializer(),
        "projectFoldersSave" to ProjectFoldersSave.serializer(),
        "companionProject" to CompanionProject.serializer(),
        "artifactTextPage" to ArtifactTextPage.serializer(),
        "heartbeatInput" to HostHeartbeat.serializer(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun <T> roundTrip(serializer: KSerializer<T>, value: JsonElement): JsonElement = wire(serializer, fromWire(serializer, value))

    @Test fun everyPayloadReadsAndWritesBackUnchanged() {
        val checked = mutableListOf<String>()
        for ((name, fixture) in fixtures) {
            val schema = fixture.getValue("schema").jsonPrimitive.content
            val serializer = types[schema] ?: continue
            val value = fixture.getValue("value")
            assertEquals(numbers(value), numbers(roundTrip(serializer, value)), name)
            checked += name
        }
        assertEquals(fixtures.keys - "usage" - "routes.native", checked.toSet(), "every payload has a Mac type")
    }

    @Test fun everyConversationRouteIsOneTheServerServes() {
        fun spelled(method: String, path: String) = "$method ${path.removePrefix("/").replace(Regex(":[A-Za-z]+"), ":id")}"
        val served = fixtures.getValue("routes.native").getValue("value").jsonObject.mapValues { (_, route) ->
            spelled(route.jsonObject.getValue("method").jsonPrimitive.content, route.jsonObject.getValue("path").jsonPrimitive.content)
        }
        // Tasks and memory jobs are hovel-service's own native routes, outside the companion table.
        val outside = setOf(NativeRoute.TASK_CLAIM, NativeRoute.TASK_PROGRESS, NativeRoute.MEMORY_CLAIM, NativeRoute.MEMORY_PROGRESS,
            NativeRoute.MEMORY_CONSOLIDATE)
        val mac = (NativeRoute.entries - outside).map { spelled(it.method, it.template) }.toSet()
        // The Mac does not send heard voice turns yet.
        assertEquals(served.filterKeys { it != "messageSend" }.values.toSet(), mac)
        assertFalse(served.values.any { it.endsWith("/answer") }, "an answer travels only as approval.closed")
    }

    @Test fun theServerSendsWhatTheRunnerActsOn() {
        val claim = fromWire(ClaimResponse.serializer(), fixtures.getValue("claim.thread").getValue("value")) as ClaimResponse.Claimed
        assertEquals(claim.claim.thread?.id, claim.claim.message.threadId)
        assertEquals("Prefers short answers.", claim.claim.recalledMemory)
        assertEquals(MessageState.CLAIMED, claim.claim.message.state)
        assertEquals(ClaimResponse.MacUpdateRequired(2), fromWire(ClaimResponse.serializer(), fixtures.getValue("claim.macUpdateRequired").getValue("value")))
        val answered = fromWire(AgentApproval.serializer(), fixtures.getValue("approval.answeredByPhone").getValue("value"))
        assertEquals(ApprovalSurface.PHONE, answered.answer?.by)
        val events = fixtures.filterKeys { it.startsWith("hostEvent.") }.values.map { HostEvents.decode(it.getValue("value").toString()) }
        assertEquals(5, events.filterNotNull().size)
        val heartbeat = fromWire(HostHeartbeat.serializer(), fixtures.getValue("heartbeat").getValue("value"))
        assertEquals("ready", heartbeat.providerState)
        assertEquals(hovelHostFeatures(tasks = true, memory = true), heartbeat.features)
        val first = fromWire(ArtifactTextPage.serializer(), fixtures.getValue("artifactText.page").getValue("value"))
        val last = fromWire(ArtifactTextPage.serializer(), fixtures.getValue("artifactText.last").getValue("value"))
        assertEquals(first.text.length, first.nextCursor)
        assertNull(last.nextCursor)
    }

    @Test fun usageIsTheAllowanceSnapshotTheServerAccepts() {
        val fixture = fixtures.getValue("usage").getValue("value").jsonObject
        val usage = ProviderUsage(RuntimeId.CLAUDE_CODE, 1_000, ProviderUsageWindow(12.5, 1_800_000_000), ProviderUsageWindow(40.0))
        assertEquals(numbers(fixture.getValue("snapshot")), numbers(allowanceSnapshot(usage, fixture.getValue("computerId").jsonPrimitive.content)))
    }

    /** [element] with every number as a double, since `40` and `40.0` are one JSON number. */
    private fun numbers(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { numbers(it.value) })
        is JsonArray -> JsonArray(element.map(::numbers))
        is JsonPrimitive -> if (!element.isString && element.doubleOrNull != null && element !is JsonNull) JsonPrimitive(element.double) else element
    }
}
