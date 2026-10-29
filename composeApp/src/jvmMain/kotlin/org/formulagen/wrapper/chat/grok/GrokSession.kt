package org.formulagen.wrapper.chat.grok

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GROK_ACCOUNT_METHODS
import org.formulagen.wrapper.providers.grok.GrokAccount
import org.formulagen.wrapper.providers.grok.GrokAcp
import org.formulagen.wrapper.providers.grok.GrokBudget
import org.formulagen.wrapper.providers.grok.GrokCleanupUnconfirmed
import org.formulagen.wrapper.providers.grok.GrokLaunchPolicy
import org.formulagen.wrapper.providers.grok.GrokRequestRejected
import org.formulagen.wrapper.providers.grok.GrokTransportFailure
import org.formulagen.wrapper.providers.grok.GrokTransportIssue
import org.formulagen.wrapper.providers.grok.grokAccount
import org.formulagen.wrapper.providers.grok.grokHandshake
import org.formulagen.wrapper.providers.grok.grokInitialize
import org.formulagen.wrapper.providers.grok.grokModels
import org.formulagen.wrapper.providers.grok.validGrokSessionId

/**
 * One authenticated ACP session over one launch [L], for talk or work. Opening never sends a prompt; every
 * prompt first revalidates the launch, then the account and the session's tool evidence.
 */
internal sealed class GrokSession<L : GrokLaunchPolicy>(
    protected val launch: L,
    opened: GrokOpenedSession,
    private val promptTimeout: Duration,
) : AutoCloseable {
    private val rpc = opened.rpc
    private val initialized = opened.initialized
    private val tools = opened.tools
    val account: GrokAccount = opened.account
    val sessionId: String = opened.sessionId
    val model: String = opened.model
    private val submitted = AtomicBoolean()
    private val stopping = AtomicBoolean()

    /** Recheck the launch before the account; a failure here never sends the turn. */
    protected abstract fun revalidate(budget: GrokBudget)

    /** Revalidate before submission; a failed check cannot trigger a silent fallback or send a turn. */
    fun prompt(text: String, timeout: Duration = promptTimeout): JsonObject {
        val budget = GrokBudget(timeout)
        revalidate(budget)
        check(grokAccount(rpc, initialized, budget).fingerprint == account.fingerprint) { "Grok account changed" }
        tools.requireAllowed(sessionId, budget.remaining())
        check(!stopping.get()) { "Grok turn stopped before submission" }
        return rpc.call("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }, budget.remaining(), onSubmitted = { submitted.set(true) })
    }

    /** Send cancellation without blocking the native UI; the prompt owner still awaits its terminal result. */
    fun cancel() {
        stopping.set(true)
        if (submitted.get()) rpc.cancel(sessionId) else rpc.close()
    }

    /** Bound a missing cancellation acknowledgement: end the connection and begin process-tree cleanup. */
    fun abort() = rpc.close()

    /** Close and reap before removing the launch's files. Invoke on a worker, never on the native UI thread. */
    override fun close() = launch.closeAfter(rpc)
}

/** What [openGrokSession] established, before any prompt. */
internal class GrokOpenedSession(
    val rpc: GrokAcp,
    val initialized: JsonObject,
    val tools: GrokToolEvidence,
    val account: GrokAccount,
    val sessionId: String,
    val model: String,
)

/**
 * Start [launch]'s agent and open one ACP session on it; no prompt or permission can run here. [prepare] runs
 * first, under the same cleanup: it verifies the launch (work also settles folder trust there) and returns the
 * deadline for the rest of startup. Any failure reaps the process and removes the launch before rethrowing; if
 * that cleanup fails, the [GrokCleanupUnconfirmed] carries the startup failure as suppressed.
 *
 * A null [model] selects the account's default; a null [expectedAccount] accepts the verified account. Grok
 * answering [resume] with a coded rejection is [GrokSessionLost]. [onPermission] null refuses every client request.
 */
internal fun openGrokSession(
    launch: GrokLaunchPolicy,
    tools: GrokToolEvidence,
    model: String?,
    expectedAccount: String?,
    resume: String?,
    onUpdate: (JsonObject) -> Unit,
    onPermission: ((JsonObject, (JsonObject?) -> Unit) -> Unit)? = null,
    prepare: () -> GrokBudget,
): GrokOpenedSession {
    var rpc: GrokAcp? = null
    try {
        val budget = prepare()
        val transport = launch.startAgent(GROK_ACCOUNT_METHODS + setOf("session/new", "session/load", "session/prompt"),
            onNotification = { method, params -> if (method == "session/update") { tools.observe(params); onUpdate(params) } },
            onRequest = onPermission?.let { answer -> { _: String, params: JsonObject, respond: (JsonObject?) -> Unit -> answer(params, respond) } })
        rpc = transport
        val initialized = transport.call("initialize", grokInitialize(), budget.remaining())
        launch.recheck()
        val account = launch.authenticate(transport, initialized, budget, expectedAccount)
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
        tools.requireAllowed(id, budget.remaining())
        launch.recheck()
        return GrokOpenedSession(transport, initialized, tools, account, id, selected)
    } catch (error: Exception) {
        try { launch.closeAfter(rpc) } catch (cleanup: Exception) { cleanup.addSuppressed(error); throw cleanup }
        throw error
    }
}

/** Grok answered `session/load` with a coded rejection. Nothing was prompted, so a fresh session is safe. */
internal class GrokSessionLost : IllegalStateException("Grok could not restore the saved session")

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
