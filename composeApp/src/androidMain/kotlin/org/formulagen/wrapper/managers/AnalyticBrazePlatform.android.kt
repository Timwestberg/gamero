package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformBrazeOnUserReady(event: AppEvent.Braze.OnUserReadyEvent) {
    log(LogLevel.Info, BrazeManager.TAG, "Braze user ready: ${event.data.user.brazeExternalId}")
}

actual fun platformBrazeOnUserLogout(event: AppEvent.Braze.OnUserLogoutEvent) {
    log(LogLevel.Info, BrazeManager.TAG, "Braze user logout: ${event.username}")
}

actual fun platformBrazeOnPushNotificationChange(event: AppEvent.Braze.OnPushNotificationStateChangeEvent) {
    log(LogLevel.Info, BrazeManager.TAG, "Braze push state: ${event.isAllowed}")
}


