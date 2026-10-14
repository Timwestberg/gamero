package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformOnNewIntent(event: AppEvent.DeepLink.OnNewIntentEvent) {
    log(LogLevel.Info, DeepLinkManager.TAG, "New intent: $event")
}
