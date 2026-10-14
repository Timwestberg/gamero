package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformLogFunnelEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) {
    // TODO: Implement with your analytics SDK
}

actual fun platformLogAttributionEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) {
    // TODO: Implement with your analytics SDK
}



actual fun platformMonitorError(event: AppEvent.Analytic.OnMonitorError) {
    log(LogLevel.Error, AnalyticManager.TAG, "Monitor error: $event")
}
