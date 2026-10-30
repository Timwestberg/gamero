package org.formulagen.wrapper.conversations

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.hovel.HovelHttpTransport
import org.formulagen.wrapper.hovel.HovelRejectedException
import org.formulagen.wrapper.hovel.HovelStatusException
import org.formulagen.wrapper.hovel.NativeRoute

/** The Mac's companion payloads in the contract's exact form, and the routes they travel on. */
class ConversationWireTest {
    private val json = Json

    @Test fun checkpointsNameTheirKindAndLeaveOutAbsentFields() {
        val reply = MessageAppend(MessageAuthor.COORDINATOR, id(), null, "Hello", null)
        assertEquals(json.parseToJsonElement("""{"kind":"message.appended","message":{"author":"coordinator","id":"${reply.id}","text":"Hello","threadId":null}}"""),
            wire(MessageCheckpoint.serializer(), MessageCheckpoint.MessageAppended(reply)))
        val approval = id()
        assertEquals(json.parseToJsonElement("""{"approvalId":"$approval","kind":"approval.closed","outcome":{"kind":"withdrawn"}}"""),
            wire(MessageCheckpoint.serializer(), MessageCheckpoint.ApprovalClosed(approval, ApprovalOutcome.Withdrawn)))
        assertEquals(json.parseToJsonElement("""{"approvalId":"$approval","kind":"approval.closed","outcome":{"kind":"answered","option":"allow-once"}}"""),
            wire(MessageCheckpoint.serializer(), MessageCheckpoint.ApprovalClosed(approval, ApprovalOutcome.Answered(ApprovalOption.ALLOW_ONCE))))
        val artifact = ArtifactRef(id(), "application/octet-stream", "Attached file", "photo.png")
        assertFalse("text" in wire(MessageCheckpoint.serializer(), MessageCheckpoint.ArtifactStored(artifact)), "a binary artifact's bytes stay on the Mac")
    }

    @Test fun aThreadAndARunTravelAsTheContractShapesThem() {
        val role = BrainRoleSnapshot(id(), "Implementer", BrainBinding(RuntimeId.CODEX, "test-model"), "", MemberMode.WORK)
        val thread = ThreadState("Implementer · add sign-in", id(), 2, role, null, BrainAttemptState.RUNNING).wire(id(), id())
        val encoded = wire(ConversationThread.serializer(), thread)
        assertEquals(setOf("activity", "conversationId", "id", "outputs", "parentMessageId", "revision", "role", "state", "stepId", "title"), encoded.keys)
        assertEquals(JsonNull, encoded["stepId"], "a nullable field is always sent")
        assertEquals("running", encoded.getValue("state").jsonPrimitive.content)
        assertFalse("effort" in encoded.getValue("role").jsonObject.getValue("binding").jsonObject)
        assertEquals(thread, fromWire(ConversationThread.serializer(), encoded))
        val run = ConversationRun(4, null, 0, id(), 0, 1, emptyList(), BrainRunState.PLANNING, null)
        assertEquals(setOf("configurationRevision", "currentThreadId", "dispatchesUsed", "id", "returnsUsed", "revision", "skipped", "state", "workflowId"),
            wire(ConversationRun.serializer(), run).keys)
    }

    @Test fun aClaimResponseIsReadStrictly() {
        val talk = TestConversation()
        val claim = talk.claim(id(), talk.message("Hi"), brain("Implementer" to MemberMode.WORK), recalledMemory = "Likes tea")
        val encoded = wire(ClaimResponse.serializer(), ClaimResponse.Claimed(claim))
        assertEquals(ClaimResponse.Claimed(claim), fromWire(ClaimResponse.serializer(), encoded))
        assertEquals(ClaimResponse.Empty, fromWire(ClaimResponse.serializer(), json.parseToJsonElement("""{"status":"empty"}""")))
        assertEquals(ClaimResponse.MacUpdateRequired(2), fromWire(ClaimResponse.serializer(), json.parseToJsonElement("""{"status":"mac-update-required","minimumContract":2}""")))
        assertFails { fromWire(ClaimResponse.serializer(), json.parseToJsonElement("""{"status":"empty","extra":1}""")) }
        assertFails("a runtime this build does not know is never read as another") {
            fromWire(ClaimResponse.serializer(), json.parseToJsonElement(encoded.toString().replace("\"codex\"", "\"future-runtime\"")))
        }
        val stopping = ClaimResponse.StopRequested(talk.message("Hi").copy(revision = 3, state = MessageState.RUNNING))
        val stop = wire(ClaimResponse.serializer(), stopping)
        assertEquals(setOf("message", "status"), stop.keys)
        assertEquals("stop-requested", stop.getValue("status").jsonPrimitive.content)
        assertEquals(stopping, fromWire(ClaimResponse.serializer(), stop))
        assertFails("a stop names its message") { fromWire(ClaimResponse.serializer(), json.parseToJsonElement("""{"status":"stop-requested"}""")) }
        // The runner backs off on exactly this error, and never reads an unknown answer as another.
        assertFailsWith<kotlinx.serialization.SerializationException> {
            fromWire(ClaimResponse.serializer(), json.parseToJsonElement("""{"status":"stop-later","message":${stop.getValue("message")}}"""))
        }
    }

