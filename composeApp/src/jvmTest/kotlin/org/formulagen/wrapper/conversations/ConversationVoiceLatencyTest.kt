package org.formulagen.wrapper.conversations

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.*
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.voice.DuplexChoice
import org.formulagen.wrapper.voice.FillerSettings
import org.formulagen.wrapper.voice.HearingDuplex
import org.formulagen.wrapper.voice.HearingResult
import org.formulagen.wrapper.workspace.FolderPicker
import org.junit.Assume.assumeTrue

/**
 * Hermetic, and measured only with `HOVEL_VOICE_LATENCY=1`, since it asserts wall-clock time: endpoint to the first
 * sentence queued at the speaker, with a provider that answers at once and a server with no latency. Team chat sends
 * in memory; a conversation posts, claims and journals the claim durably four times before its provider turn
 * (claiming, claimed, launching, running), then runs the engine. The difference is what this Mac adds; each real
 * round trip adds its network time (three on the heard path: send, claim, running).
 */
class ConversationVoiceLatencyTest {
    @Test fun aHeardTurnReachesTheSpeakerNearlyAsFastAsTeamChat() {
        assumeTrue("Set HOVEL_VOICE_LATENCY=1 to measure voice latency", System.getenv("HOVEL_VOICE_LATENCY") == "1")
        val chat = (1..TURNS).map { teamChatTurn() }.sorted()
        val conversation = conversationTurns().sorted()
        val chatMedian = chat[TURNS / 2]
        val conversationMedian = conversation[TURNS / 2]
        println("voice latency endpoint->first piece queued (ms): team chat median=$chatMedian all=$chat; " +
            "conversation median=$conversationMedian all=$conversation")
        assertTrue(conversationMedian - chatMedian < 150, "the conversation path adds ${conversationMedian - chatMedian} ms")
    }

    private fun teamChatTurn(): Long {
        val speaker = FakeSpeaker()
        val runtime = object : ChatRuntime {
            override val id = RuntimeId.CODEX
            override fun readiness(profile: String) = Readiness.Ready
            override fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle {
                val conversation = id()
                events.opened(conversation)
                events.accepted()
                events.partial("It is late.")
                events.completed("It is late. Time for tea.", conversation)
                return TurnHandle {}
            }
            override fun summarize(request: SummaryRequest) = "summary"
            override fun release() {}
            override fun close() {}
        }
        val scope = ChatScope("local", id(), "Ember", id())
        DesktopChat(java.nio.file.Files.createTempDirectory("latency-chat"), listOf(runtime), speaker, guardMs = 0).use { desk ->
            runBlocking {
                desk.open(scope)
                desk.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId,
                    TeamMember(RuntimeId.CODEX, SharedRules.RUNTIME_DEFAULT_MODEL), id(), "Hello", false))
            }
            eventually { runBlocking { desk.snapshot(scope) }.state == ChatState.READY }
            desk.testingSession()
            val endpoint = System.nanoTime()
            desk.testingUtterance(HearingResult("what time is it", 900, 1.0))
            eventually { speaker.firstPieceAt != 0L }
            return (speaker.firstPieceAt - endpoint) / 1_000_000
        }
    }

    private fun conversationTurns(): List<Long> {
        val server = FakeServer()
        val talk = TestConversation()
        val root = folder("latency-voice")
        val account = id()
        val store = ConversationStore(root, account).open()
        val folders = ProjectFolderStore(root, FolderPicker { _, _ -> null })
        val coordinator = ScriptedRuntime(RuntimeId.CODEX, chunk = 12) { "It is late. Time for tea." }
        val approvals = RemoteApprovals(NativeDialogs(Screen()), server, server.hostId)
        val engine = CoordinatorEngine(store, folders, ProviderTurns(listOf(coordinator), emptyList(), ConnectedProviders(), approvals),
            approvals, StartOfWork(NativeDialogs(Screen())), server)
        val ear = FakeEar()
        val speaker = FakeSpeaker()
        val settings = TalkSettings(FillerSettings(), duplex = DuplexChoice(HearingDuplex.HALF, "test"), guardMs = 0)
        val voice = DesktopConversationVoice(speaker, SharedHearing(ear), settings, null, HOVEL_PRODUCT)
        val beat = HostHeartbeat(emptyList(), ProviderDirectory(AT, emptyList()), listOf("memory-context"), "ready")
        val runner = ConversationRunner(server, store, engine, approvals, folders,
            ProjectFolderPublisher(folders, server, account, server.hostId) { emptyList() }, { beat }, replies = voice)
        // One brain for the conversation, so its coordinator continues one provider conversation as it does for a person.
        val document = brain()
        server.onSend = { _, message ->
            Queued(talk.message(message.text, messageId = message.id)) { claimId, claimed -> talk.claim(claimId, claimed, document) }
        }
        try {
            runner.start()
            voice.connected(heardTurns(server, runner))
            runBlocking { voice.hearing(ConversationHearingArgs(talk.id, HearingAction.HANDS_FREE)) }
            return (1..TURNS).map { turn ->
                speaker.pieces.clear()
                speaker.firstPieceAt = 0L
                val endpoint = System.nanoTime()
                ear.hear("what time is it, turn $turn")
                eventually { speaker.firstPieceAt != 0L }
                val took = (speaker.firstPieceAt - endpoint) / 1_000_000
                eventually { server.sent.size == turn && server.settled(server.sent.last().second.id) == MessageState.ANSWERED }
                speaker.settle()
                eventually { voice.served?.talk?.phase == TalkPhase.LISTENING }
                took
            }
        } finally {
            voice.close(); runner.close(); store.close()
        }
    }

    private companion object {
        const val TURNS = 9
    }
}
