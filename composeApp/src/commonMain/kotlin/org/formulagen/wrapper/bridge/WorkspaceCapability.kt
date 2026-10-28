package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A companion's project folder, where its working members work. Native code owns the folder: a
 * page never supplies a path and never sees one. Every method answers the folder's status, and a
 * change applies to the companion's working members from their next message. Desktop only.
 */
interface WorkspaceCapability {
    /** Opens the Mac's folder picker natively; the chosen folder, or the unchanged status when cancelled. */
    suspend fun choose(scope: WorkspaceScope): WorkspaceStatus
    /** Forgets the folder. It does not touch the folder or its files. */
    suspend fun clear(scope: WorkspaceScope): WorkspaceStatus
    suspend fun status(scope: WorkspaceScope): WorkspaceStatus
}

/** Bounds of what a page may see about a project folder. */
object WorkspaceLimits { // mirrors WORKSPACE_LIMITS (limits.json)
    const val FOLDER_NAME = 255
}

/** One profile's folder for one companion. */
@Serializable
data class WorkspaceScope(val companionId: String, val profile: String)

/** What a page may show about a folder: its [name], the last part of its path, and whether it still [exists]. */
@Serializable
data class WorkspaceFolder(val exists: Boolean, val name: String) {
    init { require(name.length <= WorkspaceLimits.FOLDER_NAME) }
}

/**
 * [folder] is null when none is chosen. The contract's `folder` is `nullable`, not `optional`, so the
 * key is always written, as `"folder": null` when there is none ([WorkspaceStatusSerializer]).
 */
@Serializable(with = WorkspaceStatusSerializer::class)
data class WorkspaceStatus(val folder: WorkspaceFolder?)

/** The bridge leaves out null fields, so the status is written through [Wire], whose `folder` writes its own `null`. */
internal object WorkspaceStatusSerializer : KSerializer<WorkspaceStatus> {
    @Serializable
    private class Wire(val folder: Chosen)

    @Serializable(with = ChosenSerializer::class)
    private class Chosen(val folder: WorkspaceFolder?)

    @OptIn(ExperimentalSerializationApi::class)
    private object ChosenSerializer : KSerializer<Chosen> {
        override val descriptor: SerialDescriptor = WorkspaceFolder.serializer().descriptor.nullable
        override fun serialize(encoder: Encoder, value: Chosen) =
            value.folder?.let { encoder.encodeSerializableValue(WorkspaceFolder.serializer(), it) } ?: encoder.encodeNull()
        override fun deserialize(decoder: Decoder) =
            Chosen(if (decoder.decodeNotNullMark()) decoder.decodeSerializableValue(WorkspaceFolder.serializer()) else decoder.decodeNull())
    }

    override val descriptor: SerialDescriptor = Wire.serializer().descriptor
    override fun serialize(encoder: Encoder, value: WorkspaceStatus) = encoder.encodeSerializableValue(Wire.serializer(), Wire(Chosen(value.folder)))
    override fun deserialize(decoder: Decoder) = WorkspaceStatus(decoder.decodeSerializableValue(Wire.serializer()).folder.folder)
}

/** Mirrors event-contracts/host-bridge/workspace.ts. */
object WorkspaceMethods {
    private fun valid(scope: WorkspaceScope) = SharedRules.isProfileId(scope.profile) && BridgeProtocol.UUID_PATTERN.matches(scope.companionId)
    private fun method(name: String, timeout: Duration = BridgeMethod.DEFAULT_TIMEOUT) =
        BridgeMethod("workspace.$name", WorkspaceScope.serializer(), WorkspaceStatus.serializer(), timeout = timeout, accepts = ::valid)

    /** The person may browse for a while before choosing. */
    val CHOOSE = method("choose", timeout = 10.minutes)
    val CLEAR = method("clear")
    val STATUS = method("status")

    fun pack(workspace: Capability<WorkspaceCapability>): CapabilityPack = binder.pack(workspace)

    internal val binder = CapabilityBinder("workspace", listOf<CapabilityHandler<WorkspaceCapability>>(
        CHOOSE.handle { workspace, scope -> workspace.choose(scope) },
        CLEAR.handle { workspace, scope -> workspace.clear(scope) },
        STATUS.handle { workspace, scope -> workspace.status(scope) },
    ))
}