    @Test fun progressOnlyRunsOrSettlesAMessage() {
        MessageProgress(id(), id(), 2, MessageState.RUNNING)
        for (state in listOf(MessageState.ANSWERED, MessageState.STOPPED, MessageState.FAILED, MessageState.RECONCILIATION_REQUIRED)) MessageProgress(id(), id(), 3, state)
        for (state in listOf(MessageState.QUEUED, MessageState.CLAIMED, MessageState.EXPIRED, MessageState.CANCELLED)) assertFails { MessageProgress(id(), id(), 3, state) }
        assertEquals("reconciliation-required", wire(MessageProgress.serializer(), MessageProgress(id(), id(), 3, MessageState.RECONCILIATION_REQUIRED))
            .getValue("state").jsonPrimitive.content)
    }

    @Test fun theMacNeverWritesAsThePersonOrOutOfPlace() {
        assertFails { MessageAppend(MessageAuthor.USER, id(), null, "Hi", null) }
        assertFails { MessageAppend(MessageAuthor.SPECIALIST, id(), null, "Done", null) }
        assertFails { MessageAppend(MessageAuthor.SYSTEM, id(), CoordinatorNote(NoteKind.ANSWERED, id()), "x", id()) }
        assertFails { MessageAppend(MessageAuthor.COORDINATOR, id(), CoordinatorNote(NoteKind.ANSWERED, id()), "x", null) }
        val thread = id()
        assertFails { MessageAppend(MessageAuthor.COORDINATOR, id(), CoordinatorNote(NoteKind.ANSWERED, id()), "x", thread) }
        assertFails { MessageAppend(MessageAuthor.COORDINATOR, id(), CoordinatorNote(NoteKind.STARTED, thread), "x", thread) }
        MessageAppend(MessageAuthor.COORDINATOR, id(), CoordinatorNote(NoteKind.FORWARDED, thread), "x", thread)
    }

    @Test fun hostEventsAreReadFromFramesAndEnvelopes() {
        val conversation = id()
        val approval = id()
        val lines = listOf(
            ": keep-alive",
            "event: heartbeat", "data: {\"at\":\"$AT\"}", "",
            "data: {\"type\":\"work.available\",\"conversationId\":\"$conversation\"}", "",
            "id: 7", "event: approval.answered",
            "data: {\"sequence\":\"7\",\"occurredAt\":\"$AT\",\"type\":\"approval.answered\",\"version\":1,",
            "data: \"payload\":{\"type\":\"approval.answered\",\"approvalId\":\"$approval\"}}", "",
            "data: {\"type\":\"something.newer\",\"id\":\"x\"}", "",
            "data: not json", "",
            "data: {\"type\":\"stop.requested\",\"conversationId\":\"$conversation\"}\r", "\r",
        )
        val events = mutableListOf<HostStreamEvent>()
        HostEvents.read(lines.asSequence(), events::add)
        assertEquals(listOf(HostStreamEvent.WorkAvailable(conversation), HostStreamEvent.ApprovalAnswered(approval), HostStreamEvent.StopRequested(conversation)), events)
    }

    @Test fun routesAreSpelledOnceAndOnlyTheirMethodsAreAllowed() {
        val message = id()
        assertEquals("messages/$message/checkpoints", NativeRoute.MESSAGE_CHECKPOINT.path(message))
        assertEquals("artifacts/$message/text?cursor=40", NativeRoute.ARTIFACT_TEXT.path(message, cursor = 40))
        assertEquals("artifacts/$message/text", NativeRoute.ARTIFACT_TEXT.path(message))
        assertTrue(NativeRoute.allows("POST", NativeRoute.CONVERSATION_CLAIM.path()))
        assertTrue(NativeRoute.allows("GET", NativeRoute.STREAM.path()))
        assertTrue(NativeRoute.allows("PUT", NativeRoute.PROJECT_FOLDERS.path(message)))
        assertTrue(NativeRoute.allows("GET", NativeRoute.PROJECT.path(message)))
        assertTrue(NativeRoute.allows("GET", NativeRoute.ARTIFACT_TEXT.path(message, cursor = 0)))
        assertTrue(NativeRoute.allows("POST", NativeRoute.MEMORY_CLAIM.path()))
        assertFalse(NativeRoute.allows("POST", "claim"), "Hovel tasks are gone")
        assertFalse(NativeRoute.allows("POST", "progress"))
        assertFalse(NativeRoute.allows("GET", NativeRoute.PROJECT_FOLDERS.path(message)))
        assertFalse(NativeRoute.allows("POST", "approvals/$message/answer"), "the Mac answers through approval.closed")
        assertFalse(NativeRoute.allows("POST", "messages/$message/../../claim"))
        assertFalse(NativeRoute.allows("GET", "projects/$message?cursor=1"), "only an artifact's text pages")
        assertFails { NativeRoute.MESSAGE_PROGRESS.path(message.uppercase()) }
        assertFails { NativeRoute.APPROVAL.path("../x") }
        assertFails { NativeRoute.PROJECT.path(message, cursor = 1) }
        assertFails { NativeRoute.MESSAGE_DELTA.path(message, message) }
    }

