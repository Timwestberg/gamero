package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.*
import org.formulagen.wrapper.bridge.ChatWaiting
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.WorkScope

class GrokPermissionsTest {
    private val session = "provider/session"
    private val work = WorkScope(Path.of("/scratch/project"), "lease", "Ember", TeamMember(RuntimeId.GROK_BUILD, "grok-model"))

    @Test fun onlyExactOneTimeOptionIdsCanBeReturned() {
        val permission = assertNotNull(GrokPermission.parse(permissionRequest(), session))

        assertEquals(selected("opaque:yes/17"), permission.answer(ApprovalDecision.ALLOW_ONCE))
        assertEquals(selected("opaque:no/29"), permission.answer(ApprovalDecision.DENY))
        assertEquals(selected("opaque:no/29"), permission.answer(ApprovalDecision.ALLOW_FOR_CONVERSATION))
    }

    @Test fun labelsAndOptionOrderingCannotWidenAnApproval() {
        val options = JsonArray(listOf(
            option("forever", "allow_always", "Allow once"),
            option("deny", "reject_once", "Allow everything"),
            option("once", "allow_once", "Deny")))
        val permission = assertNotNull(GrokPermission.parse(permissionRequest(options = options), session))

        assertEquals(selected("once"), permission.answer(ApprovalDecision.ALLOW_ONCE))
        assertEquals(selected("deny"), permission.answer(ApprovalDecision.ALLOW_FOR_CONVERSATION))
    }

