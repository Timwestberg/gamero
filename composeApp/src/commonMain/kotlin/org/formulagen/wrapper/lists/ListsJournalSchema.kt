package org.formulagen.wrapper.lists

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

/** Additive local schema; no existing Lists data is migrated into offline storage implicitly. */
internal object ListsJournalSchema : SqlSchema<QueryResult.Value<Unit>> {
    override val version = 2L

    /** Create projection and outgoing-intent tables in a new database. */
    override fun create(driver: SqlDriver): QueryResult.Value<Unit> {
        driver.execute(null, """
            CREATE TABLE lists_local_records (
                scope TEXT NOT NULL, entity_id TEXT NOT NULL,
                revision INTEGER NOT NULL CHECK(revision > 0), value TEXT,
                PRIMARY KEY(scope, entity_id)
            )
        """.trimIndent(), 0).value
        driver.execute(null, """
            CREATE TABLE lists_local_operations (
                sequence INTEGER PRIMARY KEY AUTOINCREMENT,
                scope TEXT NOT NULL, operation_id TEXT NOT NULL, entity_id TEXT NOT NULL,
                fingerprint TEXT NOT NULL, revision INTEGER NOT NULL, payload TEXT,
                UNIQUE(scope, operation_id)
            )
        """.trimIndent(), 0).value
        driver.execute(null, "CREATE INDEX lists_local_pending ON lists_local_operations(scope, sequence)", 0).value
        createReplica(driver)
        return QueryResult.Value(Unit)
    }

    private fun createReplica(driver: SqlDriver) {
        driver.execute(null, "CREATE TABLE lists_local_replica (scope TEXT PRIMARY KEY NOT NULL, value TEXT NOT NULL)", 0).value
    }

    /** Refuse unsupported versions instead of silently discarding local work. */
    override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> {
        require(oldVersion in 1..version && newVersion == version) { "Unsupported Lists journal migration" }
        if (oldVersion < 2) createReplica(driver)
        return QueryResult.Value(Unit)
    }
}
