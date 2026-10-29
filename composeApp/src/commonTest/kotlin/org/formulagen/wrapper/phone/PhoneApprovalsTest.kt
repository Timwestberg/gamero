package org.formulagen.wrapper.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.formulagen.wrapper.bridge.ApprovalDeviceRef
import org.formulagen.wrapper.bridge.ApprovalPresentOutcome
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.BridgeDeclinedException
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.brainJson
import org.formulagen.wrapper.conversations.ApprovalKind
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalPresentation
import org.formulagen.wrapper.conversations.ApprovalState
import org.formulagen.wrapper.conversations.ApprovalSurface
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The phone as an approval device against a fake hovel-service: registration, the dialog, and first answer wins. */
class PhoneApprovalsTest {
    private val approvalId = "72a0000b-0000-4000-8000-000000000001"
    private val otherId = "72a0000b-0000-4000-8000-000000000002"
    /** 2026-10-29T12:00:00Z, the start of every test's clock. */
    private val start = 1_793_275_200_000L

    private class Phone(val approvals: PhoneApprovals, val server: FakeDeviceServer, val vault: FakeVault, val dialogs: ScriptedDialogs)

    private fun TestScope.phone(server: FakeDeviceServer = FakeDeviceServer(), vault: FakeVault = FakeVault()): Phone {
        val dialogs = ScriptedDialogs()
        val approvals = PhoneApprovals("ios", "iPhone", DeviceApprovalApi(server, APP_URL), vault, FakeCrypto(), dialogs,
            clock = { start + testScheduler.currentTime }, context = StandardTestDispatcher(testScheduler))
        return Phone(approvals, server, vault, dialogs)
    }

    @Test
    fun aPhoneRegistersInOneExplicitStepAndKeepsOnlyWhatTheServerKnows() = runTest {
        val phone = phone()
        val registration = phone.approvals.prepare(OWNER.uppercase())
        assertEquals(PhoneApprovalCopy.REGISTER, phone.dialogs.confirmed.single())
        assertEquals("iPhone", registration.name)
        assertEquals("ios", registration.platform)
        assertTrue(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(registration.id))
        assertTrue(Regex("[0-9a-f]{64}").matches(registration.tokenHash))
        // nothing is kept until the account registered it and the server confirms
        assertNull(phone.vault.item)
        assertNull(phone.approvals.status())
        assertFailsWith<IllegalStateException> { phone.approvals.activate(OWNER, registration.id) }
        assertNull(phone.vault.item)

        val token = FakeCrypto().let { crypto -> crypto.newId(); crypto.newToken() }
        assertEquals(FakeCrypto().let { it.newId(); it.tokenHash(it.newToken()) }, registration.tokenHash)
        phone.server.devices[registration.id] = token
        assertFailsWith<IllegalStateException> { phone.approvals.activate(OWNER, "79a0000b-0000-4000-8000-0000000000aa") }
        assertEquals(registration.id, phone.approvals.activate(OWNER, registration.id).deviceId)
        val kept = DeviceCredential.read(phone.vault.item)!!
        assertEquals(DeviceCredential(registration.id, OWNER, "iPhone", "ios", token), kept)
        assertEquals(ApprovalDeviceRef(registration.id, OWNER), phone.approvals.status())
        // the check used the new token, with the contract header
        val check = phone.server.requests.last()
        assertEquals(token, check.headers[DeviceRoute.TOKEN_HEADER])
        assertEquals("1", check.headers[HovelContract.HEADER])
        assertEquals("https://hovel.example/api/hovel/v1/hovel/devices/$OWNER/${registration.id}/approvals", check.url)
    }

    @Test
    fun aDeclinedOrOverlappingRegistrationChangesNothing() = runTest {
        val phone = phone()
        phone.dialogs.confirmAnswer = false
        assertFailsWith<BridgeDeclinedException> { phone.approvals.prepare(OWNER) }
        assertFailsWith<IllegalStateException> { phone.approvals.activate(OWNER, "79a0000b-0000-4000-8000-000000000001") }
        assertNull(phone.vault.item)
        phone.dialogs.confirmAnswer = true
        val slow = object : ApprovalDialogs by phone.dialogs {
            val gate = CompletableDeferred<Boolean>()
            override suspend fun confirm(prompt: DevicePrompt) = gate.await()
        }
        val blocking = PhoneApprovals("ios", "iPhone", DeviceApprovalApi(phone.server, APP_URL), phone.vault, FakeCrypto(), slow,
            clock = { start }, context = StandardTestDispatcher(testScheduler))
        val first = async { blocking.prepare(OWNER) }
        runCurrent()
        assertFailsWith<BridgeBusyException> { blocking.prepare(OWNER) }
        slow.gate.complete(true)
        first.await()
    }

