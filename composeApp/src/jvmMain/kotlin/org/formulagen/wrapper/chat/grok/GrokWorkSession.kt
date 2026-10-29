package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*

/** One project turn and its process, including persisted-session restoration before live routing begins. */
internal class GrokWorkSession private constructor(
    private val launch: GrokWorkLaunch,
    private val rpc: GrokAcp,
    private val initialized: JsonObject,
    val account: GrokAccount,
    val sessionId: String,
) : AutoCloseable {
    private val submitted = AtomicBoolean()
    private val stopping = AtomicBoolean()

    /** Recheck the original configuration and account before accepting any project request. */
    fun prompt(text: String, timeout: Duration = 600.seconds): JsonObject {
        val budget = GrokBudget(timeout)
        if (!launch.verify(budget.remaining())) throw GrokWorkTrustDeclined()
        check(grokAccount(rpc, initialized, budget).fingerprint == account.fingerprint) { "Grok account changed" }
        check(!stopping.get())
        return rpc.call("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            putJsonArray("prompt") { add(buildJsonObject { put("type", "text"); put("text", text) }) }
        }, budget.remaining(), onSubmitted = { submitted.set(true) })
    }

    /** Cancel without blocking the native UI; the worker awaits a terminal response or forced closure. */
    fun cancel() { stopping.set(true); if (submitted.get()) rpc.cancel(sessionId) else rpc.close() }

    /** End a stalled connection and begin process-tree cleanup. */
    fun abort() = rpc.close()

    /** Complete cleanup before another turn can reuse the workspace/session. */
    override fun close() {
        val interrupted = Thread.interrupted()
        try { rpc.close(); rpc.awaitClosed(); launch.close() }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }

    companion object {
        /** No prompt or permission can run during startup. The caller supplies only the active turn's router. */
        fun open(executable: Path, environment: Map<String, String>, workspace: Path, instructions: String,
            model: String, expectedAccount: String, resume: String?, onUpdate: (JsonObject) -> Unit,
            confirmTrust: (selected: Path, root: Path) -> Boolean,
            onPermission: (JsonObject, (JsonObject?) -> Unit) -> Unit): GrokWorkSession {
            val launch = GrokWorkLaunch.create(executable, environment, workspace, instructions)
            var rpc: GrokAcp? = null
            try {
                if (!launch.verify(20.seconds)) {
                    val root = launch.trustFolder
                    if (launch.trustTooBroad) throw GrokWorkTrustTooBroad()
                    if (!confirmTrust(launch.workspace, root)) throw GrokWorkTrustDeclined()
                    launch.trust(root, 20.seconds)
                }
                val budget = GrokBudget(30.seconds)
                val tools = GrokToolEvidence(setOf("read_file", "list_dir", "grep", "search_replace", "run_terminal_cmd"))
                val transport = GrokAcp(launch.agentBuilder(), GROK_ACCOUNT_METHODS + setOf("session/new", "session/load", "session/prompt"),
                    onNotification = { method, params -> if (method == "session/update") { tools.observe(params); onUpdate(params) } },
                    onRequest = { _, params, respond -> onPermission(params, respond) }, allowReloadResponses = true)
                rpc = transport
                val initialized = transport.call("initialize", grokInitialize(), budget.remaining())
                val account = grokAccount(transport, initialized, budget)
                check(account.fingerprint == expectedAccount && account.models.any { it.modelId == model })
                if (resume != null) check(validGrokSessionId(resume) && grokHandshake(initialized).loadSession)
                val created = transport.call(if (resume == null) "session/new" else "session/load", buildJsonObject {
                    if (resume != null) put("sessionId", resume)
                    put("cwd", launch.workspace.toString()); putJsonArray("mcpServers") {}
                    putJsonObject("_meta") { put("modelId", model) }
                }, budget.remaining())
                val id = resume ?: (created["sessionId"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?.takeIf(::validGrokSessionId) ?: error("Invalid Grok session")
                check(grokModels(created["models"] as? JsonObject ?: error("Missing session model")).first == model)
                tools.requireAllowed(id, budget.remaining())
                launch.checkConfig()
                return GrokWorkSession(launch, transport, initialized, account, id)
            } catch (error: Exception) {
                val interrupted = Thread.interrupted()
                try { rpc?.close(); rpc?.awaitClosed(); launch.close() }
                finally { if (interrupted) Thread.currentThread().interrupt() }
                throw error
            }
        }
    }
}
