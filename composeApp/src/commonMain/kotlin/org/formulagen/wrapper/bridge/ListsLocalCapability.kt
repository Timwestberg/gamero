package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable

/** Account-scoped native persistence; owner assertions never select storage; no raw SQL, path or arbitrary key is accepted. */
internal interface ListsLocalCapability {
    suspend fun authorize(proof: String): ListsLocalOwner
    suspend fun account(): ListsLocalAccount
    suspend fun lock()
    suspend fun read(expectedOwnerId: String, offset: Int): ListsLocalPage
    suspend fun begin(expectedOwnerId: String, expectedRevision: Long, size: Int): ListsLocalTransfer
    suspend fun append(args: ListsLocalAppend)
    suspend fun commit(transferId: String)
}
@Serializable internal data class ListsLocalProof(val proof: String)
@Serializable internal data class ListsLocalOwner(val ownerId: String)
@Serializable internal data class ListsLocalAccount(val ownerId: ExplicitNull<String>)
@Serializable internal data class ListsLocalOffset(val expectedOwnerId: String, val offset: Int)
@Serializable internal data class ListsLocalPage(val revision: Long, val size: Int, val chunk: String)
@Serializable internal data class ListsLocalBegin(val expectedOwnerId: String, val expectedRevision: Long, val size: Int)
@Serializable internal data class ListsLocalTransfer(val transferId: String)
@Serializable internal data class ListsLocalAppend(val transferId: String, val offset: Int, val chunk: String)

/** Transfer frames are smaller than the global 64 KiB bridge limit; commits are atomic. */
internal object ListsLocalMethods {
    val AUTHORIZE = BridgeMethod("listsLocal.authorize", ListsLocalProof.serializer(), ListsLocalOwner.serializer()) { it.proof.matches(Regex("[a-f0-9]{64}")) }
    val ACCOUNT = BridgeMethod("listsLocal.account", NoArgs.serializer(), ListsLocalAccount.serializer())
    val LOCK = BridgeMethod("listsLocal.lock", NoArgs.serializer(), NoResult.serializer())
    val READ = BridgeMethod("listsLocal.read", ListsLocalOffset.serializer(), ListsLocalPage.serializer()) { SharedRules.isRecordId(it.expectedOwnerId) && it.offset >= 0 }
    val BEGIN = BridgeMethod("listsLocal.begin", ListsLocalBegin.serializer(), ListsLocalTransfer.serializer()) { SharedRules.isRecordId(it.expectedOwnerId) && it.expectedRevision in 0 until 9_007_199_254_740_991L && it.size in 1..33_554_432 }
    val APPEND = BridgeMethod("listsLocal.append", ListsLocalAppend.serializer(), NoResult.serializer()) { SharedRules.isRecordId(it.transferId) && it.offset >= 0 && it.chunk.length <= 16_000 }
    val COMMIT = BridgeMethod("listsLocal.commit", ListsLocalTransfer.serializer(), NoResult.serializer()) { SharedRules.isRecordId(it.transferId) }
    fun pack(capability: Capability<ListsLocalCapability>): CapabilityPack = binder.pack(capability)
    internal val binder = CapabilityBinder("listsLocal", listOf<CapabilityHandler<ListsLocalCapability>>(
        AUTHORIZE.handle { capability, args -> capability.authorize(args.proof) },
        ACCOUNT.handle { capability, _ -> capability.account() },
        LOCK.handle { capability, _ -> capability.lock(); NoResult },
        READ.handle { capability, args -> capability.read(args.expectedOwnerId, args.offset) },
        BEGIN.handle { capability, args -> capability.begin(args.expectedOwnerId, args.expectedRevision, args.size) },
        APPEND.handle { capability, args -> capability.append(args); NoResult },
        COMMIT.handle { capability, args -> capability.commit(args.transferId); NoResult },
    ))
}
