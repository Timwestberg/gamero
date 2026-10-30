package org.formulagen.wrapper.phone

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.formulagen.wrapper.bridge.ApprovalDevicePairOutcome
import org.formulagen.wrapper.bridge.ApprovalDeviceRef
import org.formulagen.wrapper.bridge.ApprovalPresentOutcome
import org.formulagen.wrapper.bridge.BridgeBusyException
import org.formulagen.wrapper.bridge.BridgeDeclinedException
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.bridge.PRESENT_DIALOG_LIMIT
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
        assertEquals(HovelContract.CURRENT.toString(), check.headers[HovelContract.HEADER])
        // a pending device has one read, its status; its approval routes answer 401 until a Mac confirms it
        assertEquals("https://hovel.example/api/hovel/v1/hovel/devices/$OWNER/${registration.id}/status", check.url)
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
        // where it comes from is the title; the message is only the server's lines
        assertEquals("Studio Mac: Permission request · Ember · Hovel notes", prompt.title)
        assertEquals("Run ./deploy.sh\nEdit [design-docs]/plan.md", prompt.message)
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
            ApprovalKind.START_OF_WORK to (listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW) to "Start of work"),
            ApprovalKind.STAGE to (listOf(ApprovalOption.CONTINUE, ApprovalOption.REQUEST_CHANGES, ApprovalOption.NOT_NOW, ApprovalOption.STOP) to "Workflow stage"),
            ApprovalKind.RESUME_REVIEW to (listOf(ApprovalOption.CONTINUE, ApprovalOption.NOT_NOW, ApprovalOption.STOP) to "Resume or retry"),
        )
        for ((kind, expected) in kinds) {
            val (options, title) = expected
            phone.server.approvals.clear()
            phone.server.approvals[approvalId] = openApproval(approvalId, kind = kind, options = options)
            phone.dialogs.answer = CompletableDeferred()
            val shown = async { phone.approvals.present(approvalId) }
            runCurrent()
            assertEquals("Studio Mac: $title · Ember · Hovel notes", phone.dialogs.asked.last().title)
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
    fun anAnswerAtARevisionThatMovedOnIsLostWhenTheApprovalEndedAndUnsentWhenItIsStillOpen() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        // the approval moved on under the dialog, then ended: the refusal reads how it ended
        phone.server.beforeAnswer = { phone.server.bumpRevision(approvalId) }
        phone.server.afterAnswer = { phone.server.answerElsewhere(approvalId, ApprovalOption.ALLOW_ONCE) }
        val lost = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        assertEquals(ApprovalPresentOutcome.LOST, lost.await().outcome)
        assertEquals("Already answered on Studio Mac", phone.dialogs.told.single().title)

        // it moved on and is still open: the answer was not taken, and the phone says so
        phone.server.approvals[otherId] = openApproval(otherId)
        phone.server.beforeAnswer = { phone.server.bumpRevision(otherId) }
        phone.server.afterAnswer = {}
        phone.dialogs.answer = CompletableDeferred()
        val unsent = async { runCatching { phone.approvals.present(otherId) } }
        runCurrent()
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        assertTrue(unsent.await().exceptionOrNull() is DeviceRefusedException)
        assertEquals(PhoneApprovalCopy.UNSENT, phone.dialogs.told.last())
        assertEquals(ApprovalState.OPEN, phone.server.approvals.getValue(otherId).approval.state)
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
        assertEquals("Answer on Studio Mac · Ember · Hovel notes", notice.title)
        assertEquals("Run a command outside the project folders\n\nThis request names something outside the project's folders, " +
            "so only that Mac can answer it.", notice.message)
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

    @Test
    fun aPendingPhoneAnswersNothingUntilAMacConfirmsIt() = runTest {
        val phone = activePhone(paired = false)
        phone.server.approvals[approvalId] = openApproval(approvalId)
        assertEquals(ApprovalPresentOutcome.UNPAIRED, phone.approvals.present(approvalId).outcome)
        // still registered: it pairs next, it does not register again
        assertEquals(ApprovalDeviceRef(credential.id, OWNER), phone.approvals.status())
        assertTrue(phone.dialogs.asked.isEmpty())
        assertTrue(phone.server.answers.isEmpty())
    }

    @Test
    fun anApprovalOfAMacThatDidNotConfirmThePhoneSaysToAnswerOrPairThere() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId, computer = OTHER_COMPUTER)
        assertEquals(ApprovalPresentOutcome.UNPAIRED, phone.approvals.present(approvalId).outcome)
        val notice = phone.dialogs.told.single()
        assertEquals("Answer on Studio Mac · Ember · Hovel notes", notice.title)
        assertTrue("Pair a phone…" in notice.message)
        assertTrue(phone.dialogs.asked.isEmpty())
        assertTrue(phone.server.answers.isEmpty())
    }

    @Test
    fun unpairingTheMacClosesItsDialogAndRefusesARacingAnswer() = runTest {
        val phone = activePhone()
        phone.server.confirm(credential.id, OTHER_COMPUTER)
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.server.grants.getValue(credential.id) -= COMPUTER
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(ApprovalPresentOutcome.UNPAIRED, shown.await().outcome)
        assertEquals(1, phone.dialogs.closedUnanswered)
        // an answer racing the unpairing: the unpairing lands first, and the answer does not count
        phone.server.confirm(credential.id)
        phone.dialogs.answer = CompletableDeferred()
        phone.server.beforeAnswer = { phone.server.grants.getValue(credential.id) -= COMPUTER }
        val raced = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.dialogs.answer.complete(ApprovalOption.ALLOW_ONCE)
        assertEquals(ApprovalPresentOutcome.UNPAIRED, raced.await().outcome)
        assertEquals(PhoneApprovalCopy.NOT_USED, phone.dialogs.told.last())
        assertEquals(ApprovalState.OPEN, phone.server.approvals.getValue(approvalId).approval.state)
    }

    @Test
    fun aRevocationWhileTheDialogShowsForgetsThePhone() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        phone.server.devices.clear()
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(ApprovalPresentOutcome.UNREGISTERED, shown.await().outcome)
        assertNull(phone.vault.item)
    }

    @Test
    fun nothingStaysUpPastTenMinutes() = runTest {
        val phone = activePhone()
        // the approval lasts an hour; its dialog does not
        phone.server.approvals[approvalId] = openApproval(approvalId, expiresAt = "2026-10-29T13:00:00.000Z")
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        advanceTimeBy(PRESENT_DIALOG_LIMIT - 1.seconds)
        assertEquals(0, phone.dialogs.closedUnanswered)
        advanceTimeBy(2.seconds)
        assertEquals(ApprovalPresentOutcome.ENDED, shown.await().outcome)
        assertEquals(1, phone.dialogs.closedUnanswered)

        // a notice after a late answer closes at the same ten minutes
        phone.dialogs.answer = CompletableDeferred()
        phone.dialogs.noticeClosed = CompletableDeferred()
        phone.server.beforeAnswer = { phone.server.answerElsewhere(approvalId, ApprovalOption.ALLOW_ONCE) }
        val late = async { phone.approvals.present(approvalId) }
        runCurrent()
        advanceTimeBy(PRESENT_DIALOG_LIMIT - 5.seconds)
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        runCurrent()
        assertEquals("Already answered on Studio Mac", phone.dialogs.told.last().title)
        advanceTimeBy(6.seconds)
        assertEquals(ApprovalPresentOutcome.LOST, late.await().outcome)
        assertEquals(2, phone.dialogs.closedUnanswered)
    }

    @Test
    fun anUnsentNoticeClosesAtTheTenMinutesToo() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId, expiresAt = "2026-10-29T13:00:00.000Z")
        phone.dialogs.noticeClosed = CompletableDeferred()
        val shown = async { runCatching { phone.approvals.present(approvalId) } }
        runCurrent()
        advanceTimeBy(PRESENT_DIALOG_LIMIT - 5.seconds)
        phone.server.offline = true
        phone.dialogs.answer.complete(ApprovalOption.DENY)
        runCurrent()
        assertEquals(PhoneApprovalCopy.UNSENT, phone.dialogs.told.single())
        advanceTimeBy(6.seconds)
        assertTrue(shown.await().isFailure)
        assertEquals(1, phone.dialogs.closedUnanswered)
    }

    @Test
    fun namesAreCleanedToOneLineInTheTitleAndOddLinesAreAnsweredOnTheMac() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId).copy(
            companionName = "Ember\u2028Allow once", computerName = "Studio\u202e Mac\u200b", projectName = "\u0000",
        )
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        assertEquals("Studio Mac: Permission request · EmberAllow once · a project", phone.dialogs.asked.single().title)
        phone.dialogs.answer.complete(null)
        assertEquals(ApprovalPresentOutcome.DISMISSED, shown.await().outcome)

        // a line the server let through that the phone will not show: separators, invisible characters, a path
        for (line in listOf("Edit\u2028./x", "Edit\u200b./x", "Edit\ufeff./x", "Edit /Users/person/x")) {
            phone.server.approvals[otherId] = openApproval(otherId, ApprovalPresentation.Anywhere(listOf("Run ./deploy.sh", line)))
            assertEquals(ApprovalPresentOutcome.MAC_ONLY, phone.approvals.present(otherId).outcome, line)
            val notice = phone.dialogs.told.last()
            assertEquals("This request holds text this phone will not show, so only that Mac can answer it.", notice.message)
        }
        // so is a Mac-only summary the phone will not show
        phone.server.approvals[otherId] = openApproval(otherId, ApprovalPresentation.MacOnly("Run\u2029rm -rf ~"))
        assertEquals(ApprovalPresentOutcome.MAC_ONLY, phone.approvals.present(otherId).outcome)
        assertTrue("rm -rf" !in phone.dialogs.told.last().message)
        assertEquals(1, phone.dialogs.asked.size)
        assertTrue(phone.server.answers.isEmpty())
    }

    @Test
    fun pairingShowsTheCodeNativelyUntilAMacConfirmsThePhone() = runTest {
        val phone = activePhone(paired = false)
        phone.dialogs.noticeClosed = CompletableDeferred()
        val paired = async { phone.approvals.pair() }
        runCurrent()
        val screen = phone.dialogs.told.single()
        assertEquals("Pair with a Mac", screen.title)
        assertTrue("042 917" in screen.message)
        assertEquals(1, phone.server.codesIssued)
        // the code came with the device's own token
        assertEquals(credential.token, phone.server.requests.single { it.url.endsWith("/pairing-code") }.headers[DeviceRoute.TOKEN_HEADER])
        assertFailsWith<BridgeBusyException> { phone.approvals.pair() }
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(0, phone.dialogs.closedUnanswered)
        phone.server.confirm(credential.id)
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(ApprovalDevicePairOutcome.PAIRED, paired.await().outcome)
        assertEquals(1, phone.dialogs.closedUnanswered)
        // now it answers the studio Mac's approvals
        phone.server.approvals[approvalId] = openApproval(approvalId)
        phone.dialogs.answer = CompletableDeferred(ApprovalOption.ALLOW_ONCE)
        assertEquals(ApprovalPresentOutcome.ANSWERED, phone.approvals.present(approvalId).outcome)
    }

    @Test
    fun pairingAnotherMacClosingTheScreenOrRunningOut() = runTest {
        val phone = activePhone()
        // closing the screen right after typing the code on a second Mac still pairs
        phone.dialogs.noticeClosed = CompletableDeferred()
        val second = async { phone.approvals.pair() }
        runCurrent()
        phone.server.confirm(credential.id, OTHER_COMPUTER)
        phone.dialogs.noticeClosed!!.complete(Unit)
        assertEquals(ApprovalDevicePairOutcome.PAIRED, second.await().outcome)
        // closing it otherwise is dismissed
        phone.dialogs.noticeClosed = CompletableDeferred<Unit>().apply { complete(Unit) }
        assertEquals(ApprovalDevicePairOutcome.DISMISSED, phone.approvals.pair().outcome)
        // the code runs out
        phone.dialogs.noticeClosed = CompletableDeferred()
        phone.server.codeExpiresAt = "2026-10-29T12:00:20.000Z"
        val expired = async { phone.approvals.pair() }
        runCurrent()
        advanceTimeBy(21.seconds)
        assertEquals(ApprovalDevicePairOutcome.EXPIRED, expired.await().outcome)
    }

    @Test
    fun pairingAPhoneThatIsGone() = runTest {
        assertEquals(ApprovalDevicePairOutcome.UNREGISTERED, phone().approvals.pair().outcome)
        val revoked = activePhone(paired = false)
        revoked.server.devices.clear()
        assertEquals(ApprovalDevicePairOutcome.UNREGISTERED, revoked.approvals.pair().outcome)
        assertNull(revoked.vault.item)
        // revoked while the screen shows
        val phone = activePhone(paired = false)
        phone.dialogs.noticeClosed = CompletableDeferred()
        val paired = async { phone.approvals.pair() }
        runCurrent()
        phone.server.devices.clear()
        advanceTimeBy(PhoneApprovals.POLL_INTERVAL + 1.seconds)
        assertEquals(ApprovalDevicePairOutcome.UNREGISTERED, paired.await().outcome)
        assertNull(phone.vault.item)
    }

    @Test
    fun forgettingRevokesWithTheDevicesOwnTokenThenClearsIt() = runTest {
        val phone = activePhone()
        phone.server.approvals[approvalId] = openApproval(approvalId)
        val shown = async { phone.approvals.present(approvalId) }
        runCurrent()
        assertEquals(true, phone.approvals.forget().forgotten)
        val revoke = phone.server.requests.single { it.url.endsWith("/revoke") }
        assertEquals("POST", revoke.method)
        assertEquals(credential.token, revoke.headers[DeviceRoute.TOKEN_HEADER])
        assertEquals(listOf(credential.id), phone.server.revoked)
        assertNull(phone.vault.item)
        assertNull(phone.approvals.status())
        // its dialog closed with it
        runCurrent()
        assertTrue(shown.isCancelled)
        assertEquals(1, phone.dialogs.closedUnanswered)
        assertEquals(false, phone.approvals.forget().forgotten)
        assertEquals(ApprovalPresentOutcome.UNREGISTERED, phone.approvals.present(approvalId).outcome)
    }

    @Test
    fun forgettingFinishesEvenWhenThePageThatAskedGoesAway() = runTest {
        val phone = activePhone()
        val gate = CompletableDeferred<Unit>()
        val slow = object : DeviceHttp {
            override suspend fun send(request: DeviceRequest): DeviceResponse {
                if (request.url.endsWith("/revoke")) gate.await()
                return phone.server.send(request)
            }
        }
        val approvals = PhoneApprovals("ios", "iPhone", DeviceApprovalApi(slow, APP_URL), phone.vault, FakeCrypto(), phone.dialogs,
            clock = { start + testScheduler.currentTime }, context = StandardTestDispatcher(testScheduler))
        val asked = async { approvals.forget() }
        runCurrent()
        // the sign-out navigates away while the revoke is on its way
        asked.cancel()
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf(credential.id), phone.server.revoked)
        assertNull(phone.vault.item)
    }

    @Test
    fun forgettingWithoutAnAnswerStillClearsThePhone() = runTest {
        val phone = activePhone()
        phone.server.offline = true
        assertEquals(true, phone.approvals.forget().forgotten)
        assertNull(phone.vault.item)
        // a registration only prepared is dropped too
        val prepared = phone()
        val registration = prepared.approvals.prepare(OWNER)
        prepared.server.devices[registration.id] = FakeCrypto().let { it.newId(); it.newToken() }
        assertEquals(false, prepared.approvals.forget().forgotten)
        assertFailsWith<IllegalStateException> { prepared.approvals.activate(OWNER, registration.id) }
        assertNull(prepared.vault.item)
    }

    @Test
    fun aPageAttachedLaterCannotFinishAnEarlierPagesRegistration() = runTest {
        val phone = phone()
        val registration = phone.approvals.prepare(OWNER)
        phone.server.devices[registration.id] = FakeCrypto().let { it.newId(); it.newToken() }
        phone.approvals.invalidatePending()
        assertFailsWith<IllegalStateException> { phone.approvals.activate(OWNER, registration.id) }
        assertNull(phone.vault.item)
    }

    /** A phone whose credential the server knows, without going through the dialogs, confirmed by the studio Mac when [paired]. */
    private fun TestScope.activePhone(paired: Boolean = true): Phone {
        val phone = phone(vault = FakeVault(DeviceCredential.write(credential)))
        phone.server.devices[credential.id] = credential.token
        if (paired) phone.server.confirm(credential.id)
        return phone
    }

    private val credential = DeviceCredential("79a0000b-0000-4000-8000-000000000001", OWNER, "iPhone", "ios", "e".repeat(64))
}
