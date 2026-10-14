package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val capabilities: HostCapabilities,
    private val scope: CoroutineScope,
    private val transport: BridgeTransport,
    private val newContextId: () -> String = ::randomContextId,
    /** Called when a capability fails. The page gets only the `failed` code. */
    private val onFailure: (method: String, error: Throwable) -> Unit = { _, _ -> },
) : AutoCloseable {
    private val serial = ((scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher) ?: Dispatchers.Default)
        .limitedParallelism(1)
    private val handlers: Map<String, Handler?> = buildHandlers()
    private var contextId: String? = null
    private var documentId: String? = null
    private val pending = mutableSetOf<Job>()
    private var lifecycleSubscription: AutoCloseable? = null

    init {
        (capabilities.lifecycle as? Capability.Supported)?.let { supported ->
            lifecycleSubscription = supported.impl.observe(
                onPause = { emit(BridgeEvents.LIFECYCLE_PAUSE, JsonObject(emptyMap())) },
                onResume = { emit(BridgeEvents.LIFECYCLE_RESUME, JsonObject(emptyMap())) },
            )
        }
    }

    /** Methods this host supports, in registry order. `host.capabilities` returns this. */
    val supportedMethods: List<String> = BridgeMethods.ALL.map { it.name }.filter { handlers[it] != null }

    /** Events this host can send. */
    val supportedEvents: List<String> =
        if (capabilities.lifecycle is Capability.Supported) BridgeEvents.ALL else emptyList()

    /** Checks and answers one request. [reply] is called exactly once, from any thread. */
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
                is BridgeCheck.Reject -> reply(BridgeEnvelopes.failure(check.code, messageFor(check.code)))
                BridgeCheck.WatchContext -> {
                    endContextNow()
                    val id = newContextId()
                    contextId = id
                    documentId = frame.documentId
                    reply(BridgeEnvelopes.watchContextReply(id))
                }
                is BridgeCheck.Call ->
                    if (frame.documentId != documentId) reply(BridgeEnvelopes.failure(BridgeErrorCode.EXPIRED, messageFor(BridgeErrorCode.EXPIRED)))
                    else run(check, reply)
            }
        }
    }

    /** The document changed or went away. Ends the context and cancels running calls. */
    fun endContext() {
        scope.launch(serial) { endContextNow() }
    }

    /** Sends an event if a document has an open context. Events before `watchContext` are dropped. */
    fun emit(event: String, data: JsonElement) {
        scope.launch(serial) {
            if (contextId != null) transport.post(BridgeEnvelopes.event(event, data))
        }
    }

    override fun close() {
        lifecycleSubscription?.close()
        lifecycleSubscription = null
        endContext()
    }

    private fun run(call: BridgeCheck.Call, reply: (String) -> Unit) {
        val handler = handlers.getValue(call.method)!!
        val job = scope.launch {
            val answer = try {
                BridgeEnvelopes.success(handler.invoke(call.args))
            } catch (error: CancellationException) {
                throw error
            } catch (_: BridgeBusyException) {
                BridgeEnvelopes.failure(BridgeErrorCode.BUSY, messageFor(BridgeErrorCode.BUSY))
            } catch (error: Throwable) {
                onFailure(call.method, error)
                BridgeEnvelopes.failure(BridgeErrorCode.FAILED, messageFor(BridgeErrorCode.FAILED))
            }
            withContext(serial) {
                ensureActive()
                if (contextId == call.contextId) reply(answer)
            }
        }
        pending += job
        job.invokeOnCompletion { scope.launch(serial) { pending -= job } }
    }

    private fun endContextNow() {
        contextId = null
        documentId = null
        pending.forEach { it.cancel() }
        pending.clear()
    }

    private class Handler(val accepts: (JsonObject) -> Boolean, val invoke: suspend (JsonObject) -> JsonElement)

    private fun <A, R> bind(method: BridgeMethod<A, R>, body: suspend (A) -> R): Pair<String, Handler> =
        method.name to Handler(
            accepts = { args -> method.decodeArgs(args) != null },
            invoke = { args -> method.encodeResult(body(method.decodeArgs(args)!!)) },
        )

    private fun <T> Capability<T>.bindAll(vararg methods: BridgeMethod<*, *>, binder: (T) -> List<Pair<String, Handler>>): List<Pair<String, Handler?>> =
        when (this) {
            is Capability.Supported -> binder(impl)
            Capability.Unsupported -> methods.map { it.name to null }
        }

    private fun buildHandlers(): Map<String, Handler?> {
        val bound = listOf(
            bind(BridgeMethods.HOST_CAPABILITIES) { HostCapabilitiesResult(supportedMethods, supportedEvents) },
            bind(BridgeMethods.HOST_INFO) { HostInfoResult(capabilities.platform, capabilities.appVersion) },
        ) + capabilities.links.bindAll(BridgeMethods.LINK_OPEN) { links ->
            listOf(bind(BridgeMethods.LINK_OPEN) { args -> links.open(args.url); NoResult })
        } + capabilities.haptics.bindAll(BridgeMethods.HAPTIC_PLAY) { haptics ->
            listOf(bind(BridgeMethods.HAPTIC_PLAY) { args -> haptics.play(args.effect); NoResult })
        } + capabilities.companion.bindAll(
            BridgeMethods.COMPANION_CAPABILITIES, BridgeMethods.COMPANION_PREPARE, BridgeMethods.COMPANION_ACTIVATE,
        ) { companion ->
            listOf(
                bind(BridgeMethods.COMPANION_CAPABILITIES) { CompanionCapabilitiesResult() },
                bind(BridgeMethods.COMPANION_PREPARE) { args -> companion.prepareConnection(args.ownerId) },
                bind(BridgeMethods.COMPANION_ACTIVATE) { args -> companion.activateConnection(args.ownerId, args.hostId) },
            )
        }
        val map = bound.toMap()
        val missing = BridgeMethods.ALL.map { it.name } - map.keys
        check(missing.isEmpty()) { "Methods without a binding: $missing" }
        return map
    }

    private fun messageFor(code: BridgeErrorCode): String = when (code) {
        BridgeErrorCode.FORBIDDEN -> "This frame cannot call the host"
        BridgeErrorCode.INVALID -> "The request does not match host-bridge.v2"
        BridgeErrorCode.EXPIRED -> "The bridge context expired; open a new one"
        BridgeErrorCode.UNKNOWN_METHOD -> "No host defines this method"
        BridgeErrorCode.UNSUPPORTED -> "This host does not support this method"
        BridgeErrorCode.BUSY -> "Another call to this capability is running"
        BridgeErrorCode.FAILED -> "The host could not complete the call"
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomContextId(): String = Uuid.random().toString()
