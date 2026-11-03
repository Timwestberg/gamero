package org.formulagen.wrapper.conversations

import java.net.URI
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
import org.formulagen.wrapper.runtimes.Readiness

/** Explicit local acceptance: real server claim, native coordinator, and signed-in talk runtime. */
class JevNativeSmokeTest {
    @Test fun realCoordinatorAnswersWhileServerObservesJev() {
        val configFile = System.getenv("HOVEL_JEV_NATIVE_SMOKE")
        assumeTrue("Set HOVEL_JEV_NATIVE_SMOKE to private local QA settings", configFile != null)
        val config = Json.parseToJsonElement(Files.readString(Path.of(requireNotNull(configFile)))).jsonObject
        fun setting(name: String) = config.getValue(name).jsonPrimitive.content
        val base = URI(setting("baseUrl"))
        require(base.scheme == "http" && base.host == "127.0.0.1") { "Only loopback QA services are allowed" }
        val root = Files.createTempDirectory("jev-native-smoke-")
        val executable = Path.of(setting("codex"))
        val account = setting("ownerId")
        val api = HttpConversationApi(HovelHttpTransport(base, account, setting("hostId"), setting("hostToken"))) {
            error("This bounded test does not open the background stream")
        }
        try {
            CodexChatRuntime(root.resolve("talk"), executable).use { chat ->
                DesktopProviders(listOf(CodexProvider(root.resolve("providers"), executable))).use { providers ->
                    assertEquals(Readiness.Ready, chat.readiness(account))
                    val claim = assertIs<ClaimResponse.Claimed>(api.claim(ClaimRequest(UUID.randomUUID().toString(), setting("conversationId")))).claim
                    assertTrue(claim.brain.configuration.specialists.isNotEmpty())
                    assertTrue(claim.brain.configuration.specialists.all { it.mode.name == "TALK" })
                    val dialogs = NativeDialogs(Screen { error("Unexpected approval in talk-only native test") })
                    val approvals = RemoteApprovals(dialogs, api, setting("hostId"))
                    val store = ConversationStore(root.resolve("records"), account)
                    val folders = ProjectFolderStore(root, FolderPicker { _, _ -> error("No filesystem selection in talk test") })
                    val turns = ProviderTurns(listOf(chat), emptyList(), providers, approvals, turnTimeout = 2.minutes)
                    val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(), api)
                    val cancel = CancelSignal()
                    val revision = requireNotNull(api.progress(claim.message.id,
                        MessageProgress(claim.claimId, claim.message.id, claim.message.revision, MessageState.RUNNING)))
                    val result = ClaimOutput(api, claim.claimId, claim.message.id, attempts = 1, onStopRequested = cancel::cancel).use {
                        engine.run(claim, it, cancel) { false }
                    }
                    api.progress(claim.message.id, MessageProgress(claim.claimId, claim.message.id, revision, result))
                    assertEquals(MessageState.ANSWERED, result)
                    println("JEV_NATIVE_SMOKE PASS conversation=${claim.conversation.id} message=${claim.message.id} state=$result")
                }
            }
        } finally { root.toFile().deleteRecursively() }
    }
}