    @Test
    fun anUnregisteredPhoneAsksNothing() = runTest {
        val phone = phone()
        assertEquals(ApprovalPresentOutcome.UNREGISTERED, phone.approvals.present(approvalId).outcome)
        assertTrue(phone.server.requests.isEmpty())
        // a damaged item reads as no device, and is cleared
        val damaged = phone(vault = FakeVault("{\"id\":\"nope\"}"))
        assertNull(damaged.approvals.status())
        assertNull(damaged.vault.item)
    }

    @Test
    fun thePhoneShowsTheExactLinesAndPostsTheAnswerItself() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        val prompt = phone.dialogs.asked.single()
        assertEquals("Permission request", prompt.title)
        assertEquals("Ember · Hovel notes on Studio Mac\n\nRun ./deploy.sh\nEdit [design-docs]/plan.md", prompt.message)
        assertEquals(listOf("Allow once" to false, "Deny" to true), prompt.choices.map { it.label to it.destructive })
        assertEquals("Later", prompt.dismiss)
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        assertEquals(ApprovalPresentOutcome.ANSWERED, shown.await().outcome)
        val posted = phone.server.answers.single()
        assertEquals(ApprovalAnswer(0, ApprovalOption.DENY), brainJson.decodeFromString(ApprovalAnswer.serializer(), posted.body!!))
        assertEquals("application/json", posted.headers["Content-Type"])
        val stored = phone.server.approvals.getValue(approvalId).approval
        assertEquals(ApprovalState.ANSWERED, stored.state)
        assertEquals(ApprovalSurface.PHONE, stored.answer?.by)
        assertTrue(phone.dialogs.told.isEmpty())
    }

    @Test
    fun everyKindOffersItsOwnAnswers() = runTest {
        val phone = activePhone()
        val kinds = mapOf(
            ApprovalKind.START_OF_WORK to (listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW) to "Start work?"),
            ApprovalKind.STAGE to (listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP) to "Workflow stage"),
            ApprovalKind.RESUME_REVIEW to (listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW, ApprovalOption.STOP) to "Resume or retry?"),
        )
        for ((kind, expected) in kinds) {
            val (options, title) = expected
            phone.server.approvals.clear()
            phone.server.approvals[approvalId] = openApproval(approvalId, kind = kind, options = options)
            phone.dialogs.answer = CompletableDeferred()
            val shown = async { phone.approvals.present(approvalId) }
            runCurrent()
            assertEquals(title, phone.dialogs.asked.last().title)
            assertEquals(options, phone.dialogs.asked.last().choices.map { it.option })
            phone.dialogs.answer.complete(options.last())
            assertEquals(ApprovalPresentOutcome.ANSWERED, shown.await().outcome, kind.name)
        }
    }

    @Test
    fun aLateAnswerIsToldItLostAndShownTheWinner() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        phone.server.beforeAnswer = { phone.server.answerElsewhere(approvalId, ApprovalOption.ALLOW_ONCE) }
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        assertEquals(ApprovalPresentOutcome.LOST, shown.await().outcome)
        val notice = phone.dialogs.told.single()
        assertEquals("Already answered on Studio Mac", notice.title)
        assertEquals("Allow once was chosen there first. Your answer was not used.", notice.message)
        assertEquals(ApprovalSurface.MAC, phone.server.approvals.getValue(approvalId).approval.answer?.by)
    }

    @Test
    fun anAnswerTheServerDoesNotConfirmSaysSo() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { runCatching { phone.approvals.present(approvalId) } }
        runCurrent()
        phone.server.offline = true
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        assertTrue(shown.await().isFailure)
        assertEquals(PhoneApprovalCopy.UNSENT, phone.dialogs.told.single())
        assertEquals(ApprovalState.OPEN, phone.server.approvals.getValue(approvalId).approval.state)
    }

    @Test
    fun theDialogClosesWhenTheApprovalEndsElsewhere() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        // a failed check keeps the dialog up
        phone.server.offline = true
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(0, phone.dialogs.closedUnanswered)
        phone.server.offline = false
        phone.server.answerElsewhere(approvalId, ApprovalOption.ALLOW_ONCE)
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(ApprovalPresentOutcome.ENDED, shown.await().outcome)
        assertEquals(1, phone.dialogs.closedUnanswered)
        assertTrue(phone.server.answers.isEmpty())
    }

    @Test
    fun theDialogClosesWhenTheApprovalExpires() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId, expiresAt = "2026-10-29T12:00:05.000Z")
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        advanceTimeBy(6.seconds)
        assertEquals(ApprovalPresentOutcome.ENDED, shown.await().outcome)
        assertEquals(1, phone.dialogs.closedUnanswered)
        // one that is already ended shows nothing
        phone.server.approvals[otherId] = openApproval(otherId, expiresAt = "2026-10-29T11:00:00.000Z")
        assertEquals(ApprovalPresentOutcome.ENDED, phone.approvals.present(otherId).outcome)
        phone.server.answerElsewhere(approvalId, ApprovalOption.DENY)
        assertEquals(ApprovalPresentOutcome.ENDED, phone.approvals.present(approvalId).outcome)
        assertEquals(ApprovalPresentOutcome.ENDED, phone.approvals.present("72a0000b-0000-4000-8000-0000000000ee").outcome)
        assertEquals(1, phone.dialogs.asked.size)
    }

    @Test
    fun aMacOnlyApprovalSaysWhereToAnswerAndOffersNoAnswer() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId, ApprovalPresentation.MacOnly("Run a command outside the project folders"))
        assertEquals(ApprovalPresentOutcome.MAC_ONLY, phone.approvals.present(approvalId).outcome)
        val notice = phone.dialogs.told.single()
        assertEquals("Answer on Studio Mac", notice.title)
        assertTrue(notice.message.startsWith("Ember · Hovel notes on Studio Mac\n\nRun a command outside the project folders"))
        assertEquals("OK", notice.button)
        assertTrue(phone.dialogs.asked.isEmpty())
        assertTrue(phone.server.answers.isEmpty())
    }

    @Test
    fun dismissingLeavesTheApprovalOpenOnTheMac() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.dialogs.answer.complete(null)
        assertEquals(ApprovalPresentOutcome.DISMISSED, shown.await().outcome)
        assertTrue(phone.server.answers.isEmpty())
        assertEquals(ApprovalState.OPEN, phone.server.approvals.getValue(approvalId).approval.state)
    }

    @Test
    fun aRevokedPhoneForgetsItsRegistration() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        phone.server.devices.clear()
        assertEquals(ApprovalPresentOutcome.UNREGISTERED, phone.approvals.present(approvalId).outcome)
        assertNull(phone.vault.item)
        assertNull(phone.approvals.status())
    }

    @Test
    fun oneDialogAtATime() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        phone.server.approvals[otherId] = openApproval(otherId)
        val first = async { phone.approvals.present(approvalId) }
        runCurrent()
        // the same approval again waits for the same dialog; another one is busy
        val again = async { phone.approvals.present(approvalId) }
        runCurrent()
        assertFailsWith<BridgeBusyException> { phone.approvals.present(otherId) }
        phone.dialogs.answer.complete(ApprovalOption.ALLOW_ONCE)
        assertEquals(ApprovalPresentOutcome.ANSWERED, first.await().outcome)
        assertEquals(ApprovalPresentOutcome.ANSWERED, again.await().outcome)
        assertEquals(1, phone.dialogs.asked.size)
        assertEquals(1, phone.server.answers.size)
        // once it closed, the next one shows
        phone.dialogs.answer = CompletableDeferred(ApprovalOption.DENY)
        assertEquals(ApprovalPresentOutcome.ANSWERED, phone.approvals.present(otherId).outcome)
    }

    @Test
    fun theDialogOutlivesThePageCallThatAskedForIt() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val asked = async { phone.approvals.present(approvalId) }
        runCurrent()
        asked.cancel()
        runCurrent()
        assertEquals(0, phone.dialogs.closedUnanswered)
        phone.dialogs.answer.complete(ApprovalOption.ALLOW_ONCE)
        runCurrent()
        assertEquals(ApprovalState.ANSWERED, phone.server.approvals.getValue(approvalId).approval.state)
    }

    /** A phone whose credential the server knows, without going through the dialogs. */
    private fun TestScope.activePhone(): Phone {
        val credential = DeviceCredential("79a0000b-0000-4000-8000-000000000001", OWNER, "iPhone", "ios", "e".repeat(64))
        val phone = phone(vault = FakeVault(DeviceCredential.write(credential)))
        phone.server.devices[credential.id] = credential.token
        return phone
    }
}