    @Test fun theTransportSendsEachRouteWithTheTokenAndTheContract() {
        val owner = id(); val host = id(); val token = "b".repeat(64)
        val seen = CopyOnWriteArrayList<Triple<String, String, String>>()
        val status = java.util.concurrent.atomic.AtomicInteger(200)
        val refusal = java.util.concurrent.atomic.AtomicReference<String>("{}")
        val talk = TestConversation()
        val claim = talk.claim(id(), talk.message("Hi"), brain())
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            assertEquals(token, exchange.requestHeaders.getFirst("X-Hovel-Host-Token"))
            assertEquals(HovelContract.CURRENT.toString(), exchange.requestHeaders.getFirst(HovelContract.HEADER))
            val path = exchange.requestURI.path.removePrefix("/native/$owner/$host/") + (exchange.requestURI.query?.let { "?$it" } ?: "")
            seen += Triple(exchange.requestMethod, path, exchange.requestBody.bufferedReader().readText())
            if (path == "stream" && status.get() == 200) {
                exchange.responseHeaders.add("Content-Type", "text/event-stream")
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("data: {\"type\":\"work.available\",\"conversationId\":\"${talk.id}\"}\n\n".toByteArray()) }
                return@createContext
            }
            val data = when {
                path == "conversations/claim" -> wire(ClaimResponse.serializer(), ClaimResponse.Claimed(claim))
                path.endsWith("/progress") -> buildJsonObject { put("revision", 3) }
                path.startsWith("approvals/") -> wire(AgentApproval.serializer(), AgentApproval(null, host, talk.id, AT, path.split('/')[1],
                    ApprovalKind.PERMISSION, listOf(ApprovalOption.ALLOW_ONCE, ApprovalOption.DENY), ApprovalPresentation.MacOnly("Answer on the Mac"), 0,
                    ApprovalState.OPEN, null))
                path.startsWith("projects/") && exchange.requestMethod == "GET" -> wire(CompanionProject.serializer(), claim.project)
                path.startsWith("artifacts/") -> wire(ArtifactTextPage.serializer(), ArtifactTextPage(null, "Sign-in added."))
                else -> buildJsonObject {}
            }
            val body = if (status.get() != 200) refusal.get().toByteArray() else buildJsonObject { put("data", data) }.toString().toByteArray()
            exchange.sendResponseHeaders(status.get(), body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val transport = HovelHttpTransport(URI("http://127.0.0.1:${server.address.port}"), owner, host, token)
            val api = HttpConversationApi(transport, transport::openStream)
            assertEquals(ClaimResponse.Claimed(claim), api.claim(ClaimRequest(claim.claimId, talk.id)))
            assertEquals(3, api.progress(claim.message.id, MessageProgress(claim.claimId, claim.message.id, 2, MessageState.RUNNING)))
            api.checkpoint(claim.message.id, MessageCheckpointRequest(MessageCheckpoint.RunChanged(ConversationRun(4, null, 0, id(), 0, 1, emptyList(),
                BrainRunState.PLANNING, null)), claim.claimId, claim.message.id))
            api.delta(claim.message.id, ReplyDeltaRequest(claim.claimId, claim.message.id, ReplyDelta(id(), 0, "Hel", null)))
            val approval = id()
            assertEquals(approval, api.approval(approval).id)
            assertEquals(claim.project, api.project(talk.projectId))
            val artifact = id()
            assertEquals(ArtifactTextPage(null, "Sign-in added."), api.artifactText(artifact, 0))
            assertNull(api.saveFolders(talk.projectId, ProjectFoldersSave(3, emptyList())), "an answer without the project is fine")
            api.heartbeat(HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-extraction"), "ready"))
            val events = mutableListOf<HostStreamEvent>()
            api.openStream().use { HostEvents.read(it.lines(), events::add) }
            assertEquals(listOf<HostStreamEvent>(HostStreamEvent.WorkAvailable(talk.id)), events)
            assertEquals(listOf("POST" to "conversations/claim", "POST" to "messages/${claim.message.id}/progress",
                "POST" to "messages/${claim.message.id}/checkpoints", "POST" to "messages/${claim.message.id}/deltas", "GET" to "approvals/$approval",
                "GET" to "projects/${talk.projectId}", "GET" to "artifacts/$artifact/text?cursor=0", "PUT" to "projects/${talk.projectId}/folders",
                "POST" to "heartbeat", "GET" to "stream"),
                seen.map { it.first to it.second })
            val heartbeat = json.parseToJsonElement(seen.single { it.second == "heartbeat" }.third).jsonObject
            assertEquals(setOf("allowances", "directory", "features", "providerState"), heartbeat.keys)
            assertEquals("ready", heartbeat.getValue("providerState").jsonPrimitive.content)
            status.set(409)
            assertNull(assertFailsWith<HovelRejectedException> { api.claim(ClaimRequest(claim.claimId, talk.id)) }.code)
            refusal.set("not json")
            assertNull(assertFailsWith<HovelRejectedException> { api.claim(ClaimRequest(claim.claimId, talk.id)) }.code)
            refusal.set("""{"code":"update-required","minimumContract":3}""")
            assertEquals("update-required", assertFailsWith<HovelRejectedException> { api.claim(ClaimRequest(claim.claimId, talk.id)) }.code)
            // A service older than this Mac names it in a `hovelContractError`: on every route, and never final.
            status.set(400)
            refusal.set("""{"code":"unsupported-contract","serverContract":1}""")
            for (older in listOf(assertFailsWith<HovelStatusException> { api.claim(ClaimRequest(claim.claimId, talk.id)) },
                assertFailsWith<HovelStatusException> { api.openStream() })) {
                assertEquals(HovelStatusException.UNSUPPORTED_CONTRACT, older.code)
                assertTrue(older.serverOlder)
                assertFalse(older.final, "the work waits for the service")
            }
            refusal.set("""{"code":"contract-required"}""")
            val other = assertFailsWith<HovelStatusException> { api.claim(ClaimRequest(claim.claimId, talk.id)) }
            assertFalse(other.serverOlder)
            assertTrue(other.final, "any other 400 stays final")
            status.set(404)
            val missing = assertFailsWith<HovelStatusException> { api.approval(approval) }
            assertEquals(404, missing.status)
            assertTrue(missing.final)
            status.set(503)
            assertFalse(assertFailsWith<HovelStatusException> { api.approval(approval) }.final, "a 5xx may hide a committed request")
            assertFails("not a conversation route") { transport.exchange("DELETE", NativeRoute.CONVERSATION_CLAIM.path(), buildJsonObject {}) }
            assertFails("the answer route is gone") { transport.post("approvals/$approval/answer", buildJsonObject {}) }
            Unit
        } finally { server.stop(0) }
    }

