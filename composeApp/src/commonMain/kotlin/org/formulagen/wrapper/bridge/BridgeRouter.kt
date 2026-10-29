package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.coroutines.ContinuationInterceptor
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Moves strings for the router. One per WebView; each platform has its own. */
fun interface BridgeTransport {
    /** Sends a host → web event. The value is a complete JSON envelope, never script. */
    fun post(json: String)
}

/**
 * All `host-bridge.v2` rules in one place, with no platform imports.
 *
 * A transport hands over the raw request, the [FrameFacts] it read from the
 * platform, and a reply function. The router checks the request, runs the
 * capability, and replies once. A navigation ends the context and cancels
 * every call that is still running, so a reply never reaches a later document.
 *
 * State changes run one at a time on a serial view of [scope]'s dispatcher, so
 * transports can call from any thread. Capability work runs in [scope].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BridgeRouter(
    /** The product the page belongs to, such as `Hovel`. An `unsupported` error names it. */
    private val product: String,
    private val capabilities: HostCapabilities,
    /** The product's own capabilities, such as Hovel's companion. */
    products: List<CapabilityPack> = emptyList(),
    private val scope: CoroutineScope,
    private val transport: BridgeTransport,
    private val newContextId: () -> String = ::randomContextId,
    /**
     * Called with a method or event name when a capability fails, or emits an
     * event it did not declare. The page gets only the `failed` code, or nothing.
     */
    private val onFailure: (name: String, error: Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val serial = ((scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default)
        .limitedParallelism(1)
    private val bound: List<BoundCapability> = capabilities.bound + products.map { it.bound }
    private val handlers: Map<String, MethodHandler?> = buildHandlers()
    private var contextId: String? = null
    private var documentId: String? = null
    private val pending = mutableSetOf<Job>()
    private var subscriptions: List<AutoCloseable> =
        bound.mapNotNull { capability -> capability.observe?.invoke(sinkFor(capability)) }

    /**
     * The sink one capability's events go through. An event the capability did
     * not declare is not in `host.capabilities`, so it is dropped and reported
     * to [onFailure] instead of reaching the page.
     */
    private fun sinkFor(capability: BoundCapability): EventSink = object : EventSink {
        private val declared = capability.events.map { it.name }.toSet()

        override fun <D> emit(event: BridgeEvent<D>, data: D) {
            if (event.name in declared) this@BridgeRouter.emit(event, data)
            else onFailure(event.name, IllegalStateException("Event ${event.name} was not declared by its capability, so it was not sent"))
        }
    }

    /** Methods this host supports, in declaration order. `host.capabilities` returns this. */
    val supportedMethods: List<String> = handlers.filterValues { it != null }.keys.toList()

    /** Every method this host knows, supported or not. The parity test reads it. */
    internal val knownMethods: List<String> = handlers.keys.toList()

    /** Every event this host knows, supported or not. */
    internal val knownEvents: List<String> = bound.flatMap { capability -> capability.events.map { it.name } }

    /** Events this host can send. */
    val supportedEvents: List<String> = bound.filter { it.handlers != null }.flatMap { capability -> capability.events.map { it.name } }

    /**
     * Checks and answers one request. [reply] is called at most once, from any
     * thread. A call that a navigation or a new `watchContext` cancels gets no
     * reply, because no page is left to read it.
     */
    fun handle(raw: String, frame: FrameFacts, reply: (String) -> Unit) {
        scope.launch(serial) {
            val check = checkBridgeRequest(
                raw = raw,
                frame = frame,
                contextId = contextId,
                support = { method -> if (method !in handlers) null else if (handlers[method] == null) MethodSupport.UNSUPPORTED else MethodSupport.SUPPORTED },
                acceptsArgs = { method, args -> handlers[method]?.accepts(args) == true },
            )
            when (check) {
                is BridgeCheck.Reject ->
                    if (check.code == BridgeErrorCode.UNSUPPORTED && check.method != null) {
                        reply(BridgeEnvelopes.failure(check.code, unsupportedMessage(check.method)))
                    } else {
                        reply(bridgeFailureEnvelope(check.code))
                    }
                BridgeCheck.WatchContext -> {
                    endContextNow()
                    val id = newContextId()
                    contextId = id
                    documentId = frame.documentId
                    reply(BridgeEnvelopes.watchContextReply(id))
                }
                is BridgeCheck.Call ->
                    if (frame.documentId != documentId) reply(bridgeFailureEnvelope(BridgeErrorCode.EXPIRED))
                    else run(check, reply)
            }
        }
    }

    /** The document changed or went away. Ends the context and cancels running calls. */
    fun endContext() {
        scope.launch(serial) { endContextNow() }
    }

    /**
     * Ends the context [id] if it is still the open one, and does nothing once
     * a newer `watchContext` has replaced it. A transport calls this when one
     * watch goes away while a newer watch may already be open.
     */
    fun endContext(id: String) {
        scope.launch(serial) { if (contextId == id) endContextNow() }
    }

    /** Sends an event if a document has an open context. Events before `watchContext` are dropped. */
    fun <D> emit(event: BridgeEvent<D>, data: D) {
        val encoded = bridgeJson.encodeToJsonElement(event.data, data)
        scope.launch(serial) {
            if (contextId != null) transport.post(BridgeEnvelopes.event(event.name, encoded))
        }
    }

    override fun close() {
        subscriptions.forEach { it.close() }
        subscriptions = emptyList()
        endContext()
    }

    private fun run(call: BridgeCheck.Call, reply: (String) -> Unit) {
        val handler = handlers.getValue(call.method)!!
        val job = scope.launch {
            val answer = try {
                BridgeEnvelopes.success(withTimeout(handler.method.timeout) { handler.invoke(call.args) })
            } catch (error: TimeoutCancellationException) {
                onFailure(call.method, error)
                bridgeFailureEnvelope(BridgeErrorCode.FAILED)
            } catch (error: CancellationException) {
                throw error
            } catch (_: BridgeBusyException) {
                bridgeFailureEnvelope(BridgeErrorCode.BUSY)
            } catch (_: BridgeDeclinedException) {
                bridgeFailureEnvelope(BridgeErrorCode.DECLINED)
            } catch (error: BridgeUpdateRequiredException) {
                BridgeEnvelopes.failure(BridgeErrorCode.FAILED, checkNotNull(error.message))
            } catch (error: Throwable) {
                onFailure(call.method, error)
                bridgeFailureEnvelope(BridgeErrorCode.FAILED)
            }
            withContext(serial) {
                ensureActive()
                if (contextId == call.contextId) reply(answer)
            }
        }
        pending += job
        job.invokeOnCompletion { scope.launch(serial) { pending -= job } }
    }

    /** Names the product, the platform and the missing capability, so the page and its logs can say what is absent. */
    private fun unsupportedMessage(method: String): String {
        val capability = bound.first { capability -> capability.methods.any { it.name == method } }.name
        return "$product on ${capabilities.platform.wireName} has no $capability capability ($method)"
    }

    private fun endContextNow() {
        contextId = null
        documentId = null
        pending.forEach { it.cancel() }
        pending.clear()
    }

    /** The router's own methods, then each platform capability's, then each product pack's. */
    private fun buildHandlers(): Map<String, MethodHandler?> {
        val own = listOf(
            BridgeMethods.HOST_CAPABILITIES.answer { HostCapabilitiesResult(supportedMethods, supportedEvents) },
            BridgeMethods.HOST_INFO.answer { HostInfoResult(capabilities.platform, capabilities.appVersion) },
        ).map { it.method.name to it }
        val fromCapabilities = bound.flatMap { capability ->
            capability.methods.mapIndexed { index, method -> method.name to capability.handlers?.get(index) }
        }
        val all = own + fromCapabilities
        val duplicates = all.groupBy { it.first }.filterValues { it.size > 1 }.keys
        check(duplicates.isEmpty()) { "Methods declared twice: $duplicates" }
        return all.toMap()
    }
}

@OptIn(ExperimentalUuidApi::class)
internal fun randomContextId(): String = Uuid.random().toString()
