package org.formulagen.wrapper.hovel

import kotlin.test.*
import kotlinx.serialization.json.*
import org.formulagen.wrapper.conversations.CompanionContractFixtures
import org.formulagen.wrapper.conversations.HostHeartbeat
import org.formulagen.wrapper.conversations.fromWire
import org.junit.Test

/**
 * The list suggestion bounds, feature and payloads, as the Mac mirrors them, equal event-contracts `hovel/lists`:
 * `LIST_LIMITS`, `DURATION_MINUTES`, `LIST_SUGGESTIONS_FEATURE` and the contract's own fixtures, as
 * `CompanionContractFixtures` carries them from server-platform.
 */
class ListSuggestionContractTest {
    private val fixtures = CompanionContractFixtures.PAYLOADS.mapValues { Json.parseToJsonElement(it.value).jsonObject }
    private fun value(name: String) = fixtures.getValue(name).getValue("value")

    @Test fun nativeMirrorsEqualTheContract() {
        val limits = value("lists.limits").jsonObject
        fun number(name: String) = limits.getValue(name).jsonPrimitive.int
        assertEquals(ListLimits.NAME_MAX, number("nameMax"))
        assertEquals(ListLimits.TITLE_MAX, number("titleMax"))
        assertEquals(ListLimits.STEPS_MAX, number("stepsMax"))
        assertEquals(ListLimits.SUGGESTIONS_MAX, number("suggestionsMax"))
        assertEquals(ListLimits.ITEMS_PER_LIST_MAX, number("itemsPerListMax"))
        assertEquals(ListLimits.AVOID_MAX, number("avoidMax"))
        assertEquals(ListLimits.DURATION_MAX * 60, number("durationSecondsMax"))
        // The server's schema keeps this name.
        val heartbeat = fromWire(HostHeartbeat.serializer(), value("heartbeat"))
        assertTrue(ListSuggestionLane.FEATURES.all { it in heartbeat.features })
    }

    @Test fun theContractsOwnPayloadsAreOnesTheMacReadsAndWrites() {
        val work = fromWire(SuggestionWork.serializer(), value("lists.suggestionWork"))
        assertEquals("Ember", work.companionName)
        assertTrue(work.items.size <= ListLimits.ITEMS_PER_LIST_MAX && work.avoid.size <= ListLimits.AVOID_MAX)
        val ready = assertIs<SuggestionReply.Ready>(fromWire(SuggestionReply.serializer(), value("lists.reply.ready")))
        assertTrue(ready.suggestions.size <= ListLimits.SUGGESTIONS_MAX)
        // The Mac accepts what the server accepts.
        for (suggestion in ready.suggestions) assertTrue(suggestion.withinBounds(), suggestion.toString())
        assertEquals(setOf(LookGlyph.Icon::class, LookGlyph.Emoji::class), ready.suggestions.mapNotNull { it.look.glyph?.let { glyph -> glyph::class } }.toSet())
        assertEquals(SuggestionReply.Unavailable, fromWire(SuggestionReply.serializer(), value("lists.reply.unavailable")))
        assertEquals(SuggestionReply.Busy, fromWire(SuggestionReply.serializer(), value("lists.reply.busy")))
    }
}
