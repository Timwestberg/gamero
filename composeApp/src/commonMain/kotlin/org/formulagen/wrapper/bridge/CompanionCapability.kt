package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Hovel's Mac companion. A call while another runs throws [BridgeBusyException].
 * Credentials never cross this interface; only their hash does.
 */
interface CompanionCapability {
    suspend fun prepareConnection(ownerId: String): CompanionRegistration
    suspend fun activateConnection(ownerId: String, hostId: String): JsonObject
}

@Serializable
data class CompanionCapabilitiesResult(val connectComputer: Boolean = true, val cloud: Boolean = false, val sandbox: String = "read-only")

@Serializable
data class CompanionPrepareArgs(val ownerId: String)

@Serializable
data class CompanionRegistration(val id: String, val name: String, val tokenHash: String)

@Serializable
data class CompanionActivateArgs(val ownerId: String, val hostId: String)

object CompanionMethods {
    val CAPABILITIES = BridgeMethod("companion.capabilities", NoArgs.serializer(), CompanionCapabilitiesResult.serializer())
    val PREPARE = BridgeMethod("companion.prepareConnection", CompanionPrepareArgs.serializer(), CompanionRegistration.serializer()) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId)
    }
    val ACTIVATE = BridgeMethod("companion.activateConnection", CompanionActivateArgs.serializer(), JsonObject.serializer()) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId) && BridgeProtocol.UUID_PATTERN.matches(it.hostId)
    }

    internal val binder = CapabilityBinder<CompanionCapability>(listOf(CAPABILITIES, PREPARE, ACTIVATE), handlers = { companion ->
        listOf(
            CAPABILITIES.handle { CompanionCapabilitiesResult() },
            PREPARE.handle { args -> companion.prepareConnection(args.ownerId) },
            ACTIVATE.handle { args -> companion.activateConnection(args.ownerId, args.hostId) },
        )
    })
}
