package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.UUID

/** Spoken text the pet can show. Longer Codex output stays in [full]. */
internal const val EMBER_SAY_LIMIT = 240

internal const val EMBER_UNSUPPORTED_REASON = "Sending from Ember is not wired yet"

internal val EMBER_ACTIVE_STATES = setOf("queued", "claimed", "running", "cloud-accepted")
internal val EMBER_FAILURE_STATES = setOf("failed", "reconciliation-required")
internal val EMBER_TERMINAL_STATES = EMBER_FAILURE_STATES + "succeeded"
private val EMBER_TASK_STATES = EMBER_ACTIVE_STATES + EMBER_TERMINAL_STATES

data class EmberTaskProgress(val id: String, val state: String, val text: String? = null)

/** One reading of the companion: whether it is up, Codex login, and the tasks the server last listed. */
data class EmberCompanionSnapshot(
    val online: Boolean,
    val providerState: String? = null,
    val tasks: List<EmberTaskProgress> = emptyList(),
)

internal data class EmberSay(val text: String, val taskId: String? = null, val full: String? = null)

internal data class EmberView(val state: String, val say: EmberSay? = null)

internal data class EmberSummary(val text: String, val full: String?)

data class EmberMessageAck(val id: String, val status: String, val reason: String? = null)

/**
 * Accepts a line the pet typed. A later change can turn that line into a Hovel task.
 * This interface does not create tasks and does not perform owner auth.
 */
fun interface EmberMessageSink {
    fun onMessage(id: String, text: String): EmberMessageAck
}

class UnsupportedEmberMessageSink : EmberMessageSink {
    override fun onMessage(id: String, text: String) =
        EmberMessageAck(id, "unsupported", EMBER_UNSUPPORTED_REASON)
}

/**
 * Task activity wins over login. Login applies only when no task is active or terminal.
 * Offline wins over everything, because the task list is no longer live.
 */
internal fun emberView(snapshot: EmberCompanionSnapshot): EmberView {
    if (!snapshot.online) return EmberView("blocked")
    if (snapshot.tasks.any { it.state in EMBER_ACTIVE_STATES }) return EmberView("running")
    snapshot.tasks.firstOrNull { it.state in EMBER_FAILURE_STATES }?.let { failed ->
        val fallback = if (failed.state == "reconciliation-required") {
            "This task needs reconciliation on this computer."
        } else {
            "Codex task failed."
        }
        return EmberView("blocked", emberSpeech(failed, fallback))
    }
    snapshot.tasks.firstOrNull { it.state == "succeeded" }?.let { succeeded ->
        return EmberView("ready", emberSpeech(succeeded, "Codex finished."))
    }
    return when (snapshot.providerState) {
        "auth-required" -> EmberView("needs_input")
        "unavailable" -> EmberView("blocked")
        else -> EmberView("none")
    }
}

private fun emberSpeech(task: EmberTaskProgress, blank: String): EmberSay {
    val summary = emberSummary(task.text.orEmpty())
    return if (summary.text.isEmpty()) EmberSay(blank, task.id, null) else EmberSay(summary.text, task.id, summary.full)
}

/** Shorten [raw] to at most [EMBER_SAY_LIMIT] characters. [EmberSummary.full] is set only when text was cut. */
internal fun emberSummary(raw: String): EmberSummary {
    val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
    if (normalized.length <= EMBER_SAY_LIMIT) return EmberSummary(normalized, null)
    val paragraph = normalized.substringBefore("\n\n").trim()
    if (paragraph.isNotEmpty() && paragraph.length <= EMBER_SAY_LIMIT && paragraph.length < normalized.length) {
        return EmberSummary(paragraph, normalized)
    }
    val ellipsis = "…"
    val budget = EMBER_SAY_LIMIT - ellipsis.length
    val window = normalized.take(budget)
    val breakAt = maxOf(window.lastIndexOf('\n'), window.lastIndexOf(' ')).takeIf { it >= budget / 2 } ?: budget
    val shortened = window.take(breakAt).trimEnd().ifEmpty { window }.take(budget) + ellipsis
    return EmberSummary(shortened.take(EMBER_SAY_LIMIT), normalized)
}

/** Accept only `/?token=<expected>`. Any other path, name, or extra parameter is rejected. */
internal fun emberTokenAccepted(resourceDescriptor: String, expected: String): Boolean {
    if (expected.isEmpty()) return false
    val request = resourceDescriptor.substringBefore('#').trim()
    val path = request.substringBefore('?')
    if (path != "/" && path.isNotEmpty()) return false
    if (!request.contains('?')) return false
    val pairs = request.substringAfter('?').split('&').filter { it.isNotEmpty() }
    if (pairs.size != 1 || !pairs[0].substringBefore('=').let(::decodeQuery).equals("token")) return false
    val value = decodeQuery(pairs[0].substringAfter('=', ""))
    return MessageDigest.isEqual(value.toByteArray(Charsets.UTF_8), expected.toByteArray(Charsets.UTF_8))
}

