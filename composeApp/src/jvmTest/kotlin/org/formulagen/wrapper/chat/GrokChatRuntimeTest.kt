package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.chat.grok.GROK_VERIFIED_VERSION
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.GrokProvider
import org.junit.Assume.assumeTrue

class GrokChatRuntimeTest {
    @Test fun providerAndChatShareReadinessAndResumeOnlyTheirPrivateBinding() {
        FakeGrokRuntime().use { fake ->
            val connection = GrokConnection(fake.env)
            val provider = GrokProvider(connection)
            assertEquals(ProviderState.CONNECTED, provider.status("local", ProviderAction.STATUS).state)
            assertEquals("grok-test", provider.models("local").single().modelId)
            val first = Events()
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, connection).use { runtime ->
                runtime.startTurn(request(), first)
                first.await()
                assertEquals(listOf("opened", "accepted", "partial", "completed"), first.seen)
                assertEquals("Hello", first.reply)
                assertNotEquals("provider/opaque-id", first.handle)
                val next = Events()
                runtime.startTurn(request(NativeConversation(checkNotNull(first.handle), persona.hash)), next)
                next.await()
                assertEquals(listOf("accepted", "partial", "completed"), next.seen)
                assertEquals(first.handle, next.handle)
                assertEquals(2, fake.requests().count { it == "session/prompt" })
                assertEquals(1, fake.requests().count { it == "session/load" })
            }
            val restarted = Events()
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, connection).use { runtime ->
                runtime.startTurn(request(NativeConversation(checkNotNull(first.handle), persona.hash)), restarted)
                restarted.await()
                assertEquals("completed", restarted.seen.last())
                assertEquals(first.handle, restarted.handle)
            }
        }
    }

    @Test fun missingBindingAndPersonaChangeFailBeforeSubmissionWhileModelAndTransportErrorsNeverReplay() {
        FakeGrokRuntime().use { fake ->
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env)).use { runtime ->
                for (saved in listOf(NativeConversation("11111111-1111-4111-8111-111111111111", persona.hash),
                    NativeConversation("11111111-1111-4111-8111-111111111111", Persona.companion("Other").hash))) {
                    val events = Events()
                    runtime.startTurn(request(saved), events); events.await()
                    assertEquals(ChatFailure.ConversationLost, events.failure)
                }
                val model = Events()
                runtime.startTurn(request().copy(model = "missing"), model); model.await()
                assertEquals(ChatFailure.ModelUnavailable, model.failure)
                assertFalse("session/prompt" in fake.requests())
                fake.mode("drop")
                val dropped = Events()
                runtime.startTurn(request(), dropped); dropped.await()
                assertEquals(ChatFailure.Temporary, dropped.failure)
                assertEquals(1, fake.requests().count { it == "session/prompt" })
                assertFalse("accepted" in dropped.seen)
            }
        }
    }

    @Test fun toolsFailBeforePromptAndSummaryDoesNotCreateABinding() {
        FakeGrokRuntime().use { fake ->
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env)).use { runtime ->
                fake.mode("tools")
                val unsafe = Events()
                runtime.startTurn(request(), unsafe); unsafe.await()
                assertEquals(ChatFailure.Lockdown, unsafe.failure)
                assertFalse("session/prompt" in fake.requests())
                fake.mode("normal")
                assertEquals("Hello", runtime.summarize(SummaryRequest("local", persona, null, "A conversation")))
                assertFalse(Files.exists(fake.root.resolve("providers/grok-bindings")))
            }
        }
    }

    @Test fun bindingCleanupRetainsAllHistoriesAndSkipsUnfamiliarOrCorruptData() {
        val root = Files.createTempDirectory("grok-prune-test")
        try {
            assertEquals(emptySet(), grokHistoryHandles(root, "local"))
            val first = "10000000-0000-4000-8000-000000000001"
            val second = "10000000-0000-4000-8000-000000000002"
            val directory = Files.createDirectories(root.resolve("local/companion-chats"))
            ChatHistory(directory.resolve("$first.json")).write(ChatRecord(conversations = mapOf(
                org.formulagen.wrapper.bridge.RuntimeId.GROK_BUILD to NativeConversation(first, persona.hash))))
            ChatHistory(directory.resolve("$second.json")).write(ChatRecord(workConversations = mapOf(
                org.formulagen.wrapper.bridge.RuntimeId.GROK_BUILD to NativeConversation(second, persona.hash))))
            assertEquals(setOf(first, second), grokHistoryHandles(root, "local"))
            Files.writeString(directory.resolve("$second.json"), "invalid")
            assertNull(grokHistoryHandles(root, "local"))
            Files.delete(directory.resolve("$second.json"))
            Files.writeString(directory.resolve("unexpected"), "")
            assertNull(grokHistoryHandles(root, "local"))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun stopWaitsForCancellationAndDoesNotDeliverLateEvents() {
        FakeGrokRuntime().use { fake ->
            fake.mode("wait")
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env)).use { runtime ->
                val events = Events()
                val handle = runtime.startTurn(request(), events)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ("session/prompt" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                handle.stop()
                while ("session/cancel" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                Thread.sleep(50)
                assertEquals(listOf("opened"), events.seen)
            }
        }
    }
}

/** Full production adapter opt-in, including a saved handle reused by a new adapter instance. */
class GrokChatRuntimeSmokeTest {
    @Test fun nativeAdapterUsesTheSubscriptionAndRestoresConversationAfterRestart() {
        val executable = System.getenv("HOVEL_GROK_CHAT_SMOKE")
        assumeTrue("Set HOVEL_GROK_CHAT_SMOKE for the two-prompt production adapter test", executable != null)
        val root = Files.createTempDirectory("grok-adapter-smoke")
        val env = System.getenv() + ("GAMERO_GROK_PATH" to requireNotNull(executable))
        try {
            val connection = GrokConnection(env)
            assertEquals(ProviderState.CONNECTED, GrokProvider(connection).status("local", ProviderAction.STATUS).state)
            val first = Events()
            GrokChatRuntime(root, env, connection).use { runtime ->
                runtime.startTurn(request().copy(text = "Remember HOVEL_ORCHID for this conversation. Reply briefly."), first)
                first.await(60)
                assertNull(first.failure)
                assertTrue("accepted" in first.seen)
            }
            GrokChatRuntime(root, env, connection).use { runtime ->
                val next = Events()
                runtime.startTurn(request(NativeConversation(checkNotNull(first.handle), persona.hash)).copy(
                    text = "What word did I ask you to remember? Reply with the word only."), next)
                next.await(60)
                assertNull(next.failure)
                assertTrue(next.reply.orEmpty().contains("HOVEL_ORCHID"))
                assertEquals(first.handle, next.handle)
            }
            println("GROK_CHAT_SMOKE: provider connected, accepted/partial/completed delivered, private handle resumed after adapter restart")
        } finally { root.toFile().deleteRecursively() }
    }
}

private val persona = Persona.companion("Ember")
private fun request(conversation: NativeConversation? = null) = TurnRequest("local", "20000000-0000-4000-8000-000000000002",
    persona, null, conversation, null, "Hello")

private class Events : TurnEvents {
    val seen = CopyOnWriteArrayList<String>()
    val done = CompletableFuture<Unit>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation; seen += "opened" }
    override fun accepted() { seen += "accepted" }
    override fun partial(text: String) { reply = text; seen += "partial" }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; seen += "completed"; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; seen += "failed"; done.complete(Unit) }
    fun await(seconds: Long = 10) { done.get(seconds, TimeUnit.SECONDS) }
}

