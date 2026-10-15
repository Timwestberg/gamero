package org.formulagen.wrapper.bridge

/** A capability this host provides, or an explicit `Unsupported`. Nothing throws at call time. */
sealed interface Capability<out T> {
    data class Supported<out T>(val impl: T) : Capability<T>
    data object Unsupported : Capability<Nothing>
}

/**
 * How one capability meets the bridge: its handlers, its events, and how to
 * start the events. Each capability declares one beside its interface, so the
 * router holds no capability code. The methods are the handlers' methods, so
 * the compiler checks that each one has code.
 */
internal class CapabilityBinder<T>(
    internal val handlers: List<CapabilityHandler<T>>,
    internal val events: List<BridgeEvent<*>> = emptyList(),
    /** Starts the capability's events. The router calls it once and closes the result on [BridgeRouter.close]. */
    internal val observe: ((capability: T, events: EventSink) -> AutoCloseable)? = null,
) {
    internal fun bind(capability: Capability<T>): BoundCapability {
        val methods = handlers.map { it.method }
        return when (capability) {
            is Capability.Supported -> BoundCapability(
                methods = methods,
                events = events,
                handlers = handlers.map { it.bind(capability.impl) },
                observe = observe?.let { start -> { sink -> start(capability.impl, sink) } },
            )
            Capability.Unsupported -> BoundCapability(methods, events, handlers = null, observe = null)
        }
    }
}

/** A capability as this host has it. [handlers] is null when the host does not support it. */
internal class BoundCapability(
    val methods: List<BridgeMethod<*, *>>,
    val events: List<BridgeEvent<*>>,
    val handlers: List<MethodHandler>?,
    val observe: ((EventSink) -> AutoCloseable)?,
)

/**
 * One product's capability as this host has it, made by [CapabilityBinder.pack].
 * A product passes its packs to the router when it starts the bridge, so the
 * shared core never names a product. A product passes
 * `pack(Capability.Unsupported)` on a platform where it has no such capability,
 * so its methods answer `unsupported`, not `unknown-method`.
 */
class CapabilityPack internal constructor(internal val bound: BoundCapability)

internal fun <T> CapabilityBinder<T>.pack(capability: Capability<T>): CapabilityPack = CapabilityPack(bind(capability))

/**
 * The platform set: every capability that each platform must decide on, as a
 * constructor parameter without a default. A platform that forgets one does
 * not compile; a platform without one passes [Capability.Unsupported].
 * Product capabilities, such as Hovel's companion, are [CapabilityPack]s
 * instead. The `HOST_BRIDGE_PARITY` table in event-contracts says which
 * platform supports what, and the parity test checks both sets.
 *
 * To add a platform capability, add its parameter here and its binder to [bound].
 */
class HostCapabilities(
    val platform: HostPlatform,
    val appVersion: String,
    val links: Capability<LinkOpener>,
    val haptics: Capability<Haptics>,
    val lifecycle: Capability<Lifecycle>,
) {
    internal val bound: List<BoundCapability> = listOf(
        LinkMethods.binder.bind(links),
        HapticMethods.binder.bind(haptics),
        LifecycleEvents.binder.bind(lifecycle),
    )
}
