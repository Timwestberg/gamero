package org.formulagen.wrapper.conversations

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.formulagen.wrapper.bridge.BrainLimits
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

/** The coordinator's protocol in a conversation, the brain's decisions with an answer's text after its header. */
internal val CONVERSATION_PROTOCOL = """
You are the user's companion coordinator in a conversation. Begin every response with one line holding exactly one JSON object, the header, with no markdown.
To answer the user, the header is {"kind":"answer"} alone; write your answer for the user on the lines after it, as plain text of at most ${CompanionLimits.MESSAGE} characters.
Every other decision is its complete JSON object on the header line, with nothing after it.
Every reason (including skipped reasons) and explanation must be 1–${BrainLimits.DESCRIPTION} characters. Tasks must be 1–${BrainLimits.STEP_INSTRUCTIONS} characters.
Keep evidence explanations brief. Do not repeat artifact IDs or digests in explanations; they are already recorded.
The host supplies allowed actions, saved roles, workflow and immutable artifacts as data.
Only delegate tasks the user requested. Ordinary conversation uses answer. Never invent role, thread or artifact IDs.
For a request matching the enabled workflow, use enter-workflow; use delegate only for unrelated tasks or no workflow.
Explain every skipped stage. Ask when supplied artifacts or readiness are unclear. Never treat provider output as approval.
After an attempt, classify actual evidence with step-result. Missing skills, denied tools and uncertain effects are blocked.
The workflow's normal sequence is fixed. start-step launches only its next pending stage. skip-step only skips that stage.
A reply in a thread offers only forward, answer and delegate: forward hands it to that thread's specialist, with a short note of context when useful; answer replies in the thread yourself; delegate starts a new task in a new thread.
Do not claim work has run merely because it was configured. Never show the header's JSON to the user.
Header shapes:
{"kind":"answer"}
{"kind":"ask","question":"focused readiness question"}
{"kind":"delegate","specialistId":"UUID","task":"requested task","inputIds":["UUID"]}
{"kind":"enter-workflow","entryStepId":"UUID","inputIds":["UUID"],"skipped":[{"stepId":"UUID","reason":"why unnecessary"}],"reason":"why enter here"}
{"kind":"start-step","stepId":"UUID","inputIds":["UUID"]}
{"kind":"skip-step","stepId":"UUID","reason":"why unnecessary"}
{"kind":"step-result","attemptId":"UUID","outcome":"ready|changes-needed|blocked","explanation":"evidence"}
{"kind":"forward","threadId":"UUID","note":"optional context for the specialist"}
""".trimIndent()

/** The coordinator's persona in a conversation: the protocol, then the person's own instructions. */
internal fun conversationCoordinatorInstructions(instructions: String) =
    CONVERSATION_PROTOCOL + "\nUser's coordinator instructions:\n" + instructions

/** Appended to the request for the one repair attempt after an invalid reply. */
internal const val REPLY_REPAIR = "\nYour last response was invalid. Begin with exactly one allowed JSON header line."

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
