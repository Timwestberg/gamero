package org.formulagen.wrapper.lists

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertIs
import kotlin.test.assertNull

class ListsJournalTest {
    private val account = ListsJournalScope("https://hovel.test", "owner-a")
    private val other = ListsJournalScope("https://hovel.test", "owner-b")

    /** Open the real SQLite driver so transaction and reopen assertions exercise storage. */
    private fun journal(driver: JdbcSqliteDriver) = ListsJournal(driver) { bytes ->
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }

    @Test fun savesProjectionAndIntentAcrossReopen() = runTest {
        val directory = Files.createTempDirectory("lists-journal")
        val file = directory.resolve("lists.db")
        try {
            val firstDriver = JdbcSqliteDriver("jdbc:sqlite:$file")
            ListsJournalSchema.create(firstDriver)
            val first = journal(firstDriver)
            assertEquals(ListsJournalResult.Saved(1, false), first.commit(account, "item", 0, "{\"title\":\"Milk\"}", "op-1", "{\"kind\":\"create\"}"))
            first.close()
            val second = journal(JdbcSqliteDriver("jdbc:sqlite:$file"))
            assertEquals(ListsJournalRecord(1, "{\"title\":\"Milk\"}"), second.read(account, "item"))
            assertEquals(listOf("op-1"), second.pending(account).map { it.operationId })
            assertNull(second.read(other, "item"))
            assertEquals(emptyList(), second.pending(other))
            second.close()
        } finally {
            Files.deleteIfExists(file)
            Files.deleteIfExists(directory)
        }
    }

    @Test fun retryDoesNotOverwriteNewerProjectionAndReceiptsSurviveAck() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ListsJournalSchema.create(driver)
        val store = journal(driver)
        try {
            store.commit(account, "item", 0, "{\"a\":1,\"b\":2}", "first", "{\"kind\":\"create\"}")
            store.commit(account, "item", 1, "{\"a\":3}", "second", "{\"kind\":\"edit\"}")
            store.acknowledge(account, "first")
            assertEquals(ListsJournalResult.Saved(1, true), store.commit(account, "item", 0, "{\"b\":2,\"a\":1}", "first", "{\"kind\":\"create\"}"))
            assertEquals(ListsJournalRecord(2, "{\"a\":3}"), store.read(account, "item"))
            assertEquals(listOf("second"), store.pending(account).map { it.operationId })
            assertIs<ListsJournalResult.OperationConflict>(store.commit(account, "item", 2, "{}", "first", "{}"))
            assertEquals(ListsJournalResult.Stale(2), store.commit(account, "item", 1, "{}", "stale", "{}"))
        } finally { store.close() }
    }

    @Test fun failedOutboxWriteRollsBackProjection() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ListsJournalSchema.create(driver)
        driver.execute(null, "CREATE TRIGGER fail_outbox BEFORE INSERT ON lists_local_operations BEGIN SELECT RAISE(ABORT, 'disk failure'); END", 0)
        val store = journal(driver)
        try {
            assertFails { store.commit(account, "item", 0, "{}", "op", "{}") }
            assertNull(store.read(account, "item"))
            assertEquals(emptyList(), store.pending(account))
        } finally { store.close() }
    }

    @Test fun purgeStripsQueuedContentAndPreventsResurrection() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ListsJournalSchema.create(driver)
        val store = journal(driver)
        try {
            store.commit(account, "item", 0, "{\"title\":\"Private\"}", "first", "{\"title\":\"Private\"}")
            assertEquals(ListsJournalResult.Saved(2, false), store.purge(account, "item", 1, "purge"))
            assertEquals(ListsJournalRecord(2, null), store.read(account, "item"))
            assertEquals(listOf(ListsJournalOperation("purge", "item", "{\"kind\":\"purge\"}")), store.pending(account))
            assertIs<ListsJournalResult.Purged>(store.commit(account, "item", 0, "{\"title\":\"Private\"}", "first", "{\"title\":\"Private\"}"))
            assertIs<ListsJournalResult.Purged>(store.commit(account, "item", 2, "{}", "late", "{}"))
            assertEquals(ListsJournalResult.Saved(2, true), store.purge(account, "item", 1, "purge"))
        } finally { store.close() }
    }

    @Test fun rejectsInvalidPayloadsWithoutSaving() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ListsJournalSchema.create(driver)
        val store = journal(driver)
        try {
            assertFails { store.commit(account, "item", 0, "[]", "op", "{}") }
            assertFails { store.commit(account, "item", 0, "{}", "op", "invalid") }
            assertFails { store.commit(account, "item", -1, "{}", "op", "{}") }
            assertNull(store.read(account, "item"))
        } finally { store.close() }
    }
}
