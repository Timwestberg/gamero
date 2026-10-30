package org.formulagen.wrapper.chat

import org.formulagen.wrapper.bridge.RuntimeId

/**
 * A runtime that works in a project folder: it takes only [TurnRequest]s with [TurnRequest.work], and keeps its
 * working session for the turn's lease (a conversation thread).
 */
internal interface WorkRuntime : AutoCloseable {
    val id: RuntimeId

    /** As [ChatRuntime.readiness]: the runtime's own sign-in. */
    fun readiness(accountId: String): Readiness

    /** As [ChatRuntime.startTurn], for a working turn. */
    fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle

    /**
     * How this runtime keeps context folders read-only. [ProjectFolders.forRuntime] gives it context
     * folders only when [ContextFolderSupport.readable].
     */
    val contextFolders: ContextFolderSupport get() = ContextFolderSupport.NONE

    /**
     * End the working sessions of one [lease] (a conversation's thread) now: what waits on the player is denied, and
     * only that lease's processes end. Never blocks for long.
     */
    fun release(lease: String)

    /** End every working session now, at shutdown: what waits on the player is denied, and processes end. */
    fun release()
}
