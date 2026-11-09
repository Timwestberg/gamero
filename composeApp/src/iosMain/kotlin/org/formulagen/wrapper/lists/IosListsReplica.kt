@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.uuid.ExperimentalUuidApi::class)
package org.formulagen.wrapper.lists

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseConfiguration
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.phone.IosDeviceHttp
import org.formulagen.wrapper.phone.IosDeviceVault
import org.formulagen.wrapper.phone.DeviceRequest
import platform.Foundation.*
import kotlin.uuid.Uuid

/** Native identity comes from a one-use server proof, and survives offline restarts in the device-only Keychain. */
internal class IosListsReplica(appUrl: String) : ListsLocalCapability {
    private val origin = TrustedOrigin(appUrl).rule
    private val vault = IosDeviceVault("org.formulagen.hovel.offline-lists", origin)
    private val http = IosDeviceHttp()
    private val mutex = Mutex()
    private var transfer: Transfer? = null
    private var store: ListsReplicaSql? = null
    private data class Transfer(val id: String, val ownerId: String, val expected: Long, val size: Int, val content: StringBuilder = StringBuilder())

    override suspend fun authorize(proof: String): ListsLocalOwner = mutex.withLock {
        val response = http.send(DeviceRequest("POST", "$origin/api/hovel/v1/hovel/lists/sync/unlock",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"), buildJsonObject { put("proof", proof) }.toString()))
        check(response.status in 200..299) { "Confirm the account again before opening offline Lists" }
        val owner = Json.parseToJsonElement(response.body).jsonObject.getValue("data").jsonObject.getValue("ownerId").jsonPrimitive.content
        check(SharedRules.isRecordId(owner))
        transfer = null; vault.write(owner); ListsLocalOwner(owner)
    }
    override suspend fun account(): ListsLocalAccount = mutex.withLock { ListsLocalAccount(ExplicitNull(vault.read())) }
    override suspend fun lock() = mutex.withLock { transfer = null; vault.clear(); store?.close(); store = null }
    /** Navigation abandons an incomplete write without changing the saved snapshot. */
    fun invalidatePending() { transfer = null }
    private fun owner(): String = checkNotNull(vault.read()) { "Offline account is locked" }
    private fun storage(): ListsReplicaSql = store ?: openProtectedStore().also { store = it }
    override suspend fun read(expectedOwnerId: String, offset: Int): ListsLocalPage = mutex.withLock {
        check(owner() == expectedOwnerId) { "Lists account changed" }
        val text = storage().read(ListsJournalScope(origin, expectedOwnerId)) ?: ""
        require(offset <= text.length)
        val revision = if (text.isEmpty()) 0 else Json.parseToJsonElement(text).jsonObject.getValue("revision").jsonPrimitive.long
        ListsLocalPage(revision, text.length, text.substring(offset, minOf(text.length, offset + 8_000)))
    }
    override suspend fun begin(expectedOwnerId: String, expectedRevision: Long, size: Int): ListsLocalTransfer = mutex.withLock {
        check(owner() == expectedOwnerId) { "Lists account changed" }
        val pending = Transfer(Uuid.random().toString(), expectedOwnerId, expectedRevision, size)
        transfer = pending; ListsLocalTransfer(pending.id)
    }
    override suspend fun append(args: ListsLocalAppend) = mutex.withLock {
        val pending = checkNotNull(transfer)
        check(pending.id == args.transferId && pending.ownerId == owner())
        check(args.offset == pending.content.length && args.chunk.length + args.offset <= pending.size)
        pending.content.append(args.chunk); Unit
    }
    override suspend fun commit(transferId: String) = mutex.withLock {
        val pending = checkNotNull(transfer)
        check(pending.id == transferId && pending.ownerId == owner() && pending.content.length == pending.size)
        storage().replace(ListsJournalScope(origin, pending.ownerId), pending.expected, pending.content.toString())
        transfer = null
    }
}

/** Apply complete data protection to a dedicated directory before SQLite creates its database and journals. */
private fun openProtectedStore(): ListsReplicaSql {
    val files = NSFileManager.defaultManager
    val support = files.URLForDirectory(NSApplicationSupportDirectory, NSUserDomainMask, null, true, null)!!
    val directory = support.URLByAppendingPathComponent("HovelOfflineLists", true)!!
    check(files.createDirectoryAtURL(directory, true, mapOf(NSFileProtectionKey to NSFileProtectionComplete), null))
    check(files.setAttributes(mapOf(NSFileProtectionKey to NSFileProtectionComplete), directory.path!!, null))
    check(directory.setResourceValue(true, NSURLIsExcludedFromBackupKey, null))
    return ListsReplicaSql(NativeSqliteDriver(ListsJournalSchema, "lists.db", onConfiguration = { config ->
        config.copy(extendedConfig = DatabaseConfiguration.Extended(basePath = directory.path))
    }))
}
