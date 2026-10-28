package org.formulagen.wrapper.progression.embedded

import org.formulagen.wrapper.bridge.GameId
import kotlin.test.*

class GameClosedListenersTest {
    private val game = GameId.HOVEL_COMPANIONS

    /** Every listener hears the return of a launch that opened, once. */
    @Test fun aReturnFromAnOpenedLaunchNotifiesEachListenerOnce() {
        val listeners = GameClosedListeners()
        val first = mutableListOf<GameId>()
        val second = mutableListOf<GameId>()
        listeners.observe { first += it }
        listeners.observe { second += it }
        val returned = listeners.track(game) { it }
        returned()
        returned()
        assertEquals(listOf(game), first)
        assertEquals(listOf(game), second)
    }

    /** A closed handle stops only its own listener. */
    @Test fun aRemovedListenerHearsNothing() {
        val listeners = GameClosedListeners()
        val kept = mutableListOf<GameId>()
        val removed = mutableListOf<GameId>()
        listeners.observe { kept += it }
        listeners.observe { removed += it }.close()
        listeners.track(game) { it }.invoke()
        assertEquals(listOf(game), kept)
        assertEquals(emptyList(), removed)
    }

    /** A launch whose show fails closes its surface on the way out; the page gets `failed`, not `games.closed`. */
    @Test fun aFailedLaunchNotifiesNoOne() {
        val listeners = GameClosedListeners()
        val heard = mutableListOf<GameId>()
        listeners.observe { heard += it }
        val failure = assertFailsWith<IllegalStateException> {
            listeners.track(game) { returned -> returned(); error("show failed") }
        }
        assertEquals("show failed", failure.message)
        assertEquals(emptyList(), heard)
        // The next launch that opens still reports its return.
        listeners.track(game) { it }.invoke()
        assertEquals(listOf(game), heard)
    }
}
