package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformAmplitudeOnResponsiveWebReady(event: AppEvent.Amplitude.OnResponsiveWebReadyEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude responsive ready")
}

actual fun platformAmplitudeOnUserReady(event: AppEvent.Amplitude.OnUserReadyEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude user ready: ${event.data.user.userId}")
}

actual fun platformAmplitudeOnUserLogout(event: AppEvent.Amplitude.OnUserLogoutEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude user logout: ${event.username}")
}

actual fun platformAmplitudeOnUpdateCatalogContent(event: AppEvent.Amplitude.OnUpdateCatalogContentEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude update catalog: ${event.catalogVersionId}:${event.catalogVersion}")
}

actual fun platformAmplitudeOnStartGameEvent(event: AppEvent.Amplitude.OnStartGameEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude start game: ${event.tournamentInstanceEntryId}")
}

actual fun platformAmplitudeOnGameEndedEvent(event: AppEvent.Amplitude.OnGameEndedEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude game ended: ${event.tournamentId}")
}

actual fun platformAmplitudeOnSilentErrorEvent(event: AppEvent.Amplitude.OnSilentErrorEvent) {
    log(LogLevel.Warn, AnalyticManager.TAG, "Amplitude silent error: ${event.tag} ${event.message}")
}

actual fun platformAmplitudeOnAnalyticsQueueEvent(event: AppEvent.Amplitude.OnAnalyticsQueueEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude queue: ${event.eventName}")
}

actual fun platformAmplitudeOnLogAmplitudeEvent(event: AppEvent.Amplitude.OnLogAmplitudeEvent) {
    log(LogLevel.Info, AnalyticManager.TAG, "Amplitude log: ${event.eventName}")
}


