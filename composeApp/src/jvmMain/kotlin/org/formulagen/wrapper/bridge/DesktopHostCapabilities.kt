package org.formulagen.wrapper.bridge

/**
 * The desktop column of `HOST_BRIDGE_PARITY` for the platform set. Desktop has
 * no haptics. Lifecycle arrives in phase C of the host bridge plan; until then
 * it answers `unsupported`. Product capabilities come from the product, such as
 * [org.formulagen.wrapper.desktopProductPacks].
 */
internal fun desktopHostCapabilities(): HostCapabilities = HostCapabilities(
    platform = HostPlatform.DESKTOP,
    appVersion = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev",
    links = Capability.Supported(DesktopLinkOpener()),
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)