    @Test fun theHostContractIsTradedWithThePageAndForgottenWithItsContext() = kotlinx.coroutines.runBlocking<Unit> {
        val contract = DesktopHostContract()
        assertEquals(HovelContract.CURRENT, contract.contract(7))
        assertEquals(7, contract.pageContract)
        contract.invalidate()
        assertNull(contract.pageContract)
        val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject
        assertEquals(limits.getValue("HOVEL_CONTRACT").jsonPrimitive.int, HovelContract.CURRENT)
        assertEquals(limits.getValue("HOVEL_CONTRACT_HEADER").jsonPrimitive.content, HovelContract.HEADER)
    }

    @Test fun companionLimitsMirrorTheContract() {
        val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject.getValue("COMPANION_LIMITS").jsonObject
        fun limit(name: String) = limits.getValue(name).jsonPrimitive.long
        assertEquals(limit("activitySummary"), CompanionLimits.ACTIVITY_SUMMARY.toLong())
        assertEquals(limit("approvalLine"), CompanionLimits.APPROVAL_LINE.toLong())
        assertEquals(limit("approvalLines"), CompanionLimits.APPROVAL_LINES.toLong())
        assertEquals(limit("approvalSummary"), CompanionLimits.APPROVAL_SUMMARY.toLong())
        assertEquals(limit("delta"), CompanionLimits.DELTA.toLong())
        assertEquals(limit("queuedMessageMs"), CompanionLimits.QUEUED_MESSAGE_MS)
        assertEquals(limit("threadOutputs"), CompanionLimits.THREAD_OUTPUTS.toLong())
        assertEquals(limit("threadTitle"), CompanionLimits.THREAD_TITLE.toLong())
    }
}
