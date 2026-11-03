package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.conversation.ActivityStep
import org.formulagen.wrapper.conversation.ChatVoice
import org.formulagen.wrapper.conversation.MemberMode

/** Brain types encode exactly the nulls event-contracts requires, and omit the optional ones ([brainWire]). */
class BrainWireTest {
    private fun id(n: Int) = "10000000-0000-4000-8000-" + n.toString().padStart(12, '0')

    @Test fun optionalNullsNeverReachTheWire() {
        val issue = brainWire(BrainIssue(BrainIssueReason.MISSING_BINDING, roleId = id(1))).jsonObject
        assertEquals(setOf("reason", "roleId"), issue.keys)
        val step = brainWire(ActivityStep(ActivityKind.COMMAND, "npm test", ActivityStatus.FAILED)).jsonObject
        assertFalse("detail" in step)
        val role = brainWire(SpecialistRole(id(2), "Writer", "", MemberMode.TALK, null, 0)).jsonObject
        assertEquals(JsonNull, role.getValue("binding"))
        assertFalse("notice" in brainWire(ChatVoice()).jsonObject)
    }
}
