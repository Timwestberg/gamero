package org.formulagen.wrapper.bridge

/**
 * The Android column of `HOST_BRIDGE_PARITY` for the platform set. Haptics,
 * links and lifecycle arrive in phase C of the host bridge plan; until then
 * they answer `unsupported`. Product capabilities come from the product.
 */
internal fun androidHostCapabilities(appVersion: String): HostCapabilities = HostCapabilities(
    platform = HostPlatform.ANDROID,
    appVersion = appVersion,
    links = Capability.Unsupported,
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)
