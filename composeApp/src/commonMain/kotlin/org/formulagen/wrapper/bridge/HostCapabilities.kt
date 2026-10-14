package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonObject

/** A capability this host provides, or an explicit `Unsupported`. Nothing throws at call time. */
sealed interface Capability<out T> {
    data class Supported<out T>(val impl: T) : Capability<T>
    data object Unsupported : Capability<Nothing>
}

/** Opens an HTTPS URL in the system browser. The router has already checked the URL. */
fun interface LinkOpener {
    suspend fun open(url: String)
}

fun interface Haptics {
    fun play(effect: HapticEffect)
}

/** Calls [onPause] and [onResume] when the host app leaves and returns to the foreground. */
fun interface Lifecycle {
    fun observe(onPause: () -> Unit, onResume: () -> Unit): AutoCloseable
}

/**
 * Hovel's Mac companion. A call while another runs throws [BridgeBusyException].
 * Credentials never cross this interface; only their hash does.
 */
interface CompanionCapability {
    suspend fun prepareConnection(ownerId: String): CompanionRegistration
    suspend fun activateConnection(ownerId: String, hostId: String): JsonObject
}

/**
 * Every capability, as a constructor parameter without a default. A platform
 * that forgets one does not compile; a platform without one passes
 * [Capability.Unsupported]. The `HOST_BRIDGE_PARITY` table in event-contracts
 * says which is which, and the parity test checks it.
 */
class HostCapabilities(
    val platform: HostPlatform,
    val appVersion: String,
    val links: Capability<LinkOpener>,
    val haptics: Capability<Haptics>,
    val lifecycle: Capability<Lifecycle>,
    val companion: Capability<CompanionCapability>,
)
