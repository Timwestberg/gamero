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
 * Text checked as event-contracts checks it, with the cases of `fixtures/text.json` that every host shares: names and
 * titles a person types against `displayText` ([SharedRules.isDisplayText]), the computer and phone names a native
 * dialog shows against `dialogName` ([SharedRules.isDialogName]), and approval text against `shareableText`
 * ([ShareableText]).
 */
class TextRulesTest {
    private val text = Json.parseToJsonElement(HostBridgeFixtures.TEXT).jsonObject
    private fun cases(rule: String, kind: String) = text.getValue(rule).jsonObject.getValue(kind).jsonArray.map { it.jsonPrimitive.content }

    @Test fun typedNamesAndTitlesFollowDisplayTextAndKeepEmojiWhole() {
        for (name in cases("displayText", "accepted")) assertTrue(SharedRules.isDisplayText(name), name)
        for (name in cases("displayText", "refused")) assertFalse(SharedRules.isDisplayText(name), name.escaped())
        assertTrue(SharedRules.isCompanionName("Ember \ud83d\udc69\u200d\ud83d\udcbb"))
    }

    @Test fun dialogNamesFollowDialogName() {
        for (name in cases("dialogName", "accepted")) assertTrue(SharedRules.isDialogName(name), name)
        for (name in cases("dialogName", "refused")) assertFalse(SharedRules.isDialogName(name), name.escaped())
        assertTrue(cases("dialogName", "refused").size >= 15)
    }

    @Test fun approvalTextFollowsShareableText() {
        for (line in cases("shareable", "accepted")) assertTrue(ShareableText.isShareable(line, CompanionLimits.APPROVAL_LINE), line)
        for (line in cases("shareable", "refused")) assertFalse(ShareableText.isShareable(line, CompanionLimits.APPROVAL_LINE), line.escaped())
        assertTrue(cases("shareable", "refused").size >= 12)
    }

    @Test fun aDialogNameIsCleanedToWhatDialogNameAccepts() {
        for (name in cases("dialogName", "refused") + cases("displayText", "accepted")) {
            assertTrue(SharedRules.isDialogName(SharedRules.dialogName(name, "Ember")), name.escaped())
        }
        assertEquals("Ember", SharedRules.dialogName("Em\u2028ber", "x"))
        assertEquals("Ember \ud83d\udd25", SharedRules.dialogName(" Ember \ud83d\udd25\udb40\udc67", "x"))
        assertEquals("Mac", SharedRules.dialogName("\u200b\u0000", "Mac"))
    }

    @Test fun linesSplitWhereverAReaderSeesANewLine() {
        assertEquals(listOf("a", "b", "c", "d", "e", "f"), "a\r\nb\nc\rd\u2028e\u2029f".splitLines())
        assertEquals(listOf("a", "b"), "a\u0085b".splitLines())
        assertEquals(listOf("one line"), "one line".splitLines())
    }

    private fun String.escaped() = map { if (it.code in 0x20..0x7e) "$it" else "\\u" + it.code.toString(16).padStart(4, '0') }.joinToString("")
}
