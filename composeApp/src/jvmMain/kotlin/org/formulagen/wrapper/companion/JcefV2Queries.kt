package org.formulagen.wrapper.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.cef.callback.CefQueryCallback
import org.formulagen.wrapper.bridge.BridgeErrorCode
import org.formulagen.wrapper.bridge.BridgeRouter
import org.formulagen.wrapper.bridge.FrameFacts
import org.formulagen.wrapper.bridge.bridgeFailureEnvelope

/**
 * The version 2 half of the JCEF transport. [NativeBridge] checks the browser,
 * then calls [onQuery] for every query and keeps only the version 1 queries
 * this returns false for.
 *
 * The frame comes first: a query from a child frame or another origin is
 * answered before anything else reads it, and only its first
 * [UNTRUSTED_MAX_CHARS] characters are parsed, the same cap version 1 has.
 */
internal class JcefV2Queries(private val policy: NativeOriginPolicy, private val router: BridgeRouter) {
    private val lock = Any()

    /** The open watch query. Its callback carries events; its cancellation ends the context. */
    private var watch: Pair<Long, CefQueryCallback>? = null

    /** Watch queries the router has not answered yet. A cancel removes its id, so the late reply knows. */
    private val pendingWatches = mutableSetOf<Long>()

    /** Where the router sends events: the open watch query, if any. */
    fun events(): CefQueryCallback? = synchronized(lock) { watch?.second }

    /** Answers a version 2 query, or any query from an untrusted frame, and returns true. Returns false for a trusted version 1 query. */
    fun onQuery(mainFrame: Boolean, frameUrl: String, frameId: String, queryId: Long, request: String, persistent: Boolean, callback: CefQueryCallback): Boolean {
        if (!mainFrame || !policy.permits(true, frameUrl)) {
            val kind = if (request.length > UNTRUSTED_MAX_CHARS) JcefQueryKind.V1 else classifyJcefQuery(request, persistent)
            if (kind == JcefQueryKind.V1) callback.failure(403, "Native request unavailable for this frame")
            else callback.success(bridgeFailureEnvelope(BridgeErrorCode.FORBIDDEN))
            return true
        }
        val facts = FrameFacts(mainFrame = true, trustedOrigin = true, documentId = frameId)
        when (classifyJcefQuery(request, persistent)) {
            JcefQueryKind.V1 -> return false
            JcefQueryKind.V2_MISUSE -> callback.success(bridgeFailureEnvelope(BridgeErrorCode.INVALID))
            JcefQueryKind.V2_CALL -> router.handle(request, facts, callback::success)
            JcefQueryKind.V2_WATCH -> {
                synchronized(lock) { pendingWatches += queryId }
                router.handle(request, facts) { reply ->
                    val opened = "contextId" in Json.parseToJsonElement(reply).jsonObject
                    synchronized(lock) {
                        // A watch that JCEF cancelled before this reply has already ended its context.
                        if (pendingWatches.remove(queryId) && opened) watch = queryId to callback
                    }
                    callback.success(reply)
                }
            }
        }
        return true
    }

    /**
     * Returns true when [queryId] was a version 2 watch query, and ends the
     * context. For a watch the router has not answered yet, the end is queued
     * behind it on the router, so it closes the context that watch opens and
     * not one a later watch opens.
     */
    fun onQueryCanceled(queryId: Long): Boolean = synchronized(lock) {
        if (pendingWatches.remove(queryId)) {
            router.endContext()
            return true
        }
        if (watch?.first != queryId) return false
        watch = null
        router.endContext()
        true
    }

    fun close() = synchronized(lock) {
        watch = null
        pendingWatches.clear()
    }

    private companion object {
        /** Version 1's request cap. An untrusted frame never gets more parsed than this. */
        const val UNTRUSTED_MAX_CHARS = 4096
    }
}
