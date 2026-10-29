package org.formulagen.wrapper.bridge

/**
 * The iOS column of `HOST_BRIDGE_PARITY` for the platform set. Haptics, links
 * and lifecycle arrive in phase C of the host bridge plan; until then they
 * answer `unsupported`. Product capabilities come from the product.
 */
internal fun iosHostCapabilities(appVersion: String): HostCapabilities = HostCapabilities(
    platform = HostPlatform.IOS,
    appVersion = appVersion,
    links = Capability.Unsupported,
    haptics = Capability.Unsupported,
    lifecycle = Capability.Unsupported,
)

/**
 * Hovel's capability packs on iOS. iOS has none of the desktop product capabilities, so their methods answer
 * `unsupported`. Phone approvals are iOS's own: [approvals] shows an approval natively and [devices] registers
 * this phone as an approval device (both `PhoneApprovals` in the app).
 */
internal fun iosHovelBridgePacks(approvals: ApprovalCapability, devices: ApprovalDeviceCapability): List<CapabilityPack> = listOf(
    CompanionMethods.pack(Capability.Unsupported),
    org.formulagen.wrapper.bridge.BrainMethods.pack(Capability.Unsupported),
    ChatMethods.pack(Capability.Unsupported),
    ProviderMethods.pack(Capability.Unsupported),
    PetMethods.pack(Capability.Unsupported),
    GameMethods.pack(Capability.Unsupported),
    WorkspaceMethods.pack(Capability.Unsupported),
    ProjectMethods.pack(Capability.Unsupported),
    ConversationVoiceMethods.pack(Capability.Unsupported),
    ApprovalMethods.pack(Capability.Supported(approvals)),
    ApprovalDeviceMethods.pack(Capability.Supported(devices)),
    HostContractMethods.pack(Capability.Unsupported),
)
