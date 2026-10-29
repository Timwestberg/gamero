package org.formulagen.wrapper.conversations

import kotlin.test.*
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.ProviderUsageWindow
import org.formulagen.wrapper.bridge.RuntimeId

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
        "approvalAnswer" to ApprovalAnswer.serializer(),
        "hostStreamEvent" to HostStreamEvent.serializer(),
        "projectFoldersSave" to ProjectFoldersSave.serializer(),
        "messageSend" to MessageSend.serializer(),
        "computerHeartbeat+features" to HostHeartbeat.serializer(),
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
        assertEquals(fixtures.keys - "usage", checked.toSet(), "every payload has a Mac type")
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
