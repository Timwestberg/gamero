package org.formulagen.wrapper.conversations

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.*
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.hovel.HovelHttpTransport
import org.formulagen.wrapper.providers.CodexProvider
import org.formulagen.wrapper.providers.DesktopProviders
import org.formulagen.wrapper.runtimes.CodexChatRuntime

/** Explicit loopback acceptance with real account, server and provider; dialog choices are scripted. */
class ConsentNativeSmokeTest {
    @Test fun declineReloadContinueAndReplayAgainstLocalQA() {
        val file = System.getenv("HOVEL_CONSENT_SMOKE")
        assumeTrue("Explicit local QA settings required", file != null)
        val config = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(file)))).jsonObject
        fun setting(key: String) = config.getValue(key).jsonPrimitive.content
        val base = URI(setting("baseUrl"))
        require(base.scheme == "http" && base.host == "127.0.0.1")
        val phase = System.getenv("HOVEL_CONSENT_PHASE") ?: "both"
        val root = config["recordRoot"]?.jsonPrimitive?.content?.let(Path::of) ?: Files.createTempDirectory("consent-native-smoke-")
        val owner = setting("ownerId")
        val conversation = setting("conversationId")
        val actual = HttpConversationApi(HovelHttpTransport(base.resolve("/v1/hovel"), owner, setting("hostId"), setting("hostToken"))) { error("No background stream") }
        val observations = mutableListOf<MessageCheckpoint.WorkflowEvidenceStored>()
        val stages = mutableSetOf<String>()
        var injected = false
        val api = object : ConversationApi by actual {
            override fun checkpoint(messageId: String, request: MessageCheckpointRequest) {
                val checkpoint = request.checkpoint
                if (checkpoint is MessageCheckpoint.ApprovalOpened && checkpoint.approval.kind == ApprovalKind.STAGE) stages += checkpoint.approval.id
                if (phase == "offline" && !injected && checkpoint is MessageCheckpoint.ApprovalClosed && checkpoint.approvalId in stages) {
                    actual.checkpoint(messageId, request.copy(checkpoint = MessageCheckpoint.ApprovalClosed(checkpoint.approvalId, ApprovalOutcome.Answered(ApprovalOption.NOT_NOW))))
                    injected = true
                    throw java.io.IOException("Injected lost close response with conflicting stored answer")
                }
                actual.checkpoint(messageId, request)
                if (checkpoint is MessageCheckpoint.WorkflowEvidenceStored) {
                    observations += checkpoint
                    actual.checkpoint(messageId, request)
                }
            }
        }
        fun ownerPost(path: String, body: JsonObject): JsonObject {
            val request = HttpRequest.newBuilder(base.resolve("/v1/hovel/$path"))
                .header("Authorization", "Bearer ${setting("ownerToken")}").header("X-Hovel-Contract", "5")
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()
            val reply = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
            check(reply.statusCode() in 200..299) { "Owner request failed: ${reply.statusCode()}" }
            return Json.parseToJsonElement(reply.body()).jsonObject.getValue("data").jsonObject
        }
        val messageId = config["messageId"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
        if (phase != "resume") ownerPost("conversations/$conversation/messages", buildJsonObject {
            put("id", messageId); put("threadId", JsonNull); put("inputs", JsonArray(emptyList()))
            put("text", "Start the configured workflow at its first step. Review this supplied sentence: The amber robot waves hello. Use the configured specialist and finish with its review. Do not ask for clarification.")
        })
        val executable = Path.of(setting("codex"))
        CodexChatRuntime(root.resolve("talk"), executable).use { chat ->
            DesktopProviders(listOf(CodexProvider(root.resolve("providers"), executable))).use { providers ->
                var activeClaim: String? = null
                var activeRevision = 0L
                fun run(choice: Int): Pair<MessageState, Long> {
                    val claim = assertIs<ClaimResponse.Claimed>(api.claim(ClaimRequest(UUID.randomUUID().toString(), conversation))).claim
                    assertEquals(messageId, claim.message.id)
                    assertEquals(true, claim.workflowEvidence)
                    assertTrue(claim.brain.configuration.specialists.all { it.mode.name == "TALK" })
                    val approvals = RemoteApprovals(NativeDialogs(Screen { choice }), api, setting("hostId"))
                    val store = ConversationStore(root.resolve("records"), owner)
                    val folders = ProjectFolderStore(root, FolderPicker { _, _ -> error("Talk-only QA") })
                    val turns = ProviderTurns(listOf(chat), emptyList(), providers, approvals, turnTimeout = 2.minutes)
                    val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(), api)
                    val revision = requireNotNull(api.progress(messageId, MessageProgress(claim.claimId,messageId,claim.message.revision,MessageState.RUNNING)))
                    activeClaim = claim.claimId
                    activeRevision = revision
                    val result = ClaimOutput(api,claim.claimId,messageId,attempts=1).use { engine.run(claim,it,CancelSignal()) { false } }
                    val settled = requireNotNull(api.progress(messageId,MessageProgress(claim.claimId,messageId,revision,result)))
                    return result to settled
                }
                if (phase == "offline") {
                    assertFailsWith<org.formulagen.wrapper.hovel.HovelRejectedException> { run(0) }
                    api.progress(messageId, MessageProgress(requireNotNull(activeClaim), messageId, activeRevision, MessageState.FAILED))
                    assertTrue(ConversationStore(root.resolve("records"), owner).read(conversation).brain.run!!.attempts.none { it.state == BrainAttemptState.COMPLETED })
                    val consent = ConversationStore(root.resolve("records"), owner).read(conversation).brain.run!!.stageConsents.values.single()
                    assertEquals(ApprovalOption.CONTINUE, consent.option)
                    assertEquals(ApprovalOption.NOT_NOW, actual.approval(requireNotNull(consent.approvalId)).answer?.option)
                    assertTrue(injected)
                    println("CONSENT_OFFLINE_CONFLICT PASS conversation=$conversation message=$messageId")
                    return
                }
                val declined = if (phase == "resume") MessageState.FAILED to config.getValue("retryRevision").jsonPrimitive.long else run(2)
                val before = ConversationStore(root.resolve("records"), owner).read(conversation).brain.run!!
                assertEquals(BrainRunState.PAUSED,before.summary.state)
                assertTrue(before.attempts.none { it.state == BrainAttemptState.COMPLETED })
                assertEquals(ApprovalOption.NOT_NOW,before.stageConsents.values.single().option)
                if (phase == "decline") {
                    val saved = JsonObject(config + mapOf("recordRoot" to JsonPrimitive(root.toString()), "messageId" to JsonPrimitive(messageId), "retryRevision" to JsonPrimitive(declined.second)))
                    Files.writeString(Path.of(requireNotNull(file)), saved.toString())
                    println("CONSENT_PROCESS_DECLINE PASS conversation=$conversation message=$messageId")
                    return
                }
                ownerPost("messages/$messageId/retry", buildJsonObject { put("expectedRevision",declined.second) })
                val continued = run(0)
                assertEquals(MessageState.ANSWERED,continued.first)
                val after = ConversationStore(root.resolve("records"),owner).read(conversation).brain.run!!
                assertEquals(BrainRunState.COMPLETED,after.summary.state)
                assertTrue(after.attempts.any { it.state == BrainAttemptState.COMPLETED })
                assertEquals(ApprovalOption.CONTINUE,after.stageConsents.values.single().option)
                assertEquals(if (phase == "resume") listOf("PENDING","ANSWERED") else listOf("PENDING","ANSWERED","PENDING","ANSWERED"),observations.map { it.evidence.state })
                println("CONSENT_NATIVE_SMOKE PASS conversation=$conversation message=$messageId evidence=${observations.size} replayed=${observations.size}")
            }
        }
    }
}
