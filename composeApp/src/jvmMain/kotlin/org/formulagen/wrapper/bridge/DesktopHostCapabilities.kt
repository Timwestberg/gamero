package org.formulagen.wrapper.bridge

import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HovelCompanionCapability

/**
 * The desktop column of `HOST_BRIDGE_PARITY`. Links and lifecycle arrive in
 * phase C of the host bridge plan; until then they answer `unsupported`.
 * The companion is supported only when the Codex companion is configured.
 */
internal fun desktopHostCapabilities(companion: HovelCompanion?): HostCapabilities = HostCapabilities(
    platform = HostPlatform.DESKTOP,
    appVersion = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev",
    links = Capability.Unsupported,
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
    companion = companion?.let { Capability.Supported(HovelCompanionCapability(it)) } ?: Capability.Unsupported,
)
