package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.formulagen.wrapper.SmokeMarkers
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.coroutines.CoroutineContext

/** What a transport read from the platform about the frame a message came from. Never from the page. */
data class PageFrame(val mainFrame: Boolean, val url: String)

/**
 * Where the answer to one request goes. When a `watchContext` request opens a
 * context, its channel also carries that context's events.
 */
interface BridgeChannel {
    fun reply(json: String)
    fun event(json: String)

    /**
     * Called just before [reply] when this channel's `watchContext` opened the
     * context [contextId]. Returns false when the transport no longer wants it,
     * such as a watch query cancelled before its reply; the bridge then ends
     * that context, and only that one.
     */
    fun opened(contextId: String): Boolean = true
}

/**
 * The shared half of every transport: the router, its scope, the trusted
 * origin, the document counter, the event channel and the failure log.
 *
 * A platform adapter only talks to its WebView. It calls [receive] for each
 * message with the frame facts it read, and [documentChanged] when the main
 * frame gets a new document. A transport whose only signal is the end of one
 * watch (JCEF) calls [endContext] with that watch's context id instead, so a
 * newer watch keeps its context. Each `post` it makes is a JSON string, never
 * script. `TransportContractTest` checks every adapter against these rules.
 */
@OptIn(ExperimentalAtomicApi::class)
class HostBridge(
    /** The product the page belongs to, such as `Hovel`. An `unsupported` error names it. */
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    /** The product's own capabilities, such as Hovel's companion. */
    products: List<CapabilityPack> = emptyList(),
    /** Where capability work runs. The bridge makes a child scope and cancels it on [close]. */
    context: CoroutineContext = Dispatchers.Default,
    newContextId: () -> String = ::randomContextId,
    /** Gets the method or event name when a capability fails or emits an undeclared event. */
    onFailure: (name: String, error: Throwable) -> Unit = { name, error ->
        log(LogLevel.Warn, "HostBridge", "Host bridge $name failed", error)
    },
    /**
     * Gets the first answer each document of the trusted main frame receives: the request's
     * method (null when it had none) and `ok` or the error code. By default it logs [SmokeMarkers.bridgeAnswered].
     */
    private val onFirstAnswer: (method: String?, outcome: String) -> Unit = { method, outcome ->
        log(LogLevel.Info, "HostBridge", SmokeMarkers.bridgeAnswered(capabilities.platform, product, method, outcome))
    },
) : AutoCloseable {
    val origin = TrustedOrigin(trustedUrl)
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private val document = AtomicLong(0)
    /** The last document that got an answer, so [onFirstAnswer] runs once per document. */
    private val answered = AtomicLong(-1)
    /** The open context and the channel that carries its events. */
    private val events = AtomicReference<OpenContext?>(null)

    internal val router = BridgeRouter(
        product = product,
        capabilities = capabilities,
        products = products,
        scope = scope,
        transport = { json -> events.load()?.channel?.event(json) },
        newContextId = newContextId,
        onFailure = onFailure,
    )

    /** Hands one request to the router. [channel] gets at most one reply, from any thread; a cancelled call gets none. */
    fun receive(raw: String, frame: PageFrame, channel: BridgeChannel) {
        val documentId = document.load()
        val facts = FrameFacts(frame.mainFrame, origin.matches(frame.url), documentId.toString())
        router.handle(raw, facts) { reply ->
            val contextId = openedContext(reply)
            if (contextId != null && document.load() == documentId) {
                // This context replaced any older one, so an older channel gets no more events either way.
                if (channel.opened(contextId)) {
                    events.store(OpenContext(contextId, channel))
                } else {
                    events.store(null)
                    router.endContext(contextId)
                }
            }
            channel.reply(reply)
            // Only the trusted main frame's current document counts, so a refused frame can't stand in for it.
            val last = answered.load()
            if (facts.mainFrame && facts.trustedOrigin && document.load() == documentId &&
                last != documentId && answered.compareAndSet(last, documentId)
            ) onFirstAnswer(methodOf(raw), outcomeOf(reply))
        }
    }

    /** The main frame has a new document, or none. Ends the context and cancels its running calls. */
    fun documentChanged() {
        document.incrementAndFetch()
        events.store(null)
        router.endContext()
    }

    /**
     * Ends the context [contextId] if it is still the open one. A newer
     * `watchContext` that replaced it stays open. Use this when one watch goes
     * away, and [documentChanged] when the whole document does.
     */
    fun endContext(contextId: String) {
        events.load()?.takeIf { it.id == contextId }?.let { events.compareAndSet(it, null) }
        router.endContext(contextId)
    }

    override fun close() {
        events.store(null)
        router.close()
        scope.cancel()
    }

    private fun methodOf(raw: String): String? =
        runCatching { ((Json.parseToJsonElement(raw) as? JsonObject)?.get("method") as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()

    /** `ok` for a result or an opened context, else the error code. */
    private fun outcomeOf(reply: String): String {
        val envelope = Json.parseToJsonElement(reply) as? JsonObject ?: return "invalid"
        if (envelope["ok"] == JsonPrimitive(false)) return ((envelope["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content ?: "failed"
        return "ok"
    }

    private fun openedContext(reply: String): String? =
        ((Json.parseToJsonElement(reply) as? JsonObject)?.get("contextId") as? JsonPrimitive)?.content

    private class OpenContext(val id: String, val channel: BridgeChannel)
}
