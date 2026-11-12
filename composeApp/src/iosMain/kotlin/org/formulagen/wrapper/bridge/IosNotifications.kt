@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
package org.formulagen.wrapper.bridge

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNAuthorizationStatus
import platform.UserNotifications.UNAuthorizationStatusAuthorized
import platform.UserNotifications.UNAuthorizationStatusDenied
import platform.UserNotifications.UNAuthorizationStatusEphemeral
import platform.UserNotifications.UNAuthorizationStatusNotDetermined
import platform.UserNotifications.UNAuthorizationStatusProvisional
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptionNone
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import kotlin.coroutines.resume

/**
 * Local notifications on iPhone: list reminders and Focus phase ends, scheduled on the phone with no server and no
 * push, so they need no entitlement. [LocalNotifications] holds the rules; this file only talks to iOS.
 */
internal fun iosNotifications(): LocalNotifications = LocalNotifications(IosNotificationCenter(), ::epochMs)

private fun epochMs(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

/** What Hovel may do with the person's answer. A provisional or ephemeral grant delivers, so it counts as granted. */
internal fun notificationPermissionOf(status: UNAuthorizationStatus?): NotificationPermission = when (status) {
    UNAuthorizationStatusAuthorized, UNAuthorizationStatusProvisional, UNAuthorizationStatusEphemeral -> NotificationPermission.GRANTED
    UNAuthorizationStatusNotDetermined -> NotificationPermission.UNDETERMINED
    UNAuthorizationStatusDenied -> NotificationPermission.DENIED
    // A status this build does not know cannot be relied on to deliver.
    else -> NotificationPermission.DENIED
}

/**
 * While Hovel is on screen iOS shows no banner, plays no sound and lists nothing: the page shows its own notice for
 * a reminder that comes due. That is also what iOS does for an app with no delegate; this delegate says it outright,
 * so adding another delegate method later cannot change it by accident. A tap on a notification opens the app,
 * which is the default and needs no code here.
 */
private class QuietInForeground : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) = withCompletionHandler(UNNotificationPresentationOptionNone)
}

/** `UNUserNotificationCenter` as [LocalNotifications] sees it. Its completion handlers run on a system queue. */
internal class IosNotificationCenter(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
    private val nowMs: () -> Long = ::epochMs,
) : NotificationCenterPort {
    // The centre keeps its delegate weakly, so it lives here.
    private val foreground = QuietInForeground()

    init {
        center.delegate = foreground
    }

    override suspend fun permission(): NotificationPermission = suspendCancellableCoroutine { continuation ->
        center.getNotificationSettingsWithCompletionHandler { settings ->
            if (continuation.isActive) continuation.resume(notificationPermissionOf(settings?.authorizationStatus))
        }
    }

    /** Banners and the default sound. No badge: nothing in Hovel clears one. */
    override suspend fun prompt(): Unit = suspendCancellableCoroutine { continuation ->
        center.requestAuthorizationWithOptions(UNAuthorizationOptionAlert or UNAuthorizationOptionSound) { _, _ ->
            if (continuation.isActive) continuation.resume(Unit)
        }
    }

    override suspend fun pending(): List<String> = suspendCancellableCoroutine { continuation ->
        center.getPendingNotificationRequestsWithCompletionHandler { requests ->
            if (continuation.isActive) continuation.resume(requests.orEmpty().filterIsInstance<UNNotificationRequest>().map { it.identifier })
        }
    }

    override suspend fun delivered(): List<String> = suspendCancellableCoroutine { continuation ->
        center.getDeliveredNotificationsWithCompletionHandler { notifications ->
            if (continuation.isActive) continuation.resume(notifications.orEmpty().filterIsInstance<UNNotification>().map { it.request.identifier })
        }
    }

    // iOS applies a removal on its own queue, in order with the calls that follow it.
    override suspend fun removePending(identifiers: List<String>) {
        if (identifiers.isNotEmpty()) center.removePendingNotificationRequestsWithIdentifiers(identifiers)
    }

    override suspend fun removeDelivered(identifiers: List<String>) {
        if (identifiers.isNotEmpty()) center.removeDeliveredNotificationsWithIdentifiers(identifiers)
    }

    override suspend fun schedule(request: ScheduledNotification): Boolean {
        val content = UNMutableNotificationContent()
        content.setTitle(request.title)
        if (request.body.isNotEmpty()) content.setBody(request.body)
        content.setSound(UNNotificationSound.defaultSound)
        // Groups a scope's notifications together in Notification Centre.
        content.setThreadIdentifier(request.thread)
        val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(NotificationPlan.delaySeconds(request.fireAtMs, nowMs()), repeats = false)
        val added = UNNotificationRequest.requestWithIdentifier(request.identifier, content, trigger)
        return suspendCancellableCoroutine { continuation ->
            center.addNotificationRequest(added) { error -> if (continuation.isActive) continuation.resume(error == null) }
        }
    }
}
