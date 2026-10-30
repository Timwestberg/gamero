package org.formulagen.wrapper.chat.grok

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import org.formulagen.wrapper.chat.ChatFailure
import org.formulagen.wrapper.chat.NativeConversation
import org.formulagen.wrapper.chat.Persona
import org.formulagen.wrapper.chat.TurnEvents
import org.formulagen.wrapper.chat.TurnRequest

/** The companion persona of the talk runtime's fixture and live turns. */
internal val talkPersona = Persona.companion("Ember")

/** One talk turn for the fixture companion, resuming [conversation] when given. */
internal fun talkRequest(conversation: NativeConversation? = null) = TurnRequest(ACCOUNT, "20000000-0000-4000-8000-000000000002",
    talkPersona, null, conversation, "Hello")

/** Records one talk turn's events in order, with its handle, reply and failure. */
internal class TalkEvents : TurnEvents {
    val seen = CopyOnWriteArrayList<String>()
    val done = CompletableFuture<Unit>()
    var handle: String? = null
    var reply: String? = null
    var failure: ChatFailure? = null
    override fun opened(conversation: String) { handle = conversation; seen += "opened" }
    override fun accepted() { seen += "accepted" }
    override fun partial(text: String) { reply = text; seen += "partial" }
    override fun completed(text: String, conversation: String) { reply = text; handle = conversation; seen += "completed"; done.complete(Unit) }
    override fun failed(failure: ChatFailure) { this.failure = failure; seen += "failed"; done.complete(Unit) }
    fun await(seconds: Long = 10) { done.get(seconds, TimeUnit.SECONDS) }
}

/** The signed-in account the Grok tests run turns for. */
internal const val ACCOUNT = "50000000-0000-4000-8000-000000000005"

/** A runtime's retention when no conversation holds a binding: pruning keeps only what is active. */
internal val nothingRetained: () -> Set<String>? = { emptySet() }
