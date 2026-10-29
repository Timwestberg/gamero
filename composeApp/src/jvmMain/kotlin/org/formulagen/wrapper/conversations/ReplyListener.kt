package org.formulagen.wrapper.conversations

/**
 * What this Mac hears of one claimed message as it runs: the coordinator's replies in the main
 * conversation, as they stream and once written, whether a delegated task has started, and the end
 * of the run. Voice speaks the replies ([VoiceConversation]); threads are never heard. Calls come from
 * the run's threads and must not block.
 */
internal interface ReplyListener {
    /** One more piece of reply [replyId], in order. A repaired reply streams under a new id. */
    fun delta(replyId: String, text: String) {}

    /** Coordinator message [replyId] in the main conversation is written, with its whole [text]; it may never have streamed. */
    fun appended(replyId: String, text: String) {}

    /** The run has delegated: a thread is queued or working for it. From here on it is the agents' work, not only a reply. */
    fun threadStarted() {}

    /** The message settled, or will settle from the journal: nothing more comes for it. */
    fun ended() {}
}

/**
 * Who listens to a claimed message's replies on this Mac, asked once its claim lands and before any
 * provider turn. [stop] stops that message as the person's Stop does; it may be called at once, and
 * the message then settles `stopped` without running.
 */
internal fun interface ReplyListeners {
    fun forClaim(claim: ClaimResult, stop: () -> Unit): ReplyListener?

    companion object {
        /** Nobody listens: a Mac with no voice. */
        val NONE = ReplyListeners { _, _ -> null }
    }
}
