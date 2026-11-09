package org.formulagen.wrapper.lists

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

/** Atomic canonical base, outbox and pull checkpoint for one authorized account. */
internal class ListsReplicaSql(private val driver: SqlDriver) {
    private val mutex = Mutex()
    private val transactions = object : TransacterImpl(driver) {}
    init {
        // This pragma returns a row. Pin the query to the writer connection.
        transactions.transaction {
            check(driver.executeQuery(null, "PRAGMA secure_delete = ON", { cursor ->
                QueryResult.Value(cursor.next().value && cursor.getLong(0) == 1L)
            }, 0).value) { "Could not enable secure deletion for offline Lists" }
        }
        driver.execute(null, "PRAGMA synchronous = FULL", 0).value
    }

    suspend fun read(scope: ListsJournalScope): String? = mutex.withLock { readNow(scope) }

    /** Compare local generations inside the same SQLite transaction as the durable snapshot replacement. */
    suspend fun replace(scope: ListsJournalScope, expected: Long, value: String) = mutex.withLock {
        require(expected in 0 until 9_007_199_254_740_991L)
        require(value.length <= 33_554_432)
        val parsed = Json.parseToJsonElement(value).jsonObject
        require(parsed.keys == setOf("revision", "cursor", "ready", "records", "operations", "acknowledged", "purged"))
        require(parsed.getValue("revision").jsonPrimitive.long == expected + 1)
        require(parsed.getValue("cursor").jsonPrimitive.content.matches(Regex("0|[1-9][0-9]{0,18}")))
        require(parsed.getValue("ready").jsonPrimitive.booleanOrNull != null)
        for (name in listOf("records", "operations", "acknowledged", "purged")) require(parsed[name] is JsonArray)
        var compact = false
        transactions.transaction {
            val previous = readNow(scope)?.let { Json.parseToJsonElement(it).jsonObject }
            val current = previous?.getValue("revision")?.jsonPrimitive?.long ?: 0
            check(current == expected) { "Lists changed; retry the local save" }
            compact = parsed.getValue("purged").jsonArray.size > (previous?.get("purged")?.jsonArray?.size ?: 0)
            driver.execute(null, "INSERT OR REPLACE INTO lists_local_replica (scope, value) VALUES (?, ?)", 2) {
                bindString(0, scope.key); bindString(1, value)
            }.value
        }
        // The commit is already durable. Compaction is best-effort and must never turn it into a failed save.
        if (compact) runCatching {
            driver.executeQuery(null, "PRAGMA wal_checkpoint(TRUNCATE)", { cursor ->
                cursor.next()
                QueryResult.Value(Unit)
            }, 0).value
            driver.execute(null, "VACUUM", 0).value
        }
    }
    private fun readNow(scope: ListsJournalScope): String? = driver.executeQuery(null,
        "SELECT value FROM lists_local_replica WHERE scope = ?", { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
        1, { bindString(0, scope.key) }).value

    suspend fun close() = mutex.withLock { driver.close() }
}
