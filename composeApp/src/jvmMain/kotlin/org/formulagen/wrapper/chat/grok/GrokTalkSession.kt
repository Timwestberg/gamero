package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GROK_ACCOUNT_METHODS
import org.formulagen.wrapper.providers.grok.GrokAccount
import org.formulagen.wrapper.providers.grok.GrokAcp
import org.formulagen.wrapper.providers.grok.GrokBudget
import org.formulagen.wrapper.providers.grok.GrokLaunch
import org.formulagen.wrapper.providers.grok.GrokRequestRejected
import org.formulagen.wrapper.providers.grok.GrokSessionLost
import org.formulagen.wrapper.providers.grok.GrokTransportFailure
import org.formulagen.wrapper.providers.grok.GrokTransportIssue
import org.formulagen.wrapper.providers.grok.grokAccount
import org.formulagen.wrapper.providers.grok.grokHandshake
import org.formulagen.wrapper.providers.grok.grokInitialize
import org.formulagen.wrapper.providers.grok.grokModels
import org.formulagen.wrapper.providers.grok.validGrokSessionId

/** One authenticated isolated session. Creation never sends a prompt; every prompt rechecks the account. */
internal class GrokTalkSession private constructor(
    private val launch: GrokLaunch,
    private val rpc: GrokAcp,
    private val initialized: JsonObject,
    private val lockdown: GrokToolEvidence,
    val account: GrokAccount,
    val sessionId: String,
    val model: String,
) : AutoCloseable {
    private val submitted = java.util.concurrent.atomic.AtomicBoolean()
    private val stopping = java.util.concurrent.atomic.AtomicBoolean()
    /** Revalidate before submission; a failed check cannot trigger a silent fallback or send a turn. */
    fun prompt(text: String, timeout: Duration = 90.seconds): JsonObject {
        val budget = GrokBudget(timeout)
        launch.checkPolicies()
        val refreshed = grokAccount(rpc, initialized, budget)
        check(refreshed.fingerprint == account.fingerprint) { "Grok account changed" }
        lockdown.requireAllowed(sessionId, budget.remaining())
        check(!stopping.get()) { "Grok turn stopped before submission" }
        return rpc.call("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }, budget.remaining(), onSubmitted = { submitted.set(true) })
    }

    /** Send cancellation; the prompt owner still awaits its terminal result before releasing resources. */
    fun cancel() {
        stopping.set(true)
        if (submitted.get()) rpc.cancel(sessionId) else rpc.close()
    }

    /** Bound a missing cancellation acknowledgement without blocking the caller. */
    fun abort() = rpc.close()

    /** Close and reap before deleting any session files. Invoke on a worker, never on the native UI thread. */
    override fun close() {
        launch.closeAfter(rpc)
    }

    companion object {
        /** Open only after supported-version, discovery, subscription and effective-tool checks all pass. */
        fun open(executable: Path, environment: Map<String, String>, instructions: String, model: String? = null,
            timeout: Duration = 20.seconds, persistentFolder: Path? = null, resume: String? = null,
            expectedAccount: String? = null, onUpdate: (JsonObject) -> Unit = {}): GrokTalkSession {
            val budget = GrokBudget(timeout)
            val launch = GrokLaunch.create(executable, environment, instructions, persistentFolder = persistentFolder)
            var rpc: GrokAcp? = null
            try {
                launch.verify(budget.remaining())
                val lockdown = GrokToolEvidence()
                val transport = GrokAcp(launch.agentBuilder(), GROK_ACCOUNT_METHODS + setOf("session/new", "session/load", "session/prompt"),
                    allowReloadResponses = true,
                    onNotification = { method, params ->
                        if (method == "session/update") { lockdown.observe(params); onUpdate(params) }
                    })
                rpc = transport
                val initialized = transport.call("initialize", grokInitialize(), budget.remaining())
                launch.checkPolicies()
                val account = grokAccount(transport, initialized, budget)
                check(expectedAccount == null || account.fingerprint == expectedAccount) { "Grok account changed" }
                launch.checkPolicies()
                val selected = model ?: account.defaultModel
                check(account.models.any { it.modelId == selected }) { "Grok model unavailable" }
                if (resume != null) check(validGrokSessionId(resume) && grokHandshake(initialized).loadSession)
                val created = try {
                    transport.call(if (resume == null) "session/new" else "session/load", buildJsonObject {
                        if (resume != null) put("sessionId", resume)
                        put("cwd", launch.workspace.toString()); putJsonArray("mcpServers") {}
                        putJsonObject("_meta") { put("modelId", selected) }
                    }, budget.remaining())
                } catch (rejected: GrokRequestRejected) { throw if (resume != null) GrokSessionLost() else rejected }
                val id = resume ?: (created["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf(::validGrokSessionId) ?: error("Invalid Grok session id")
                val models = created["models"] as? JsonObject ?: error("Missing session model")
                check(grokModels(models).first == selected) { "Grok did not select the requested model" }
                lockdown.requireAllowed(id, budget.remaining())
                launch.checkPolicies()
                return GrokTalkSession(launch, transport, initialized, lockdown, account, id, selected)
            } catch (error: Exception) {
                launch.closeAfter(rpc)
                throw error
            }
        }
    }
}

/** Correlated, monotonic tool evidence: a later safe update cannot erase an earlier unsafe toolset. */
internal class GrokToolEvidence(private val allowed: Set<String> = emptySet()) {
    private val sessions = LinkedHashMap<String, CompletableFuture<Unit>>()
    private val unsafe = HashSet<String>()
    private var selected: String? = null

    /** The runtime publishes effective registered tools in available_commands_update._meta.tools. */
    @Synchronized fun observe(params: JsonObject) {
        val update = params["update"] as? JsonObject ?: return
        if (update["sessionUpdate"] != JsonPrimitive("available_commands_update")) return
        val id = (params["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing tool evidence owner")
        check(validGrokSessionId(id))
        if (selected != null && id != selected) return
        check(id in sessions || sessions.size < 8) { "Too many Grok session updates" }
        val ready = sessions.getOrPut(id) { CompletableFuture() }
        val tools = (update["_meta"] as? JsonObject)?.get("tools") as? JsonArray
        if (tools == null || tools.size > allowed.size || tools.any {
            (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content !in allowed
        }) {
            unsafe += id
            ready.completeExceptionally(GrokTransportFailure(GrokTransportIssue.LOCKDOWN))
            if (selected == id) throw GrokTransportFailure(GrokTransportIssue.LOCKDOWN)
        } else if (id !in unsafe) ready.complete(Unit)
    }

    /** Wait for the exact new session, including evidence that arrived before session/new answered. */
    fun requireAllowed(id: String, timeout: Duration) {
        val ready = synchronized(this) {
            check(selected == null || selected == id)
            if (id in unsafe) throw GrokTransportFailure(GrokTransportIssue.LOCKDOWN)
            selected = id
            sessions.getOrPut(id) { CompletableFuture() }
        }
        try { ready.get(timeout.inWholeMilliseconds.coerceAtLeast(1), TimeUnit.MILLISECONDS) }
        catch (_: java.util.concurrent.ExecutionException) { throw GrokTransportFailure(GrokTransportIssue.LOCKDOWN) }
        synchronized(this) { if (id in unsafe) throw GrokTransportFailure(GrokTransportIssue.LOCKDOWN) }
    }
}
