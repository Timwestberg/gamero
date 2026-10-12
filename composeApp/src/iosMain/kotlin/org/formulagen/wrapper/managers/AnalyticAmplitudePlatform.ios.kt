package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformAmplitudeOnResponsiveWebReady(event: AppEvent.Amplitude.OnResponsiveWebReadyEvent) {
    postNotification(name = "onAmplitudeResponsiveWebReady")
}

actual fun platformAmplitudeOnUserReady(event: AppEvent.Amplitude.OnUserReadyEvent) {
    val user = event.data.user
    val payload = mapOf(
        "userId" to user.userId,
        "userName" to user.userName,
        "legacyUserId" to (user.legacyUserId ?: 0),
        "brazeExternalId" to user.brazeExternalId,
    )
    postNotification(name = "onAmplitudeUserReady", userInfo = payload)
}

actual fun platformAmplitudeOnUserLogout(event: AppEvent.Amplitude.OnUserLogoutEvent) {
    val payload = mapOf("username" to event.username)
    postNotification(name = "onAmplitudeUserLogout", userInfo = payload)
}

actual fun platformAmplitudeOnUpdateCatalogContent(event: AppEvent.Amplitude.OnUpdateCatalogContentEvent) {
    val payload = mapOf(
        "catalogVersionId" to event.catalogVersionId,
        "catalogVersion" to event.catalogVersion,
    )
    postNotification(name = "onAmplitudeUpdateCatalogContent", userInfo = payload)
}

actual fun platformAmplitudeOnStartGameEvent(event: AppEvent.Amplitude.OnStartGameEvent) {
    val payload = mapOf(
        "o" to event.o,
        "tournamentInstanceEntryId" to event.tournamentInstanceEntryId,
    )
    postNotification(name = "onAmplitudeStartGame", userInfo = payload)
}

actual fun platformAmplitudeOnGameEndedEvent(event: AppEvent.Amplitude.OnGameEndedEvent) {
    val payload = mapOf("tournamentId" to event.tournamentId)
    postNotification(name = "onAmplitudeGameEnded", userInfo = payload)
}

actual fun platformAmplitudeOnSilentErrorEvent(event: AppEvent.Amplitude.OnSilentErrorEvent) {
    val payload = mapOf(
        "tag" to event.tag,
        "sentryLevel" to event.sentryLevel,
        "errorLevel" to event.errorLevel,
        "message" to event.message,
        "error" to event.error,
        "errorCode" to event.errorCode,
        "stackTrace" to event.stackTrace,
    )
    postNotification(name = "onAmplitudeSilentError", userInfo = payload)
}

actual fun platformAmplitudeOnAnalyticsQueueEvent(event: AppEvent.Amplitude.OnAnalyticsQueueEvent) {
    val payload = mapOf(
        "eventName" to event.eventName,
        "eventObject" to event.eventObject,
    )
    postNotification(name = "onAmplitudeAnalyticsQueue", userInfo = payload)
}

actual fun platformAmplitudeOnLogAmplitudeEvent(event: AppEvent.Amplitude.OnLogAmplitudeEvent) {
    val payload = mapOf(
        "eventName" to event.eventName,
        "eventData" to (event.eventData?.toString()),
    )
    postNotification(name = "onAmplitudeLogEvent", userInfo = payload)
}


