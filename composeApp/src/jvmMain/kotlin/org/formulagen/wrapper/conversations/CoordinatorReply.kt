package org.formulagen.wrapper.conversations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.brain.CoordinatorDecision
import org.formulagen.wrapper.brain.DecisionKind
import org.formulagen.wrapper.brain.parseDecision

/*
 * The coordinator's reply in a conversation: a JSON header line, then for an answer a free-text body.
 * The header is validated as soon as its line completes; an answer's body then streams to every
 * surface as it is written. Every other decision is its full JSON on the header line, with no body,
 * and never streams. The decision shapes and their checks are the brain's ([parseDecision]).
 */

/**
 * [text] as one of the [allowed] decisions. An answer is the header `{"kind":"answer"}` with its
 * text in the body; a whole `{"kind":"answer","text":…}` header with no body is read the same way.
 * Any other decision is its header alone. Throws when the reply fits none of these.
 */
internal fun parseCoordinatorReply(text: String, allowed: Set<DecisionKind>): CoordinatorDecision {
    val (header, body) = splitHeader(text)
    if (isAnswerHeader(header)) {
        require(DecisionKind.ANSWER in allowed)
        val answer = body.trim()
        require(answer.isNotEmpty() && answer.length <= CompanionLimits.MESSAGE) { "An answer needs its text" }
        return CoordinatorDecision.Answer(answer)
    }
    require(body.isBlank()) { "Only an answer has a body" }
    return parseDecision(header.trim(), allowed)
}

/** The header line and everything after it. */
internal fun splitHeader(text: String): Pair<String, String> {
    val newline = text.indexOf('\n')
    return if (newline < 0) text to "" else text.substring(0, newline) to text.substring(newline + 1)
}

/** Whether [header] is exactly `{"kind":"answer"}`, the header whose body streams. */
internal fun isAnswerHeader(header: String): Boolean {
    val value = runCatching { brainJson.parseToJsonElement(header.trim()) }.getOrNull() as? JsonObject ?: return false
    return value.keys == setOf("kind") && value["kind"] == JsonPrimitive("answer")
}

/**
 * Turns a reply's partial texts (each the whole reply so far) into deltas of its streamable part:
 * after an answer header ([headed]), or the whole reply otherwise. Leading whitespace is held back.
 * When a partial no longer extends what was sent (a runtime restarted its text), streaming stops for
 * this reply; its `message.appended` checkpoint is the truth either way.
 */
internal class ReplyStream(private val headed: Boolean, private val allowAnswer: Boolean = true, private val send: (String) -> Unit) {
    private var mode = if (headed) Mode.WAITING else Mode.STREAMING
    private var start = 0
    private var sent = ""

    private enum class Mode { WAITING, STREAMING, SILENT }

    /** The reply so far. Sends what is new, if anything. */
    @Synchronized fun partial(text: String) {
        if (mode == Mode.WAITING) {
            val newline = text.indexOf('\n')
            if (newline < 0) return
            mode = if (allowAnswer && isAnswerHeader(text.substring(0, newline))) Mode.STREAMING else Mode.SILENT
            start = newline + 1
        }
        if (mode != Mode.STREAMING) return
        val body = text.substring(minOf(start, text.length)).trimStart()
        if (!body.startsWith(sent)) { mode = Mode.SILENT; return }
        val fresh = body.substring(sent.length).take(CompanionLimits.MESSAGE - sent.length)
        if (fresh.isEmpty()) return
        sent += fresh
        send(fresh)
    }

    /** Whether any text was streamed. */
    @get:Synchronized val streamed: Boolean get() = sent.isNotEmpty()
}
