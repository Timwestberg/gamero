package org.formulagen.wrapper.lists

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal const val LISTS_JOURNAL_MAX_PAYLOAD = 262_144
private const val MAX_REVISION = 9_007_199_254_740_991L

/** Internal storage scope, supplied by the authenticated account coordinator. */
internal data class ListsJournalScope(val serverOrigin: String, val ownerId: String) {
    init {
        require(serverOrigin.isNotBlank() && serverOrigin.length <= 2048)
        require(ownerId.isNotBlank() && ownerId.length <= 128)
    }
    val key: String get() = JsonArray(listOf(JsonPrimitive(serverOrigin), JsonPrimitive(ownerId))).toString()
}

/** A local projection; null value is a permanent, content-free tombstone. */
internal data class ListsJournalRecord(val revision: Long, val value: String?)

/** Immutable outgoing intent; acknowledged payloads are removed. */
internal data class ListsJournalOperation(val operationId: String, val entityId: String, val payload: String)

internal sealed interface ListsJournalResult {
    data class Saved(val revision: Long, val replayed: Boolean) : ListsJournalResult
    data class Stale(val revision: Long) : ListsJournalResult
    data object OperationConflict : ListsJournalResult
    data object Purged : ListsJournalResult
}

/** Transactional persistence only; domain validation, authorization and sync belong to its caller. */
internal class ListsJournal(private val driver: SqlDriver, private val sha256: (ByteArray) -> String) {
    private val mutex = Mutex()
    private val transactions = object : TransacterImpl(driver) {}

    /** Read one account-scoped projection without consuming outgoing work. */
    suspend fun read(scope: ListsJournalScope, entityId: String): ListsJournalRecord? = mutex.withLock {
        requireId(entityId)
        record(scope, entityId)
    }

    /** Atomically store a projection and durable intent, or reject a stale/reused operation. */
    suspend fun commit(
        scope: ListsJournalScope,
        entityId: String,
        expectedRevision: Long,
        value: String,
        operationId: String,
        payload: String,
    ): ListsJournalResult = mutex.withLock {
        write(scope, entityId, expectedRevision, canonicalObject(value), operationId, canonicalObject(payload))
    }

    /** Permanently remove content and supersede older queued payloads in the same transaction. */
    suspend fun purge(
        scope: ListsJournalScope,
        entityId: String,
        expectedRevision: Long,
        operationId: String,
    ): ListsJournalResult = mutex.withLock {
        write(scope, entityId, expectedRevision, null, operationId, "{\"kind\":\"purge\"}")
    }

    /** Read a bounded outgoing prefix in durable insertion order. */
    suspend fun pending(scope: ListsJournalScope, limit: Int = 100): List<ListsJournalOperation> = mutex.withLock {
        require(limit in 1..100)
        driver.executeQuery(null,
            "SELECT operation_id, entity_id, payload FROM lists_local_operations WHERE scope = ? AND payload IS NOT NULL ORDER BY sequence LIMIT ?",
            { cursor ->
                val rows = mutableListOf<ListsJournalOperation>()
                while (cursor.next().value) rows += ListsJournalOperation(cursor.getString(0)!!, cursor.getString(1)!!, cursor.getString(2)!!)
                QueryResult.Value(rows.toList())
            }, 2, { bindString(0, scope.key); bindLong(1, limit.toLong()) }).value
    }

    /** Drop acknowledged intent content while retaining its replay fingerprint. */
    suspend fun acknowledge(scope: ListsJournalScope, operationId: String) = mutex.withLock {
        requireId(operationId)
        driver.execute(null, "UPDATE lists_local_operations SET payload = NULL WHERE scope = ? AND operation_id = ?", 2) {
            bindString(0, scope.key); bindString(1, operationId)
        }.value
        Unit
    }

    /** Release this handle; callers must stop workers before closing it. */
    suspend fun close() = mutex.withLock { driver.close() }

