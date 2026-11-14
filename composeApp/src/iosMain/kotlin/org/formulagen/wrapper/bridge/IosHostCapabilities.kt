package org.formulagen.wrapper.bridge

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification
import platform.UIKit.UIImpactFeedbackGenerator
import platform.UIKit.UIImpactFeedbackStyle
import platform.UIKit.UINotificationFeedbackGenerator
import platform.UIKit.UINotificationFeedbackType
import platform.UIKit.UISelectionFeedbackGenerator
import kotlin.coroutines.resume

/** The iOS column of `HOST_BRIDGE_PARITY` for the platform set. Product capabilities come from the product. */
internal fun iosHostCapabilities(appVersion: String): HostCapabilities = HostCapabilities(
    platform = HostPlatform.IOS,
    appVersion = appVersion,
    links = Capability.Supported(LinkOpener(::openOutsideApp)),
    haptics = Capability.Supported(Haptics(::playHaptic)),
    lifecycle = Capability.Supported(iosLifecycle),
)

/**
 * Hands [url] to iOS, which opens Safari, or the site's own app when the link is one of its universal links.
 * The caller has checked the URL ([isSafeHttpsUrl]). Throws when iOS has nothing that opens it.
 */
internal suspend fun openOutsideApp(url: String) = withContext(Dispatchers.Main) {
    val target = checkNotNull(NSURL.URLWithString(url)) { "iOS cannot read the link" }
    val opened = suspendCancellableCoroutine { continuation ->
        UIApplication.sharedApplication.openURL(target, options = emptyMap<Any?, Any>()) { opened ->
            if (continuation.isActive) continuation.resume(opened)
        }
    }
    check(opened) { "iOS could not open the link" }
}

/** Each named effect on the Taptic Engine. A device without one, and the simulator, do nothing. */
internal suspend fun playHaptic(effect: HapticEffect) = withContext(Dispatchers.Main) {
    when (effect) {
        HapticEffect.LIGHT -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleLight).impactOccurred()
        HapticEffect.MEDIUM -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleMedium).impactOccurred()
        HapticEffect.HEAVY -> UIImpactFeedbackGenerator(UIImpactFeedbackStyle.UIImpactFeedbackStyleHeavy).impactOccurred()
        HapticEffect.SELECTION -> UISelectionFeedbackGenerator().selectionChanged()
        HapticEffect.SUCCESS -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeSuccess)
        HapticEffect.WARNING -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeWarning)
        HapticEffect.ERROR -> UINotificationFeedbackGenerator().notificationOccurred(UINotificationFeedbackType.UINotificationFeedbackTypeError)
    }
}

/**
 * `lifecycle.pause` when the app goes to the background and `lifecycle.resume` when it comes back. A system
 * prompt, the app switcher or Control Centre over the app is not a pause: the page is still on screen.
 */
internal val iosLifecycle = Lifecycle { onPause, onResume ->
    val center = NSNotificationCenter.defaultCenter
    val observers = listOf(
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { onPause() },
        center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { onResume() },
    )
    AutoCloseable { observers.forEach(center::removeObserver) }
}

/**
 * Hovel's capability packs on iOS. iOS has none of the desktop product capabilities, so their methods answer
 * `unsupported`. Phone approvals are iOS's own: [approvals] shows an approval natively and [devices] registers
 * this phone as an approval device (both `PhoneApprovals` in the app). [notifications] schedules list reminders
 * and Focus phase ends on the phone; the app passes a [lists] store that clears them when the account locks.
 */
internal fun iosHovelBridgePacks(
    approvals: ApprovalCapability,
    devices: ApprovalDeviceCapability,
    lists: ListsLocalCapability,
    voice: VoiceInputCapability? = null,
    playback: VoicePlaybackCapability? = null,
    notifications: NotificationsCapability? = null,
): List<CapabilityPack> = listOf(
    VoicePlaybackMethods.pack(supportedIfPresent(playback)),
    VoiceInputMethods.pack(supportedIfPresent(voice)),
    NotificationMethods.pack(supportedIfPresent(notifications)),
    ListsLocalMethods.pack(Capability.Supported(lists)),
    CompanionMethods.pack(Capability.Unsupported),
    ProviderMethods.pack(Capability.Unsupported),
    PetMethods.pack(Capability.Unsupported),
    GameMethods.pack(Capability.Unsupported),
    ProjectMethods.pack(Capability.Unsupported),
    ProjectWorkspaceMethods.pack(Capability.Unsupported),
    ConversationVoiceMethods.pack(Capability.Unsupported),
    ApprovalMethods.pack(Capability.Supported(approvals)),
    ApprovalDeviceMethods.pack(Capability.Supported(devices)),
    // Only a Mac confirms a phone.
    PhonePairingMethods.pack(Capability.Unsupported),
    HostContractMethods.pack(Capability.Unsupported),
)

private fun <T : Any> supportedIfPresent(impl: T?): Capability<T> = impl?.let { Capability.Supported(it) } ?: Capability.Unsupported
