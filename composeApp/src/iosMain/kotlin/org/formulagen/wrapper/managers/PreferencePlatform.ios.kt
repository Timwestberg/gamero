package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformOnSetPushNotificationState(event: AppEvent.Preference.OnSetPushNotificationStateEvent) {
    postNotification("onSetPushNotificationState", mapOf("isAllowed" to event.isAllowed))
}

actual fun platformOnSetOnboardingState(event: AppEvent.Preference.OnSetOnboardingStateEvent) {
    postNotification("onSetOnboardingState", mapOf("hasEverSeenOnboardingScreen" to event.hasEverSeenOnboardingScreen))
}

actual fun platformOnSetPerformanceWarningState(event: AppEvent.Preference.OnSetPerformanceWarningStateEvent) {
    postNotification("onSetPerformanceWarningState", mapOf("enabled" to event.enabled))
}

actual fun platformOnSetDeviceID(event: AppEvent.Preference.OnSetDeviceIDEvent) {
    postNotification("onSetDeviceID", mapOf("deviceId" to event.deviceId))
}

actual fun platformOnSetAuthToken(event: AppEvent.Preference.OnSetAuthTokenEvent) {
    postNotification("onSetAuthToken", mapOf("loginToken" to event.loginToken))
}

actual fun platformOnSetUserId(event: AppEvent.Preference.OnSetUserIdEvent) {
    postNotification("onSetUserId", mapOf("userId" to event.userId))
}

actual fun platformOnSetUsername(event: AppEvent.Preference.OnSetUsernameEvent) {
    postNotification("onSetUsername", mapOf("username" to event.username))
}

actual fun platformOnSetBrazeUserID(event: AppEvent.Preference.OnSetBrazeUserIDEvent) {
    postNotification("onSetBrazeUserID", mapOf("brazeUserId" to event.brazeUserId))
}

actual fun platformOnSetAppEnv(event: AppEvent.Preference.OnSetAppEnvStateEvent) {
    postNotification("onSetAppEnv", mapOf("env" to event.env))
}

actual fun platformOnSetAttributionsState(event: AppEvent.Preference.OnSetAttributionStateEvent) {
    postNotification("onSetAttributionsState", mapOf("attributionsAFJson" to event.attributionsAFJson))
}

actual fun platformOnSetCustomResponsiveWebBaseURL(event: AppEvent.Preference.OnSetCustomResponsiveWebBaseURLEvent) {
    postNotification("onSetCustomResponsiveWebBaseURL", mapOf("customUrl" to event.customUrl))
}

actual fun platformOnSetNetworkLatencyThreshold(event: AppEvent.Preference.OnSetNetworkLatencyThresholdEvent) {
    postNotification("onSetNetworkLatencyThreshold", mapOf("threshold" to event.threshold))
}

actual fun platformOnSetGamePerformanceThreshold(event: AppEvent.Preference.OnSetGamePerformanceFPSThresholdEvent) {
    postNotification("onSetGamePerformanceFPSThreshold", mapOf("threshold" to event.threshold))
}

actual fun platformOnAddGameFpsEntry(event: AppEvent.Preference.OnAddGameFpsEntryEvent) {
    postNotification("onAddGameFpsEntry", mapOf("gameName" to event.gameName, "averageFPS" to event.averageFPS))
}

actual fun platformOnSetFpsTrackCount(event: AppEvent.Preference.OnSetGameTrackCountEvent) {
    postNotification("onSetFpsTrackCount", mapOf("trackCount" to event.trackCount))
}

actual fun platformOnClearSession() {
    postNotification("onClearSession")
}


