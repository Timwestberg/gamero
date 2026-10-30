package org.formulagen.wrapper.chat

import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.formulagen.wrapper.bridge.RuntimeId

/** The person-facing reason a turn failed, as a paused run or a failed message shows it. */
class FailureNoticeTest {
    @Test fun aUsageLimitSaysWhenItResetsWhenTheRuntimeSays() {
        assertEquals("You've reached your Claude usage limit. Try again later.",
            failureNotice(RuntimeId.CLAUDE_CODE, ChatFailure.UsageLimit(null)))
        val soon = failureNotice(RuntimeId.CODEX, ChatFailure.UsageLimit(Instant.now().plusSeconds(3600)), ZoneOffset.UTC)
        assertTrue(soon.startsWith("You've reached your Codex usage limit. It resets at "), soon)
    }

    @Test fun aSignedOutRuntimeNamesItsOwnSignIn() {
        assertEquals("Sign in to Codex again: run `codex login` in Terminal.", failureNotice(RuntimeId.CODEX, ChatFailure.SignedOut))
    }

    @Test fun aLostConversationStartsFresh() {
        assertEquals("Codex could not continue this conversation. Send your message again to start fresh.",
            failureNotice(RuntimeId.CODEX, ChatFailure.ConversationLost))
    }
}