    @Test fun missingOneTimeOptionsAndAmbiguousIdsAreUnavailable() {
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(listOf(option("forever", "allow_always"), option("never", "reject_always")))), session))
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(listOf(option("one", "allow_once"), option("two", "allow_once"), option("no", "reject_once")))), session))
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(listOf(option("same", "allow_once"), option("same", "reject_once")))), session))
    }

    @Test fun foreignSessionAndMalformedToolRequestsAreNotShown() {
        val request = permissionRequest()
        assertNull(GrokPermission.parse(request, "other/session"))
        assertNull(GrokPermission.parse(JsonObject(request + ("sessionId" to JsonPrimitive(123))), session))
        assertNull(GrokPermission.parse(JsonObject(request + ("toolCall" to buildJsonObject { put("title", "Looks safe") })), session))
        assertNull(GrokPermission.parse(JsonObject(request + ("options" to JsonPrimitive("allow"))), session))
    }

    @Test fun oversizedAndControlContainingChoicesAreRejected() {
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(List(17) { option("id-$it", "future") })), session))
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(listOf(option("x".repeat(257), "allow_once"), option("no", "reject_once")))), session))
        assertNull(GrokPermission.parse(permissionRequest(options = JsonArray(listOf(option("yes", "allow_once", "Allow\nsecret"), option("no", "reject_once")))), session))
    }

    @Test fun cancellationNeverSelectsAnyOption() {
        assertEquals(Json.parseToJsonElement("""{"outcome":{"outcome":"cancelled"}}"""), cancelledGrokPermission())
    }

    @Test fun structuredSubjectUsesNativeBrokerWithoutConversationGrant() {
        val result = CompletableFuture<JsonObject>()
        val observed = CompletableFuture<ApprovalRequest>()
        val events = RecordingEvents()
        val broker = ApprovalBroker { request, _, shown ->
            observed.complete(request); shown(); ApprovalDecision.ALLOW_ONCE
        }
        GrokApprovals(session, work, events, broker, ::describeCommand).use { approvals ->
            approvals.receive(permissionRequest(), result::complete)

            assertEquals(selected("opaque:yes/17"), result.get(2, TimeUnit.SECONDS))
            val request = observed.get(2, TimeUnit.SECONDS)
            assertEquals(ApprovalSubject.Command("echo hello", work.folder, null, null), request.subject)
            assertNull(request.sessionLabel)
            assertEquals(work.member, request.member)
            assertEquals(2, events.waitingCalls.get())
        }
    }

    @Test fun missingStructuredDetailsDenyWithoutCallingTheBroker() {
        val calls = AtomicInteger()
        val result = CompletableFuture<JsonObject>()
        val broker = ApprovalBroker { _, _, _ -> calls.incrementAndGet(); ApprovalDecision.ALLOW_ONCE }
        GrokApprovals(session, work, RecordingEvents(), broker, { null }).use { approvals ->
            approvals.receive(permissionRequest(), result::complete)

            assertEquals(selected("opaque:no/29"), result.get(2, TimeUnit.SECONDS))
            assertEquals(0, calls.get())
        }
    }

    @Test fun brokerFailureIsDenied() {
        val result = CompletableFuture<JsonObject>()
        val broker = ApprovalBroker { _, _, _ -> error("native dialog unavailable") }
        GrokApprovals(session, work, RecordingEvents(), broker, ::describeCommand).use { approvals ->
            approvals.receive(permissionRequest(), result::complete)

            assertEquals(selected("opaque:no/29"), result.get(2, TimeUnit.SECONDS))
        }
    }

    @Test fun stopCancelsVisibleQueuedAndLaterPermissions() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val asked = AtomicInteger()
        val broker = ApprovalBroker { _, _, _ ->
            asked.incrementAndGet(); entered.countDown()
            released.await(2, TimeUnit.SECONDS)
            ApprovalDecision.ALLOW_ONCE
        }
        val first = CompletableFuture<JsonObject>()
        val queued = CompletableFuture<JsonObject>()
        val later = CompletableFuture<JsonObject>()
        val approvals = GrokApprovals(session, work, RecordingEvents(), broker, ::describeCommand)
        try {
            approvals.receive(permissionRequest("first"), first::complete)
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            approvals.receive(permissionRequest("queued"), queued::complete)

            approvals.close()
            approvals.receive(permissionRequest("later"), later::complete)

            assertEquals(cancelledGrokPermission(), first.get(2, TimeUnit.SECONDS))
            assertEquals(cancelledGrokPermission(), queued.get(2, TimeUnit.SECONDS))
            assertEquals(cancelledGrokPermission(), later.get(2, TimeUnit.SECONDS))
            assertEquals(1, asked.get())
        } finally { released.countDown(); approvals.close() }
    }

    @Test fun duplicateToolRequestDoesNotReplaceTheVisibleApproval() {
        val entered = CountDownLatch(1)
        val released = CountDownLatch(1)
        val broker = ApprovalBroker { _, _, _ -> entered.countDown(); released.await(2, TimeUnit.SECONDS); ApprovalDecision.ALLOW_ONCE }
        val first = CompletableFuture<JsonObject>()
        val duplicate = CompletableFuture<JsonObject>()
        GrokApprovals(session, work, RecordingEvents(), broker, ::describeCommand).use { approvals ->
            try {
                approvals.receive(permissionRequest(), first::complete)
                assertTrue(entered.await(2, TimeUnit.SECONDS))

                approvals.receive(permissionRequest(), duplicate::complete)
                released.countDown()

                assertEquals(cancelledGrokPermission(), duplicate.get(2, TimeUnit.SECONDS))
                assertEquals(selected("opaque:yes/17"), first.get(2, TimeUnit.SECONDS))
            } finally { released.countDown() }
        }
    }

    /** Test-only decoder; production must verify Grok's actual tool input before supplying one. */
    private fun describeCommand(tool: JsonObject): ApprovalSubject? {
        val input = tool["rawInput"] as? JsonObject ?: return null
        val command = input["command"] as? JsonPrimitive ?: return null
        return ApprovalSubject.Command(command.content, work.folder, null, null)
    }

    private fun permissionRequest(toolId: String = "tool-1", options: JsonArray = JsonArray(listOf(
        option("opaque:yes/17", "allow_once"), option("opaque:no/29", "reject_once"), option("persistent", "allow_always")))) = buildJsonObject {
        put("sessionId", session)
        putJsonObject("toolCall") {
            put("toolCallId", toolId); put("title", "Do something else")
            putJsonObject("rawInput") { put("command", "echo hello") }
        }
        put("options", options)
    }

    private fun option(id: String, kind: String, name: String = "Choice") = buildJsonObject {
        put("optionId", id); put("kind", kind); put("name", name)
    }

    private fun selected(id: String) = buildJsonObject {
        putJsonObject("outcome") { put("outcome", "selected"); put("optionId", id) }
    }

    private class RecordingEvents : TurnEvents {
        val waitingCalls = AtomicInteger()
        override fun opened(conversation: String) = Unit
        override fun accepted() = Unit
        override fun partial(text: String) = Unit
        override fun completed(text: String, conversation: String) = Unit
        override fun failed(failure: ChatFailure) = Unit
        override fun waiting(waiting: ChatWaiting?) { waitingCalls.incrementAndGet() }
    }
}
