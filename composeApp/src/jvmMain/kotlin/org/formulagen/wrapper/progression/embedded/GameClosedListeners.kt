package org.formulagen.wrapper.progression.embedded

import java.util.concurrent.CopyOnWriteArrayList
import org.formulagen.wrapper.bridge.GameId

/**
 * Who hears that the player returned from a game, as the `games.closed` contract says: at most once
 * per launch that opened, and never for a launch that failed. No Swing, so tests drive it directly.
 */
internal class GameClosedListeners {
    private val listeners = CopyOnWriteArrayList<(GameId) -> Unit>()

    /** Adds [onClosed] until the returned handle is closed. */
    fun observe(onClosed: (GameId) -> Unit): AutoCloseable {
        listeners += onClosed
        return AutoCloseable { listeners -= onClosed }
    }

    /**
     * Tracks one launch of [gameId]. [open] receives the callback the game calls when the player
     * returns, and opens the game. The callback notifies only once [open] has returned, so a launch
     * that throws, and closes its surface on the way out, reports nothing.
     */
    fun <T> track(gameId: GameId, open: (returned: () -> Unit) -> T): T {
        var opened = false
        val result = open {
            if (opened) {
                opened = false
                listeners.forEach { it(gameId) }
            }
        }
        opened = true
        return result
    }
}
