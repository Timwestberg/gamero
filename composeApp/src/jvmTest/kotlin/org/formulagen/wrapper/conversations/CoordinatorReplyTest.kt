package org.formulagen.wrapper.conversations

import kotlin.test.*
import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.brain.CoordinatorDecision
import org.formulagen.wrapper.brain.DecisionKind
import org.formulagen.wrapper.brain.THREAD_REPLY_DECISIONS

class CoordinatorReplyTest {
    private val all = DecisionKind.entries.toSet()

    @Test fun anAnswerIsItsHeaderLineThenItsBody() {
        assertEquals(CoordinatorDecision.Answer("Hello there.\nSecond line."), parseCoordinatorReply("{\"kind\":\"answer\"}\nHello there.\nSecond line.\n", all))
        assertEquals(CoordinatorDecision.Answer("Hi"), parseCoordinatorReply("  {\"kind\": \"answer\"}  \n\n  Hi  ", all))
        // The brain's whole form with no body is read the same way.
        assertEquals(CoordinatorDecision.Answer("Hi"), parseCoordinatorReply("{\"kind\":\"answer\",\"text\":\"Hi\"}", all))
    }

    @Test fun reactionsAreMetadataAndDirectRepliesRemainCompatible() {
        val raw = """{"kind":"answer","reaction":"🎉"}""" + "\nHappy to help."
        assertEquals(CoordinatorDecision.Answer("Happy to help.", "🎉"), parseCoordinatorReply(raw, all))
        assertEquals(CoordinatorDecision.Answer("Happy to help.", "🎉"), parseDirectCoordinatorReply(raw))
        assertEquals(CoordinatorDecision.Answer("Plain answer"), parseDirectCoordinatorReply("Plain answer"))
        assertFails { parseDirectCoordinatorReply("""{"kind":"answer","reaction":"invalid"}""" + "\nHi") }
        val sent = mutableListOf<String>()
        val stream = ReplyStream(headed = true, optionalHeader = true) { sent += it }
        stream.partial(raw)
        assertEquals(listOf("Happy to help."), sent)
        val plain = ReplyStream(headed = true, optionalHeader = true) { sent += it }
        plain.partial("Plain answer")
        assertEquals(listOf("Happy to help.", "Plain answer"), sent)
    }

    @Test fun anAnswerNeedsItsTextWithinTheMessageLimit() {
        assertFails { parseCoordinatorReply("{\"kind\":\"answer\"}", all) }
        assertFails { parseCoordinatorReply("{\"kind\":\"answer\"}\n   \n", all) }
        assertFails { parseCoordinatorReply("{\"kind\":\"answer\"}\n" + "x".repeat(CompanionLimits.MESSAGE + 1), all) }
        assertEquals(CompanionLimits.MESSAGE, (parseCoordinatorReply("{\"kind\":\"answer\"}\n" + "x".repeat(CompanionLimits.MESSAGE), all)
            as CoordinatorDecision.Answer).text.length)
    }

    @Test fun everyOtherDecisionIsItsHeaderAloneAndOnlyWhenAllowed() {
        val specialist = id()
        val delegate = """{"kind":"delegate","specialistId":"$specialist","task":"Add sign-in","inputIds":[]}"""
        assertEquals(CoordinatorDecision.Delegate(specialist, "Add sign-in", emptyList()), parseCoordinatorReply(delegate, all))
        assertEquals(CoordinatorDecision.Delegate(specialist, "Add sign-in", emptyList()), parseCoordinatorReply("$delegate\n   ", all))
        assertFails("a body after another decision") { parseCoordinatorReply("$delegate\nAnd some text", all) }
        assertFails("not allowed") { parseCoordinatorReply(delegate, setOf(DecisionKind.ANSWER)) }
        assertFails("no answer allowed") { parseCoordinatorReply("{\"kind\":\"answer\"}\nHi", setOf(DecisionKind.DELEGATE)) }
        assertFails("prose") { parseCoordinatorReply("Sure, I will do that.", all) }
        assertFails("markdown") { parseCoordinatorReply("```json\n$delegate\n```", all) }
    }