/** Process fixture exercises the actual version, inspection, account, session and streaming boundaries. */
internal class FakeGrokRuntime : AutoCloseable {
    val root = Files.createTempDirectory("fake-grok-runtime")
    private val executable = root.resolve("grok")
    val env = mapOf("HOME" to root.toString(), "GAMERO_GROK_PATH" to executable.toString())
    init {
        mode("normal")
        Files.createDirectories(root.resolve(".grok"))
        Files.writeString(root.resolve(".grok/config.toml"), "[auth]\npreferred_method='oidc'\n")
        Files.writeString(executable, """
            #!/usr/bin/python3
            import json, sys, pathlib, os
            root=pathlib.Path(__file__).parent
            def out(value): print(json.dumps(dict(jsonrpc='2.0',**value)),flush=True)
            if '--version' in sys.argv: print('${GROK_VERIFIED_VERSION}'); sys.exit(0)
            if 'inspect' in sys.argv:
                print(json.dumps(dict(projectTrusted=True,projectInstructions=[],hooks=[],skills=[],plugins=[],mcpServers=[],lspServers=[],
                    configSources={'layers':[{'role':'user','path':str(pathlib.Path(os.environ['GROK_HOME'])/'config.toml')}]},permissions={'managedSettingsExists':False}))); sys.exit(0)
            meta=dict(auth_mode='Oidc',backend_billed=False,is_team_principal=False,is_organization_principal=False,gate=None,subscription_tier='SuperGrok')
            models=dict(currentModelId='grok-test',availableModels=[dict(modelId='grok-test',name='Grok Test')])
            sid='provider/opaque-id'
            waiting=None
            with (root/'pids').open('a') as f: f.write(str(os.getpid())+'\n')
            for line in sys.stdin:
                req=json.loads(line); method=req.get('method'); rid=req.get('id')
                if method is None:
                    with (root/'requests').open('a') as f: f.write('permission/replied\n')
                    continue
                with (root/'requests').open('a') as f: f.write(method+'\n')
                mode=(root/'mode').read_text()
                result={}
                if method=='initialize': result=dict(protocolVersion=1,authMethods=[{'id':'cached_token'}],agentCapabilities={'loadSession':True},_meta={'modelState':models})
                elif method=='_x.ai/auth/info': result=dict(methodId='cached_token',principalType='user',principalId='fixture-user')
                elif method=='_x.ai/auth/check_subscription': result=dict(authenticated=True,meta=meta)
                elif method=='authenticate': result={'_meta':meta}
                elif method in ['session/new','session/load']:
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'available_commands_update','_meta':{'tools':['shell'] if mode=='tools' else []}}}})
                    result={'sessionId':sid,'models':models}
                elif method=='session/prompt':
                    if mode=='drop': sys.exit(0)
                    if mode=='wait': waiting=rid; continue
                    if mode=='approval':
                        waiting=rid
                        tool=dict(toolCallId='operation',rawInput=dict(variant='Bash',command='printf TEST',description='Test command'),
                            _meta={'x.ai/tool':dict(version=1,namespace='grok_build',kind='execute',name='run_terminal_cmd',label='Run',read_only=False,
                                input=dict(command='printf TEST',description='Test command'))})
                        options=[dict(optionId='yes',kind='allow_once',name='Allow once'),dict(optionId='no',kind='reject_once',name='Deny')]
                        out(dict(id='permission',method='session/request_permission',params=dict(sessionId=sid,toolCall=tool,options=options)))
                        continue
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_thought_chunk','content':{'type':'text','text':'hidden'}}}})
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'agent_message_chunk','content':{'type':'text','text':'Hello'}}}})
                    result={'stopReason':'end_turn'}
                elif method=='session/cancel':
                    if waiting is None: continue
                    rid=waiting; waiting=None; result={'stopReason':'cancelled'}
                else: raise RuntimeError('Unexpected method')
                out({'id':rid,'result':result})
        """.trimIndent())
        executable.toFile().setExecutable(true)
    }
    fun mode(value: String) { Files.writeString(root.resolve("mode"), value) }
    fun requests(): List<String> = root.resolve("requests").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }
    override fun close() { root.toFile().deleteRecursively() }
}
