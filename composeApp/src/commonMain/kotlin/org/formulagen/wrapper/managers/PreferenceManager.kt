package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus
import org.formulagen.wrapper.preferences.PreferenceStore
import org.formulagen.wrapper.preferences.SettingsPreferenceStore

class PreferenceManager(
    private val store: PreferenceStore = SettingsPreferenceStore()
) {
    companion object {
        val TAG: String = "PreferenceManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Preference>()
                .collect { evt ->
                    when (evt) {
                        is AppEvent.Preference.OnSetPushNotificationStateEvent -> store.setPushNotificationAllowed(evt.isAllowed)
                        is AppEvent.Preference.OnSetOnboardingStateEvent -> store.setHasSeenOnboarding(evt.hasEverSeenOnboardingScreen)
                        is AppEvent.Preference.OnSetPerformanceWarningStateEvent -> store.setPerformanceWarningEnabled(evt.enabled)
                        is AppEvent.Preference.OnSetDeviceIDEvent -> store.setDeviceId(evt.deviceId)
                        is AppEvent.Preference.OnSetAuthTokenEvent -> store.setAuthToken(evt.loginToken)
                        is AppEvent.Preference.OnSetUserIdEvent -> store.setUserId(evt.userId)
                        is AppEvent.Preference.OnSetUsernameEvent -> store.setUsername(evt.username)
                        is AppEvent.Preference.OnSetBrazeUserIDEvent -> store.setBrazeUserId(evt.brazeUserId)
                        is AppEvent.Preference.OnSetAppEnvStateEvent -> store.setAppEnv(evt.env)
                        is AppEvent.Preference.OnSetAttributionStateEvent -> store.setAttributionsAFJson(evt.attributionsAFJson)
                        is AppEvent.Preference.OnSetCustomResponsiveWebBaseURLEvent -> store.setCustomResponsiveBaseUrl(evt.customUrl)
                        is AppEvent.Preference.OnSetNetworkLatencyThresholdEvent -> store.setNetworkLatencyThreshold(evt.threshold)
                        is AppEvent.Preference.OnSetGamePerformanceFPSThresholdEvent -> store.setGamePerformanceFpsThreshold(evt.threshold)
                        is AppEvent.Preference.OnAddGameFpsEntryEvent -> store.addGameFpsEntry(evt.gameName, evt.averageFPS)
                        is AppEvent.Preference.OnSetGameTrackCountEvent -> store.setFpsTrackCount(evt.trackCount)
                        AppEvent.Preference.OnClearSessionEvent -> store.clear()
                    }
                }
        }
    }
}



