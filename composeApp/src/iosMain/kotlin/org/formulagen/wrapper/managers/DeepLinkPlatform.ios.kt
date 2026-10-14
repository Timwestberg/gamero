package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformOnNewIntent(event: AppEvent.DeepLink.OnNewIntentEvent) {
    postNotification(name = "onNewIntent", userInfo = mapOf("url" to event.url, "source" to event.source))
}
