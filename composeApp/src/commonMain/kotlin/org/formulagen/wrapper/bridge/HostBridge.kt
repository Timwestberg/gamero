package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
 * frame gets a new document. Each `post` it makes is a JSON string, never
 * script. `TransportContractTest` checks every adapter against these rules.
 */
@OptIn(ExperimentalAtomicApi::class)
class HostBridge(
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
) : AutoCloseable {
    val origin = TrustedOrigin(trustedUrl)
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private val document = AtomicLong(0)
    /** The open context and the channel that carries its events. */
    private val events = AtomicReference<OpenContext?>(null)

    internal val router = BridgeRouter(
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
                if (channel.opened(contextId)) events.store(OpenContext(contextId, channel)) else router.endContext(contextId)
            }
            channel.reply(reply)
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

    private fun openedContext(reply: String): String? =
        ((Json.parseToJsonElement(reply) as? JsonObject)?.get("contextId") as? JsonPrimitive)?.content

    private class OpenContext(val id: String, val channel: BridgeChannel)
}
