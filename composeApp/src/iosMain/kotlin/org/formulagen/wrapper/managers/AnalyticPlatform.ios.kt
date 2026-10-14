package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformLogFunnelEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) {
    val payload = mapOf(
        "eventName" to event.eventName,
        "eventData" to (event.eventData?.toString()),
        "type" to event.type.name
    )
    postNotification(name = "onLogFunnelEvent", userInfo = payload)
}

actual fun platformLogAttributionEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) {
    val payload = mapOf(
        "eventName" to event.eventName,
        "eventData" to (event.eventData?.toString()),
        "type" to event.type.name
    )
    postNotification(name = "onLogAttributionEvent", userInfo = payload)
}


actual fun platformMonitorError(event: AppEvent.Analytic.OnMonitorError) {
    val payload = mapOf(
        "tag" to event.tag,
        "message" to event.message,
        "level" to event.level,
        "error" to event.error,
        "stackTrace" to event.stackTrace,
        "version" to event.version
    )
    postNotification(name = "onMonitorError", userInfo = payload)
}
