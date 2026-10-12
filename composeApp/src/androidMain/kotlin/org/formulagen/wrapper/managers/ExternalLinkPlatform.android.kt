package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformOpenExternalLink(event: AppEvent.ExternalLink.OnOpenExternalLink) {
    log(LogLevel.Info, ExternalLinkManager.TAG, "Open external link: ${event.url} (Android stub)")
}