    @Test fun aForwardNamesItsThreadAndAnOptionalNote() {
        val thread = id()
        assertEquals(CoordinatorDecision.Forward(thread), parseCoordinatorReply("""{"kind":"forward","threadId":"$thread"}""", THREAD_REPLY_DECISIONS))
        assertEquals(CoordinatorDecision.Forward(thread, "Use the new colors"),
            parseCoordinatorReply("""{"kind":"forward","threadId":"$thread","note":"Use the new colors"}""", THREAD_REPLY_DECISIONS))
        assertFails { parseCoordinatorReply("""{"kind":"forward","threadId":"not-a-uuid"}""", THREAD_REPLY_DECISIONS) }
        assertFails { parseCoordinatorReply("""{"kind":"forward","threadId":"$thread","note":""}""", THREAD_REPLY_DECISIONS) }
        assertFails("a run never forwards") { parseCoordinatorReply("""{"kind":"forward","threadId":"$thread"}""", all - DecisionKind.FORWARD) }
        assertFails("a thread reply never asks") { parseCoordinatorReply("""{"kind":"ask","question":"Why?"}""", THREAD_REPLY_DECISIONS) }
    }

    @Test fun anAnswerStreamsOnlyOnceItsHeaderValidates() {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(headed = true) { sent += it }
        stream.partial("{\"kind\":\"ans")
        stream.partial("{\"kind\":\"answer\"}")
        assertEquals(emptyList(), sent, "nothing before the header line completes")
        stream.partial("{\"kind\":\"answer\"}\n")
        stream.partial("{\"kind\":\"answer\"}\n  ")
        assertEquals(emptyList(), sent, "leading whitespace is held back")
        stream.partial("{\"kind\":\"answer\"}\n  Hel")
        stream.partial("{\"kind\":\"answer\"}\n  Hello")
        stream.partial("{\"kind\":\"answer\"}\n  Hello")
        assertEquals(listOf("Hel", "lo"), sent)
        assertTrue(stream.streamed)
    }

    @Test fun anyOtherHeaderNeverStreams() {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(headed = true) { sent += it }
        stream.partial("""{"kind":"delegate","specialistId":"${id()}","task":"x","inputIds":[]}""" + "\n")
        stream.partial("""{"kind":"delegate","specialistId":"${id()}","task":"x","inputIds":[]}""" + "\nleaked text")
        assertEquals(emptyList(), sent)
        val notAllowed = ReplyStream(headed = true, allowAnswer = false) { sent += it }
        notAllowed.partial("{\"kind\":\"answer\"}\nHello")
        assertEquals(emptyList(), sent, "an answer that is not allowed must not stream")
        assertFalse(notAllowed.streamed)
    }

    @Test fun aRestartedReplyStopsStreaming() {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(headed = false) { sent += it }
        stream.partial("First draft")
        stream.partial("Second")
        stream.partial("Second draft, longer")
        assertEquals(listOf("First draft"), sent)
    }

    @Test fun aPlainReplyStreamsFromItsFirstCharacter() {
        val sent = mutableListOf<String>()
        val stream = ReplyStream(headed = false) { sent += it }
        stream.partial("{")
        stream.partial("{not a header")
        assertEquals(listOf("{", "not a header"), sent)
    }

    @Test fun streamedTextStopsAtTheMessageLimit() {
        val sent = StringBuilder()
        val stream = ReplyStream(headed = false) { sent.append(it) }
        stream.partial("x".repeat(CompanionLimits.MESSAGE + 50))
        assertEquals(CompanionLimits.MESSAGE, sent.length)
    }

    @Test fun theProtocolOffersEveryHeaderShape() {
        for (kind in listOf("answer", "ask", "delegate", "enter-workflow", "start-step", "skip-step", "step-result", "forward")) {
            assertTrue("\"kind\":\"$kind\"" in CONVERSATION_PROTOCOL, kind)
        }
        assertTrue(conversationCoordinatorInstructions("Be kind.").endsWith("User's coordinator instructions:\nBe kind."))
    }
}
