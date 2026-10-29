package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.conversations.ShareableText
import org.formulagen.wrapper.conversations.splitLines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The text a native dialog shows, checked as event-contracts checks it: names against `displayText`
 * ([SharedRules.isDisplayText]) and approval text against `shareableText` ([ShareableText]), with the cases of
 * `fixtures/text.json` that every host shares.
 */
class TextRulesTest {
    private val text = Json.parseToJsonElement(HostBridgeFixtures.TEXT).jsonObject
    private fun cases(rule: String, kind: String) = text.getValue(rule).jsonObject.getValue(kind).jsonArray.map { it.jsonPrimitive.content }

    @Test fun namesFollowDisplayText() {
        for (name in cases("display", "accepted")) assertTrue(SharedRules.isDisplayText(name), name)
        for (name in cases("display", "refused")) assertFalse(SharedRules.isDisplayText(name), name.escaped())
        assertTrue(cases("display", "refused").size >= 15)
    }

    @Test fun approvalTextFollowsShareableText() {
        for (line in cases("shareable", "accepted")) assertTrue(ShareableText.isShareable(line, CompanionLimits.APPROVAL_LINE), line)
        for (line in cases("shareable", "refused")) assertFalse(ShareableText.isShareable(line, CompanionLimits.APPROVAL_LINE), line.escaped())
        assertTrue(cases("shareable", "refused").size >= 12)
    }

    @Test fun aNameIsCleanedToWhatDisplayTextAccepts() {
        for (name in cases("display", "refused")) {
            val cleaned = SharedRules.displayable(name, "Ember")
            assertTrue(SharedRules.isDisplayText(cleaned), name.escaped())
        }
        assertEquals("Ember", SharedRules.displayable("Em ber", "x"))
        assertEquals("Ember 🔥", SharedRules.displayable(" Ember 🔥󠁧", "x"))
        assertEquals("Mac", SharedRules.displayable("​\u0000", "Mac"))
    }

    @Test fun linesSplitWhereverAReaderSeesANewLine() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), "a\r\nb\nc\rd e f".splitLines())
        assertEquals(listOf("a", "b"), "a\u0085b".splitLines())
        assertEquals(listOf("one line"), "one line".splitLines())
    }

    private fun String.escaped() = map { if (it.code in 0x20..0x7e) "$it" else "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
}
