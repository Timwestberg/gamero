package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonElement

/** A capability this host provides, or an explicit `Unsupported`. Nothing throws at call time. */
sealed interface Capability<out T> {
    data class Supported<out T>(val impl: T) : Capability<T>
    data object Unsupported : Capability<Nothing>
}

/**
 * How one capability meets the bridge: its methods, its events, and how to
 * run them. Each capability declares one beside its interface, so the router
 * holds no capability code.
 */
internal class CapabilityBinder<T>(
    val methods: List<BridgeMethod<*, *>>,
    val events: List<String> = emptyList(),
    private val handlers: (T) -> List<MethodHandler> = { emptyList() },
    /** Starts the capability's events. The router calls it once and closes the result on [BridgeRouter.close]. */
    private val observe: ((T, emit: (String, JsonElement) -> Unit) -> AutoCloseable)? = null,
) {
    fun bind(capability: Capability<T>): BoundCapability = when (capability) {
        is Capability.Supported -> BoundCapability(
            methods = methods,
            events = events,
            handlers = handlers(capability.impl).also { bound ->
                check(bound.map { it.method } == methods) { "Handlers must match the declared methods: ${methods.map { it.name }}" }
            },
            observe = observe?.let { start -> { emit -> start(capability.impl, emit) } },
        )
        Capability.Unsupported -> BoundCapability(methods, events, handlers = null, observe = null)
    }
}

/** A capability as this host has it. [handlers] is null when the host does not support it. */
internal class BoundCapability(
    val methods: List<BridgeMethod<*, *>>,
    val events: List<String>,
    val handlers: List<MethodHandler>?,
    val observe: ((emit: (String, JsonElement) -> Unit) -> AutoCloseable)?,
)

/**
 * Every capability, as a constructor parameter without a default. A platform
 * that forgets one does not compile; a platform without one passes
 * [Capability.Unsupported]. The `HOST_BRIDGE_PARITY` table in event-contracts
 * says which is which, and the parity test checks it.
 *
 * To add a capability, add its parameter here and its binder to [bound].
 */
class HostCapabilities(
    val platform: HostPlatform,
    val appVersion: String,
    val links: Capability<LinkOpener>,
    val haptics: Capability<Haptics>,
    val lifecycle: Capability<Lifecycle>,
    val companion: Capability<CompanionCapability>,
) {
    internal val bound: List<BoundCapability> = listOf(
        LinkMethods.binder.bind(links),
        HapticMethods.binder.bind(haptics),
        LifecycleEvents.binder.bind(lifecycle),
        CompanionMethods.binder.bind(companion),
    )
}
