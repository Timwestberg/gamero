package org.formulagen.wrapper.bridge

/**
 * The desktop column of `HOST_BRIDGE_PARITY` for the platform set. Links and
 * lifecycle arrive in phase C of the host bridge plan; until then they answer
 * `unsupported`. Product capabilities come from the product, such as
 * [org.formulagen.wrapper.desktopProductPacks].
 */
internal fun desktopHostCapabilities(): HostCapabilities = HostCapabilities(
    platform = HostPlatform.DESKTOP,
    appVersion = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev",
    links = Capability.Unsupported,
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)