private fun decodeQuery(value: String): String = try {
    URLDecoder.decode(value, Charsets.UTF_8)
} catch (_: IllegalArgumentException) {
    ""
}

internal fun emberStateJson(state: String): String = buildJsonObject {
    put("type", "state")
    put("state", state)
}.toString()

internal fun emberSayJson(say: EmberSay): String = buildJsonObject {
    put("type", "say")
    put("text", say.text)
    if (say.taskId != null) put("taskId", say.taskId)
    if (say.full != null) put("full", say.full)
}.toString()

/**
 * Reply to a pet `message`, or null when the frame should be ignored.
 * [log] receives a fixed sentence and never the payload: the payload is untrusted text.
 */
internal fun emberHandleClientMessage(raw: String, sink: EmberMessageSink, log: (String) -> Unit): String? {
    val obj = try {
        kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonObject
    } catch (_: Exception) {
        null
    }
    if (obj == null) {
        log("Ignored a malformed Ember pet message")
        return null
    }
    val type = (obj["type"] as? JsonPrimitive)?.contentOrNull
    if (type == null) {
        log("Ignored a malformed Ember pet message")
        return null
    }
    if (type != "message") return null
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull
    val textElement = obj["text"]
    if (id == null || textElement !is JsonPrimitive || !textElement.isString || !canonicalUuid(id)) {
        log("Ignored a malformed Ember pet message")
        return null
    }
    val ack = try {
        sink.onMessage(id, textElement.content)
    } catch (_: Exception) {
        log("Ignored an Ember pet message the companion could not accept")
        return null
    }
    return buildJsonObject {
        put("type", "message_ack")
        put("id", id)
        put("status", ack.status)
        if (ack.reason != null) put("reason", ack.reason)
    }.toString()
}

private fun canonicalUuid(value: String) = runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

internal fun emberTasksFromHeartbeat(data: JsonObject): List<EmberTaskProgress> {
    val tasks = data["tasks"] as? JsonArray ?: return emptyList()
    return tasks.mapNotNull { element ->
        val task = element as? JsonObject ?: return@mapNotNull null
        val id = ((task["request"] as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (!canonicalUuid(id)) return@mapNotNull null
        val progress = task["progress"] as? JsonObject ?: return@mapNotNull null
        val state = (progress["state"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
        if (state !in EMBER_TASK_STATES) return@mapNotNull null
        val text = when (state) {
            "succeeded" -> jsonText(progress["result"])
            "failed", "reconciliation-required" -> jsonText(progress["error"])
            else -> null
        }
        EmberTaskProgress(id, state, text)
    }
}

private fun jsonText(element: JsonElement?): String? {
    val primitive = element as? JsonPrimitive ?: return null
    if (!primitive.isString) return null
    return primitive.content
}

internal data class EmberTaskMerge(val tasks: List<EmberTaskProgress>, val sticky: EmberTaskProgress?)

/**
 * A heartbeat that started before a local outcome returns can still say "running" or omit the task.
 * Keep the local terminal text until a heartbeat that started afterwards agrees or drops it.
 * The longer text wins so a server preview does not replace the local Codex result.
 */
internal fun mergeEmberTasks(
    serverTasks: List<EmberTaskProgress>,
    sticky: EmberTaskProgress?,
    heartbeatStale: Boolean,
): EmberTaskMerge {
    if (sticky == null) return EmberTaskMerge(serverTasks, null)
    val index = serverTasks.indexOfFirst { it.id == sticky.id }
    if (index >= 0) {
        val server = serverTasks[index]
        if (server.state == sticky.state) {
            val tasks = serverTasks.toMutableList()
            tasks[index] = server.copy(text = preferText(sticky.text, server.text))
            return EmberTaskMerge(tasks, null)
        }
        if (server.state in EMBER_ACTIVE_STATES && sticky.state in EMBER_TERMINAL_STATES) {
            val tasks = serverTasks.toMutableList()
            tasks[index] = sticky
            return EmberTaskMerge(tasks, sticky)
        }
        return EmberTaskMerge(serverTasks, null)
    }
    return if (heartbeatStale) EmberTaskMerge(listOf(sticky) + serverTasks, sticky) else EmberTaskMerge(serverTasks, null)
}

private fun preferText(local: String?, server: String?): String? = when {
    local == null -> server
    server == null -> local
    server.length > local.length -> server
    else -> local
}

/** The child must not inherit an API-key billing path. [socketUrl] is the bridge's own per-launch URL. */
internal fun applyEmberPetEnvironment(env: MutableMap<String, String>, socketUrl: String) {
    env.keys.removeIf { it.startsWith("OPENAI_") || it == "CODEX_API_KEY" }
    env["EMBER_PET_WS"] = socketUrl
}
