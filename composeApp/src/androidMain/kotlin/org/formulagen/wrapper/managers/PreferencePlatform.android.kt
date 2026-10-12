package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformOnSetPushNotificationState(event: AppEvent.Preference.OnSetPushNotificationStateEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Push state: ${event.isAllowed}")
}

actual fun platformOnSetOnboardingState(event: AppEvent.Preference.OnSetOnboardingStateEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Onboarding seen: ${event.hasEverSeenOnboardingScreen}")
}

actual fun platformOnSetPerformanceWarningState(event: AppEvent.Preference.OnSetPerformanceWarningStateEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Performance warning: ${event.enabled}")
}

actual fun platformOnSetDeviceID(event: AppEvent.Preference.OnSetDeviceIDEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "DeviceId set")
}

actual fun platformOnSetAuthToken(event: AppEvent.Preference.OnSetAuthTokenEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Auth token set")
}

actual fun platformOnSetUserId(event: AppEvent.Preference.OnSetUserIdEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "UserId set: ${event.userId}")
}

actual fun platformOnSetUsername(event: AppEvent.Preference.OnSetUsernameEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Username set: ${event.username}")
}

actual fun platformOnSetBrazeUserID(event: AppEvent.Preference.OnSetBrazeUserIDEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Braze user set: ${event.brazeUserId}")
}

actual fun platformOnSetAppEnv(event: AppEvent.Preference.OnSetAppEnvStateEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "App env: ${event.env}")
}

actual fun platformOnSetAttributionsState(event: AppEvent.Preference.OnSetAttributionStateEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Attributions set")
}

actual fun platformOnSetCustomResponsiveWebBaseURL(event: AppEvent.Preference.OnSetCustomResponsiveWebBaseURLEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Base URL set: ${event.customUrl}")
}

actual fun platformOnSetNetworkLatencyThreshold(event: AppEvent.Preference.OnSetNetworkLatencyThresholdEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Latency threshold: ${event.threshold}")
}

actual fun platformOnSetGamePerformanceThreshold(event: AppEvent.Preference.OnSetGamePerformanceFPSThresholdEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Game FPS threshold: ${event.threshold}")
}

actual fun platformOnAddGameFpsEntry(event: AppEvent.Preference.OnAddGameFpsEntryEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Add FPS entry: ${event.gameName} -> ${event.averageFPS}")
}

actual fun platformOnSetFpsTrackCount(event: AppEvent.Preference.OnSetGameTrackCountEvent) {
    log(LogLevel.Info, PreferenceManager.TAG, "Track count: ${event.trackCount}")
}

actual fun platformOnClearSession() {
    log(LogLevel.Info, PreferenceManager.TAG, "Clear session")
}


