package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration.Companion.minutes

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
    /** Waits for the person to approve the connection and the Keychain prompt. */
    val PREPARE = BridgeMethod(
        "companion.prepareConnection", CompanionPrepareArgs.serializer(), CompanionRegistration.serializer(), timeout = 5.minutes,
    ) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId)
    }
    /** Reads the Keychain, which can show a prompt. */
    val ACTIVATE = BridgeMethod(
        "companion.activateConnection", CompanionActivateArgs.serializer(), JsonObject.serializer(), timeout = 2.minutes,
    ) {
        BridgeProtocol.UUID_PATTERN.matches(it.ownerId) && BridgeProtocol.UUID_PATTERN.matches(it.hostId)
    }

    /** Hovel's companion as a product pack. Hovel passes it on every platform, `Unsupported` where it has none. */
    fun pack(companion: Capability<CompanionCapability>): CapabilityPack = binder.pack(companion)

    internal val binder = CapabilityBinder(
        "companion",
        listOf<CapabilityHandler<CompanionCapability>>(
            CAPABILITIES.handle { _, _ -> CompanionCapabilitiesResult() },
            PREPARE.handle { companion, args -> companion.prepareConnection(args.ownerId) },
            ACTIVATE.handle { companion, args -> companion.activateConnection(args.ownerId, args.hostId) },
        ),
    )
}
