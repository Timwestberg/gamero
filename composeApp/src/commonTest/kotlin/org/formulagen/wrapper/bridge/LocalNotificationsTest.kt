package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class LocalNotificationsTest {
    private val now = 1_800_000_000_000L
    private val reminders = NotificationScope.LISTS_REMINDERS
    private val focus = NotificationScope.LISTS_FOCUS

    /** A notification centre in memory. [hold] parks the next permission read, so a test can overlap two calls. */
    private class Center(var permission: NotificationPermission = NotificationPermission.GRANTED) : NotificationCenterPort {
        val waiting = linkedMapOf<String, ScheduledNotification>()
        val shown = mutableListOf<String>()
        var answer = NotificationPermission.GRANTED
        var prompts = 0
        var refused = emptySet<String>()
        var hold: CompletableDeferred<Unit>? = null
        var permissionReads = 0

        override suspend fun permission(): NotificationPermission {
            permissionReads++
            hold?.also { hold = null }?.await()
            return permission
        }
        override suspend fun prompt() { prompts++; permission = answer }
        override suspend fun pending() = waiting.keys.toList()
        override suspend fun delivered() = shown.toList()
        override suspend fun removePending(identifiers: List<String>) { identifiers.forEach(waiting::remove) }
        override suspend fun removeDelivered(identifiers: List<String>) { shown.removeAll(identifiers) }
        override suspend fun schedule(request: ScheduledNotification): Boolean {
            if (request.identifier in refused) return false
            waiting[request.identifier] = request
            return true
        }
    }

    private fun item(id: String, inMs: Long = 60_000, title: String = "Replace furnace filter", body: String = "") =
        LocalNotification(id, title, now + inMs, body)

    private fun notifications(center: Center) = LocalNotifications(center) { now }

    @Test
    fun anIdentifierNamesItsScopeAndNoOtherScopeOwnsIt() {
        assertEquals("lists.reminders/reminder:1", NotificationPlan.identifier(reminders, "reminder:1"))
        for (scope in NotificationScope.entries) for (other in NotificationScope.entries) {
            assertEquals(scope == other, NotificationPlan.owns(other, NotificationPlan.identifier(scope, "a.b_c:d-1")), "$scope in $other")
        }
        // An id that spells another scope stays in its own.
        assertFalse(NotificationPlan.owns(focus, NotificationPlan.identifier(reminders, "lists.focus")))
        assertFalse(NotificationPlan.owns(reminders, "someone-else"))
    }

    @Test
    fun onlyFutureItemsAreDueOneForEachIdSoonestFirst() {
        val due = NotificationPlan.due(
            listOf(item("late", 90_000), item("past", -1), item("now", 0), item("soon", 1), item("late", 30_000, title = "Moved earlier")),
            now,
        )
        assertEquals(listOf("soon" to now + 1, "late" to now + 30_000), due.map { it.id to it.fireAtMs })
        assertEquals("Moved earlier", due.last().title)
    }

    @Test
    fun aTriggerAlwaysLiesInTheFuture() {
        assertEquals(60.0, NotificationPlan.delaySeconds(now + 60_000, now))
        assertEquals(NotificationPlan.MIN_DELAY_SECONDS, NotificationPlan.delaySeconds(now + 1, now))
        assertEquals(NotificationPlan.MIN_DELAY_SECONDS, NotificationPlan.delaySeconds(now - 5_000, now))
    }

    @Test
    fun aSyncMakesTheScopeExactlyItsItemsAndLeavesOtherScopesAlone() = runTest {
        val center = Center()
        val notifications = notifications(center)
        notifications.sync(NotificationSync(focus, listOf(item("phase"))))
        notifications.sync(NotificationSync(reminders, listOf(item("a"), item("b"))))
        center.shown += listOf("lists.reminders/old", "lists.focus/old")

        val result = notifications.sync(NotificationSync(reminders, listOf(item("b", title = "Renamed", body = "Hallway closet"), item("c"), item("gone", -5_000))))

        assertEquals(NotificationSynced(NotificationPermission.GRANTED, 2), result)
        assertEquals(setOf("lists.focus/phase", "lists.reminders/b", "lists.reminders/c"), center.waiting.keys)
        assertEquals(ScheduledNotification("lists.reminders/b", "lists.reminders", "Renamed", "Hallway closet", now + 60_000), center.waiting["lists.reminders/b"])
        // What iOS already showed for this scope goes too; the other scope's stays.
        assertEquals(listOf("lists.focus/old"), center.shown)

        assertEquals(0, notifications.sync(NotificationSync(reminders, emptyList())).scheduled)
        assertEquals(setOf("lists.focus/phase"), center.waiting.keys)
    }

    @Test
    fun withoutPermissionNothingIsScheduledAndWhatWasPendingGoes() = runTest {
        val center = Center()
        val notifications = notifications(center)
        notifications.sync(NotificationSync(reminders, listOf(item("a"))))
        for (permission in listOf(NotificationPermission.DENIED, NotificationPermission.UNDETERMINED)) {
            center.permission = permission
            assertEquals(NotificationSynced(permission, 0), notifications.sync(NotificationSync(reminders, listOf(item("a"), item("b")))))
            assertEquals(emptySet(), center.waiting.keys)
        }
        assertEquals(0, center.prompts, "a sync never prompts")
    }

    @Test
    fun theCountIsWhatIosKeptNotWhatWasAsked() = runTest {
        val center = Center().apply { refused = setOf("lists.reminders/b") }
        assertEquals(1, notifications(center).sync(NotificationSync(reminders, listOf(item("a"), item("b")))).scheduled)
    }

    @Test
    fun requestPromptsOnlyWhileUndetermined() = runTest {
        val center = Center(NotificationPermission.UNDETERMINED).apply { answer = NotificationPermission.DENIED }
        val notifications = notifications(center)
        assertEquals(NotificationPermission.UNDETERMINED, notifications.status().permission)
        assertEquals(0, center.prompts, "status never prompts")
        assertEquals(NotificationPermission.DENIED, notifications.request().permission)
        assertEquals(NotificationPermission.DENIED, notifications.request().permission)
        assertEquals(1, center.prompts)
        center.permission = NotificationPermission.GRANTED
        assertEquals(NotificationPermission.GRANTED, notifications.request().permission)
        assertEquals(1, center.prompts)
    }

    @Test
    fun overlappingSyncsOfOneScopeRunOneAfterTheOther() = runTest {
        val center = Center()
        val notifications = notifications(center)
        val gate = CompletableDeferred<Unit>()
        center.hold = gate
        val first = launch { notifications.sync(NotificationSync(reminders, listOf(item("first")))) }
        val second = launch { notifications.sync(NotificationSync(reminders, listOf(item("second")))) }
        runCurrent()
        assertEquals(1, center.permissionReads, "the second sync waits for the first")
        gate.complete(Unit)
        first.join(); second.join()
        assertEquals(setOf("lists.reminders/second"), center.waiting.keys)
    }

    @Test
    fun aSyncWhosePageWentAwayStillFinishes() = runTest {
        val center = Center()
        val notifications = notifications(center)
        val gate = CompletableDeferred<Unit>()
        center.hold = gate
        val sync = launch { notifications.sync(NotificationSync(reminders, listOf(item("a")))) }
        runCurrent()
        sync.cancel()
        gate.complete(Unit)
        sync.join()
        assertEquals(setOf("lists.reminders/a"), center.waiting.keys)
    }

    @Test
    fun clearRemovesEveryScopeScheduledAndShown() = runTest {
        val center = Center()
        val notifications = notifications(center)
        notifications.sync(NotificationSync(focus, listOf(item("phase"))))
        notifications.sync(NotificationSync(reminders, listOf(item("a"))))
        center.shown += listOf("lists.reminders/old", "lists.focus/old")
        notifications.clear()
        assertTrue(center.waiting.isEmpty())
        assertTrue(center.shown.isEmpty())
    }

    /** A Lists store that knows one owner per proof. */
    private class Store(var owner: String? = null, var lockFails: Boolean = false) : ListsLocalCapability by FakeListsLocalCapability() {
        override suspend fun authorize(proof: String) = ListsLocalOwner(proof).also { owner = proof }
        override suspend fun account() = ListsLocalAccount(ExplicitNull(owner))
        override suspend fun lock() {
            if (lockFails) throw IllegalStateException("The Keychain is unavailable")
            owner = null
        }
    }

    @Test
    fun lockingTheListsAccountForgetsItsNotificationsEvenWhenTheLockFails() = runTest {
        var forgotten = 0
        val store = Store(owner = "one")
        val lists = store.forgettingOnLock { forgotten++ }
        lists.lock()
        assertEquals(1, forgotten)
        assertEquals(null, store.owner)
        store.lockFails = true
        assertFailsWith<IllegalStateException> { lists.lock() }
        assertEquals(2, forgotten)
    }

    @Test
    fun anotherAccountUnlockingForgetsTheLastAccountsNotifications() = runTest {
        var forgotten = 0
        val lists = Store(owner = "one").forgettingOnLock { forgotten++ }
        assertEquals("one", lists.authorize("one").ownerId)
        assertEquals(0, forgotten, "the same account confirming again keeps its reminders")
        assertEquals("two", lists.authorize("two").ownerId)
        assertEquals(1, forgotten)
        assertEquals("two", lists.account().ownerId.value)
    }
}
