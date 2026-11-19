package org.formulagen.wrapper.bridge

/**
 * The Android column of `HOST_BRIDGE_PARITY` for the platform set. Haptics
 * and lifecycle arrive in phase C of the host bridge plan; until then they
 * answer `unsupported`. Product capabilities come from the product.
 *
 * [links] is passed in because it needs a `Context`: the start code passes
 * [AndroidLinkOpener], and the parity test, which has no `Context`, a fake.
 */
internal fun androidHostCapabilities(appVersion: String, links: LinkOpener): HostCapabilities = HostCapabilities(
    platform = HostPlatform.ANDROID,
    appVersion = appVersion,
    links = Capability.Supported(links),
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)