    /** Keep the SQL transaction synchronous on its owning thread. */
    private fun write(scope: ListsJournalScope, entityId: String, expected: Long, value: String?, operationId: String, payload: String): ListsJournalResult {
        requireId(entityId); requireId(operationId)
        require(expected in 0 until MAX_REVISION)
        val fingerprint = sha256(JsonArray(listOf(JsonPrimitive(entityId), JsonPrimitive(expected), value?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull, JsonPrimitive(payload))).toString().encodeToByteArray())
        require(fingerprint.matches(Regex("[a-f0-9]{64}")))
        return transactions.transactionWithResult {
            val current = record(scope, entityId)
            val receipt = driver.executeQuery(null,
                "SELECT fingerprint, revision FROM lists_local_operations WHERE scope = ? AND operation_id = ?",
                { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0)!! to cursor.getLong(1)!! else null) },
                2, { bindString(0, scope.key); bindString(1, operationId) }).value
            if (receipt != null && receipt.first != fingerprint) return@transactionWithResult ListsJournalResult.OperationConflict
            if (current != null && current.value == null && value != null) return@transactionWithResult ListsJournalResult.Purged
            if (receipt != null) return@transactionWithResult ListsJournalResult.Saved(receipt.second, true)
            if (current != null && current.value == null) return@transactionWithResult ListsJournalResult.Purged
            if ((current?.revision ?: 0) != expected) return@transactionWithResult ListsJournalResult.Stale(current?.revision ?: 0)
            val revision = expected + 1
            driver.execute(null,
                "INSERT OR REPLACE INTO lists_local_records(scope, entity_id, revision, value) VALUES (?, ?, ?, ?)", 4) {
                bindString(0, scope.key); bindString(1, entityId); bindLong(2, revision); bindString(3, value)
            }.value
            if (value == null) driver.execute(null,
                "UPDATE lists_local_operations SET payload = NULL WHERE scope = ? AND entity_id = ?", 2) {
                bindString(0, scope.key); bindString(1, entityId)
            }.value
            driver.execute(null,
                "INSERT INTO lists_local_operations(scope, operation_id, entity_id, fingerprint, revision, payload) VALUES (?, ?, ?, ?, ?, ?)", 6) {
                bindString(0, scope.key); bindString(1, operationId); bindString(2, entityId)
                bindString(3, fingerprint); bindLong(4, revision); bindString(5, payload)
            }.value
            ListsJournalResult.Saved(revision, false)
        }
    }

    /** Read only within the current serialized operation or transaction. */
    private fun record(scope: ListsJournalScope, entityId: String): ListsJournalRecord? = driver.executeQuery(null,
        "SELECT revision, value FROM lists_local_records WHERE scope = ? AND entity_id = ?",
        { cursor -> QueryResult.Value(if (cursor.next().value) ListsJournalRecord(cursor.getLong(0)!!, cursor.getString(1)) else null) },
        2, { bindString(0, scope.key); bindString(1, entityId) }).value
}

/** Stable object serialization for immutable operation identity, independent of key order. */
internal fun canonicalObject(text: String): String {
    require(text.length <= LISTS_JOURNAL_MAX_PAYLOAD)
    val parsed = Json.parseToJsonElement(text)
    require(parsed is JsonObject)
    return canonicalElement(parsed, 0).toString().also { require(it.length <= LISTS_JOURNAL_MAX_PAYLOAD) }
}

/** Bound nesting while preserving array order and sorting object keys. */
private fun canonicalElement(value: JsonElement, depth: Int): JsonElement {
    require(depth <= 32)
    return when (value) {
        is JsonObject -> JsonObject(value.keys.sorted().associateWith { canonicalElement(value.getValue(it), depth + 1) })
        is JsonArray -> JsonArray(value.map { canonicalElement(it, depth + 1) })
        else -> value
    }
}

/** IDs are opaque internal storage keys, never SQL fragments. */
private fun requireId(value: String) = require(value.isNotBlank() && value.length <= 128)
