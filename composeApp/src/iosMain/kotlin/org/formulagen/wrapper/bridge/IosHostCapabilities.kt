package org.formulagen.wrapper.bridge

/**
 * The iOS column of `HOST_BRIDGE_PARITY` for the platform set. Haptics and
 * lifecycle arrive in phase C of the host bridge plan; until then they answer
 * `unsupported`. Product capabilities come from the product.
 */
internal fun iosHostCapabilities(appVersion: String): HostCapabilities = HostCapabilities(
    platform = HostPlatform.IOS,
    appVersion = appVersion,
    links = Capability.Supported(IosLinkOpener()),
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)

/**
 * Hovel's capability packs on iOS. iOS has none of the desktop product capabilities, so their methods answer
 * `unsupported`. Phone approvals are iOS's own; they answer `unsupported` until phone approvals ship.
 */
internal fun iosHovelBridgePacks(): List<CapabilityPack> = listOf(
    CompanionMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.BrainMethods.pack(Capability.Unsupported),
    ChatMethods.pack(Capability.Unsupported),
    ProviderMethods.pack(Capability.Unsupported),
    PetMethods.pack(Capability.Unsupported),
    GameMethods.pack(Capability.Unsupported),
    WorkspaceMethods.pack(Capability.Unsupported),
    ProjectMethods.pack(Capability.Unsupported),
    ConversationVoiceMethods.pack(Capability.Unsupported),
    ApprovalMethods.pack(Capability.Unsupported),
    HostContractMethods.pack(Capability.Unsupported),
)
