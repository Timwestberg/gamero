package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformBrazeOnUserReady(event: AppEvent.Braze.OnUserReadyEvent) {
    val user = event.data.user
    val payload = mapOf(
        "brazeExternalId" to user.brazeExternalId,
        "userId" to user.userId,
        "userName" to user.userName,
    )
    postNotification(name = "onBrazeUserReady", userInfo = payload)
}

actual fun platformBrazeOnUserLogout(event: AppEvent.Braze.OnUserLogoutEvent) {
    val payload = mapOf("username" to event.username)
    postNotification(name = "onBrazeUserLogout", userInfo = payload)
}

actual fun platformBrazeOnPushNotificationChange(event: AppEvent.Braze.OnPushNotificationStateChangeEvent) {
    val payload = mapOf("isAllowed" to event.isAllowed)
    postNotification(name = "onBrazePushNotificationStateChange", userInfo = payload)
}


