package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformOpenExternalLink(event: AppEvent.ExternalLink.OnOpenExternalLink) {
    val payload = mapOf(
        "url" to event.url,
        "target" to event.target
    )
    postNotification(name = "onOpenExternalLink", userInfo = payload)
}


