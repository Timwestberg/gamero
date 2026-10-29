package org.formulagen.wrapper.chat.grok

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.*
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.ApprovalDecision
import org.formulagen.wrapper.approvals.ApprovalRelay
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.WorkScope
import org.formulagen.wrapper.providers.grok.validGrokSessionId

/** One ACP permission exchange; opaque IDs are returned unchanged and never treated as labels. */
internal class GrokPermission private constructor(
    val toolCallId: String,
    val tool: JsonObject,
    private val allowOnce: String,
    private val rejectOnce: String,
) {
    /** The conversation-wide decision is deliberately denied; Grok owns persistent grant scope. */
    fun answer(decision: ApprovalDecision): JsonObject = buildJsonObject {
        putJsonObject("outcome") {
            put("outcome", "selected")
            put("optionId", if (decision == ApprovalDecision.ALLOW_ONCE) allowOnce else rejectOnce)
        }
    }

    companion object {
        /** Accept only a correlated, bounded request with one unambiguous allow/deny-once pair. */
        fun parse(params: JsonObject, sessionId: String): GrokPermission? {
            if (params["sessionId"] != JsonPrimitive(sessionId) || !validGrokSessionId(sessionId)) return null
            val tool = params["toolCall"] as? JsonObject ?: return null
            val toolId = permissionText(tool["toolCallId"], PERMISSION_ID_LIMIT) ?: return null
            val options = params["options"] as? JsonArray ?: return null
            if (options.isEmpty() || options.size > PERMISSION_LIMIT) return null
            val ids = HashSet<String>()
            val kinds = mutableMapOf<String, MutableList<String>>()
            for (option in options) {
                val value = option as? JsonObject ?: return null
                val id = permissionText(value["optionId"], PERMISSION_ID_LIMIT) ?: return null
                val kind = permissionText(value["kind"], 64) ?: return null
                permissionText(value["name"], 512) ?: return null
                if (!ids.add(id)) return null
                kinds.getOrPut(kind) { mutableListOf() }.add(id)
            }
            return GrokPermission(toolId, tool,
                kinds["allow_once"]?.singleOrNull() ?: return null,
                kinds["reject_once"]?.singleOrNull() ?: return null)
        }
    }
}

/** ACP's cancellation response, also used when no safe permission choice can be represented. */
internal fun cancelledGrokPermission() = buildJsonObject {
    putJsonObject("outcome") { put("outcome", "cancelled") }
}

/**
 * One work turn's permissions, using the shared native broker off the protocol reader thread.
 * [describeTool] must decode a verified tool payload; null denies without showing a dialog.
 * The runtime owner closes this turn on Stop, completion, account change or connection loss.
 */
internal class GrokApprovals(
    private val sessionId: String,
    private val work: WorkScope,
    private val events: TurnEvents,
    broker: ApprovalBroker,
    private val describeTool: (JsonObject) -> ApprovalSubject? = { grokApprovalSubject(it, work.folder) },
) : AutoCloseable {
    private val denied = AtomicBoolean()
    /** Grok may terminate the prompt with cancelled after a rejected permission, without final text. */
    val wasDenied: Boolean get() = denied.get()
    private val relay = ApprovalRelay(broker)
    private val pending = LinkedHashMap<String, Pending>()
    private var closed = false
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, ArrayBlockingQueue<Runnable>(PERMISSION_LIMIT),
        Thread.ofPlatform().daemon().name("hovel-grok-approval").factory())

    /** One reply per request, even when Stop races the native dialog's eventual answer. */
    private class Pending(val entry: ApprovalRelay.Pending, private val respond: (JsonObject) -> Unit) {
        private val answered = AtomicBoolean()

        /** A stale broker answer cannot replace a cancellation already sent to Grok. */
        fun reply(result: JsonObject) { if (answered.compareAndSet(false, true)) runCatching { respond(result) } }
    }

    /** Register on the reader thread; only native, structured subjects can reach a dialog. */
    fun receive(params: JsonObject, respond: (JsonObject) -> Unit) {
        if (synchronized(pending) { closed }) { respond(cancelledGrokPermission()); return }
        val permission = GrokPermission.parse(params, sessionId)
        if (permission == null) { respond(cancelledGrokPermission()); return }
        val subject = runCatching { describeTool(permission.tool) }.getOrNull()
        if (subject == null) { denied.set(true); respond(permission.answer(ApprovalDecision.DENY)); return }
        val request = ApprovalRequest(work.companion, work.member, work.folder, subject, sessionLabel = null)
        val current = synchronized(pending) {
            if (closed || pending.size >= PERMISSION_LIMIT || permission.toolCallId in pending) null
            else Pending(relay.register(permission.toolCallId, request, events), respond).also {
                pending[permission.toolCallId] = it
            }
        }
        if (current == null) { respond(cancelledGrokPermission()); return }
        try {
            executor.execute {
                try {
                    val decision = relay.ask(permission.toolCallId, current.entry)
                    if (!current.entry.cancel.isCancelled && decision != ApprovalDecision.ALLOW_ONCE) denied.set(true)
                    current.reply(if (current.entry.cancel.isCancelled) cancelledGrokPermission() else permission.answer(decision))
                } catch (_: Exception) { current.reply(cancelledGrokPermission()) }
                finally { synchronized(pending) { pending.remove(permission.toolCallId, current) } }
            }
        } catch (_: Exception) {
            relay.cancel(permission.toolCallId)
            current.reply(cancelledGrokPermission())
            synchronized(pending) { pending.remove(permission.toolCallId, current) }
        }
    }

    /** Invalidate queued and visible approvals immediately; a blocked broker cannot delay Stop. */
    override fun close() {
        val entries = synchronized(pending) {
            if (closed) return
            closed = true
            pending.values.toList().also { pending.clear() }
        }
        entries.forEach { runCatching { it.entry.cancel.cancel() }; it.reply(cancelledGrokPermission()) }
        executor.shutdownNow()
    }
}

/** Permission identifiers/labels are bounded strings; control characters cannot reach a dialog. */
private fun permissionText(value: JsonElement?, limit: Int): String? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content
        ?.takeIf { it.isNotEmpty() && it.length <= limit && it.none(Char::isISOControl) }

private const val PERMISSION_LIMIT = 16
private const val PERMISSION_ID_LIMIT = 256
