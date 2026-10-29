package org.formulagen.wrapper.chat

import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.*
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.ChatEntry
import org.formulagen.wrapper.bridge.ChatOpenForClient
import org.formulagen.wrapper.bridge.ChatScope
import org.formulagen.wrapper.bridge.ChatSend
import org.formulagen.wrapper.bridge.ChatState
import org.formulagen.wrapper.bridge.ChatVoice
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.chat.grok.GROK_TURN_HANDOFF
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

    @Test fun aRejectedSessionLoadLosesOnlyThatConversationAndDropsItsBinding() {
        FakeGrokRuntime().use { fake ->
            val providers = fake.root.resolve("providers")
            GrokChatRuntime(providers, fake.env, GrokConnection(fake.env)).use { runtime ->
                val first = Events()
                runtime.startTurn(request(), first); first.await()
                val saved = NativeConversation(checkNotNull(first.handle), persona.hash)
                assertTrue(Files.exists(providers.resolve("grok-bindings/${saved.id}.json")))
                fake.mode("reject-load")
                val rejected = Events()
                runtime.startTurn(request(saved), rejected); rejected.await()
                assertEquals(ChatFailure.ConversationLost, rejected.failure)
                assertFalse(Files.exists(providers.resolve("grok-bindings/${saved.id}.json")))
                // The handle no longer names a session, so a repeat cannot repeat the failing load.
                val again = Events()
                runtime.startTurn(request(saved), again); again.await()
                assertEquals(ChatFailure.ConversationLost, again.failure)
                assertEquals(1, fake.requests().count { it == "session/load" })
                assertEquals(1, fake.requests().count { it == "session/prompt" })
            }
        }
    }

    @Test fun desktopChatStartsAFreshGrokConversationOnceWhenTheSavedOneCannotLoad() = runBlocking {
        FakeGrokRuntime().use { fake ->
            val root = fake.root.resolve("providers")
            val scope = ChatScope("local", "20000000-0000-4000-8000-000000000002", "Ember", UUID.randomUUID().toString())
            val member = TeamMember(RuntimeId.GROK_BUILD, "runtime-default")
            val speech = object : ChatSpeech {
                override fun status() = ChatVoice()
                override fun speak(text: String) = Unit
                override fun stop() = Unit
                override fun close() = Unit
            }
            DesktopChat(root, listOf(GrokChatRuntime(root, fake.env, GrokConnection(fake.env))), speech).use { chat ->
                chat.openForClient(ChatOpenForClient(scope.profile, scope.companionId, scope.name, scope.sessionId, listOf("grok-build")))
                suspend fun reply(text: String) {
                    chat.send(ChatSend(scope.profile, scope.companionId, scope.name, scope.sessionId, member, UUID.randomUUID().toString(), text, false))
                    val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                    while (chat.snapshot(scope).state != ChatState.READY) { check(System.nanoTime() < until) { "Chat did not finish" }; Thread.sleep(20) }
                }
                reply("First")
                fake.mode("reject-load")
                reply("Second")
                assertEquals(listOf("Hello", "Hello"), chat.snapshot(scope).messages.filterIsInstance<ChatEntry.Assistant>().map { it.text })
                assertEquals(1, fake.requests().count { it == "session/load" })
                assertEquals(2, fake.requests().count { it == "session/new" })
                assertEquals(2, fake.requests().count { it == "session/prompt" })
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

    @Test fun cleanupKeepsEveryBindingAHistoryNamesWhereverThatHistoryIsAndDropsOrphans() {
        FakeGrokRuntime().use { fake ->
            val providers = fake.root.resolve("providers")
            GrokChatRuntime(providers, fake.env, GrokConnection(fake.env)).use { runtime ->
                val kept = Events(); runtime.startTurn(request(), kept); kept.await()
                companionChatHistory(providers, "local", "20000000-0000-4000-8000-000000000002").write(ChatRecord(conversations = mapOf(
                    RuntimeId.GROK_BUILD to NativeConversation(checkNotNull(kept.handle), persona.hash))))
                val orphan = Events(); runtime.startTurn(request(), orphan); orphan.await()
                // Another companion's version 1 history, never opened since teams, so still in the legacy folder.
                val legacy = Files.createDirectories(providers.resolve("local/codex/companion-chats"))
                    .resolve("20000000-0000-4000-8000-000000000009.json")
                Files.writeString(legacy, """{"threadId":"10000000-0000-4000-8000-000000000001","messages":[]}""")
                val next = Events(); runtime.startTurn(request(), next); next.await()
                assertTrue(Files.exists(providers.resolve("grok-bindings/${kept.handle}.json")))
                assertFalse(Files.exists(providers.resolve("grok-bindings/${orphan.handle}.json")))
                assertTrue(Files.exists(providers.resolve("grok-bindings/${next.handle}.json")))
                assertTrue(Files.exists(legacy), "Reading histories for cleanup never moves one")
            }
        }
    }

    @Test fun aMessageSentRightAfterStoppingAnUnresponsiveTurnWaitsForItsTeardown() {
        FakeGrokRuntime().use { fake ->
            fake.mode("stuck")
            GrokChatRuntime(fake.root.resolve("providers"), fake.env, GrokConnection(fake.env)).use { runtime ->
                val stuck = Events()
                val handle = runtime.startTurn(request(), stuck)
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while ("session/prompt" !in fake.requests()) { check(System.nanoTime() < until); Thread.sleep(10) }
                fake.mode("normal")
                handle.stop()
                // Grok ignores the cancellation, so the old turn holds the companion for its whole grace and teardown.
                val next = Events()
                runtime.startTurn(request(), next); next.await(GROK_TURN_HANDOFF.inWholeSeconds + 5)
                assertNull(next.failure)
                assertEquals("Hello", next.reply)
                assertFalse("completed" in stuck.seen || "failed" in stuck.seen)
            }
        }
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
                trust=json.loads((root/'trust.json').read_text()) if (root/'trust.json').exists() else {}
                if '--trust' in sys.argv:
                    with (root/'requests').open('a') as f: f.write('trust\n')
                    trust['trusted']=True; trust['root']=trust.get('rootAfterTrust',trust.get('root')); (root/'trust.json').write_text(json.dumps(trust))
                report=dict(projectTrusted=trust.get('trusted',True),projectInstructions=[],hooks=[],skills=[],plugins=[],mcpServers=[],lspServers=[],
                    configSources={'layers':[{'role':'user','path':str(pathlib.Path(os.environ['GROK_HOME'])/'config.toml')}]},permissions={'managedSettingsExists':False})
                if trust.get('root'): report['projectRoot']=trust['root']
                print(json.dumps(report)); sys.exit(0)
            meta=dict(auth_mode='Oidc',backend_billed=False,is_team_principal=False,is_organization_principal=False,gate=None,subscription_tier='SuperGrok')
            models=dict(currentModelId='grok-test',availableModels=[dict(modelId='grok-test',name='Grok Test')])
            sid='provider/opaque-id'
            waiting=None
            with (root/'pids').open('a') as f: f.write(str(os.getpid())+'\n')
            (root/'agent-environment.json').write_text(json.dumps(dict(os.environ)))
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
                elif method=='session/load' and mode=='reject-load': out({'id':rid,'error':{'code':-32002,'message':'Session not found'}}); continue
                elif method in ['session/new','session/load']:
                    out({'method':'session/update','params':{'sessionId':sid,'update':{'sessionUpdate':'available_commands_update','_meta':{'tools':['shell'] if mode=='tools' else []}}}})
                    result={'sessionId':sid,'models':models}
                elif method=='session/prompt':
                    if mode=='drop': sys.exit(0)
                    if mode=='wait': waiting=rid; continue
                    if mode=='stuck': continue
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
    /** Inspection reports the project untrusted with [projectRoot]; `--trust` then reports [rootAfterTrust]. */
    fun untrusted(projectRoot: java.nio.file.Path, rootAfterTrust: java.nio.file.Path = projectRoot) = Files.writeString(root.resolve("trust.json"),
        buildJsonObject { put("trusted", false); put("root", projectRoot.toString()); put("rootAfterTrust", rootAfterTrust.toString()) }.toString())
    fun requests(): List<String> = root.resolve("requests").let { if (Files.exists(it)) Files.readAllLines(it) else emptyList() }
    override fun close() { root.toFile().deleteRecursively() }
}
