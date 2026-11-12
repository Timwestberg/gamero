package org.formulagen.wrapper.lists

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.*

class ListsReplicaSqlTest {
    private val account = ListsJournalScope("https://hovel.test", "owner-a")
    private val other = ListsJournalScope("https://hovel.test", "owner-b")
    private fun snapshot(revision: Int, cursor: String = "0") = """{"revision":$revision,"cursor":"$cursor","ready":true,"records":[],"operations":[],"acknowledged":[],"purged":[]}"""

    @Test fun survivesRestartAndRejectsStaleReplacement() = runTest {
        val directory = Files.createTempDirectory("lists-replica")
        val file = directory.resolve("lists.db")
        try {
            val driver = JdbcSqliteDriver("jdbc:sqlite:$file")
            ListsJournalSchema.create(driver)
            val first = ListsReplicaSql(driver)
            first.replace(account, 0, snapshot(1, "7")); first.close()
            val second = ListsReplicaSql(JdbcSqliteDriver("jdbc:sqlite:$file"))
            assertEquals(snapshot(1, "7"), second.read(account))
            assertNull(second.read(other))
            assertFails { second.replace(account, 0, snapshot(1, "8")) }
            assertEquals(snapshot(1, "7"), second.read(account))
            assertFails { second.replace(account, 1, snapshot(3)) }
            second.replace(account, 1, snapshot(2, "8"))
            second.close()
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory) }
    }

    @Test fun storageFailureRollsBackCursorAndPayloadTogether() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        ListsJournalSchema.create(driver)
        val store = ListsReplicaSql(driver)
        store.replace(account, 0, snapshot(1, "4"))
        driver.execute(null, "CREATE TRIGGER fail_replica BEFORE INSERT ON lists_local_replica BEGIN SELECT RAISE(ABORT, 'disk full'); END", 0)
        assertFails { store.replace(account, 1, snapshot(2, "5")) }
        assertEquals(snapshot(1, "4"), store.read(account))
        store.close()
    }

    @Test fun migrationKeepsExistingJournalAndRejectsFutureVersions() = runTest {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        driver.execute(null, "CREATE TABLE lists_local_records (scope TEXT, value TEXT)", 0)
        driver.execute(null, "INSERT INTO lists_local_records VALUES ('kept', 'draft')", 0)
        ListsJournalSchema.migrate(driver, 1, 2)
        val store = ListsReplicaSql(driver)
        store.replace(account, 0, snapshot(1))
        assertEquals(snapshot(1), store.read(account))
        assertFails { ListsJournalSchema.migrate(driver, 3, 2) }
        store.close()
    }
}
