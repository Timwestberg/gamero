package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

    /** Called just before [reply] when this channel's `watchContext` opened the context. */
    fun opened() {}
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
    /** The product the page belongs to, such as `Hovel`. An `unsupported` error names it. */
    product: String,
    trustedUrl: String,
    capabilities: HostCapabilities,
    /** The product's own capabilities, such as Hovel's companion. */
    products: List<CapabilityPack> = emptyList(),
    /** Where capability work runs. The bridge makes a child scope and cancels it on [close]. */
    context: CoroutineContext = Dispatchers.Default,
    newContextId: () -> String = ::randomContextId,
    onFailure: (method: String, error: Throwable) -> Unit = { method, error ->
        log(LogLevel.Warn, "HostBridge", "Host bridge call $method failed", error)
    },
) : AutoCloseable {
    val origin = TrustedOrigin(trustedUrl)
    private val scope = CoroutineScope(context + SupervisorJob(context[Job]))
    private val document = AtomicLong(0)
    private val events = AtomicReference<BridgeChannel?>(null)

    internal val router = BridgeRouter(
        product = product,
        capabilities = capabilities,
        products = products,
        scope = scope,
        transport = { json -> events.load()?.event(json) },
        newContextId = newContextId,
        onFailure = onFailure,
    )

    /** Hands one request to the router. [channel] gets at most one reply, from any thread; a cancelled call gets none. */
    fun receive(raw: String, frame: PageFrame, channel: BridgeChannel) {
        val documentId = document.load()
        val facts = FrameFacts(frame.mainFrame, origin.matches(frame.url), documentId.toString())
        router.handle(raw, facts) { reply ->
            if (opensContext(reply) && document.load() == documentId) {
                events.store(channel)
                channel.opened()
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

    override fun close() {
        events.store(null)
        router.close()
        scope.cancel()
    }

    private fun opensContext(reply: String): Boolean =
        (Json.parseToJsonElement(reply) as? JsonObject)?.containsKey("contextId") == true
}
