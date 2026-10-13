package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent

/** Legacy mobile integrations are not supported or advertised by the desktop host. */
actual fun platformLogFunnelEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) { unsupportedDesktop("platformLogFunnelEvent") }
actual fun platformLogAttributionEvent(event: AppEvent.Analytic.OnLogAnalyticEvent) { unsupportedDesktop("platformLogAttributionEvent") }
actual fun platformMonitorError(event: AppEvent.Analytic.OnMonitorError) { unsupportedDesktop("platformMonitorError") }
actual fun platformAmplitudeOnResponsiveWebReady(event: AppEvent.Amplitude.OnResponsiveWebReadyEvent) { unsupportedDesktop("platformAmplitudeOnResponsiveWebReady") }
actual fun platformAmplitudeOnUserReady(event: AppEvent.Amplitude.OnUserReadyEvent) { unsupportedDesktop("platformAmplitudeOnUserReady") }
actual fun platformAmplitudeOnUserLogout(event: AppEvent.Amplitude.OnUserLogoutEvent) { unsupportedDesktop("platformAmplitudeOnUserLogout") }
actual fun platformAmplitudeOnUpdateCatalogContent(event: AppEvent.Amplitude.OnUpdateCatalogContentEvent) { unsupportedDesktop("platformAmplitudeOnUpdateCatalogContent") }
actual fun platformAmplitudeOnStartGameEvent(event: AppEvent.Amplitude.OnStartGameEvent) { unsupportedDesktop("platformAmplitudeOnStartGameEvent") }
actual fun platformAmplitudeOnGameEndedEvent(event: AppEvent.Amplitude.OnGameEndedEvent) { unsupportedDesktop("platformAmplitudeOnGameEndedEvent") }
actual fun platformAmplitudeOnSilentErrorEvent(event: AppEvent.Amplitude.OnSilentErrorEvent) { unsupportedDesktop("platformAmplitudeOnSilentErrorEvent") }
actual fun platformAmplitudeOnAnalyticsQueueEvent(event: AppEvent.Amplitude.OnAnalyticsQueueEvent) { unsupportedDesktop("platformAmplitudeOnAnalyticsQueueEvent") }
actual fun platformAmplitudeOnLogAmplitudeEvent(event: AppEvent.Amplitude.OnLogAmplitudeEvent) { unsupportedDesktop("platformAmplitudeOnLogAmplitudeEvent") }
actual fun platformRemoveAppSplashScreen() { unsupportedDesktop("platformRemoveAppSplashScreen") }
actual fun platformVerifyBridges() { unsupportedDesktop("platformVerifyBridges") }
actual fun platformAFOnInstall(event: AppEvent.AppsFlyer.OnInstallEvent) { unsupportedDesktop("platformAFOnInstall") }
actual fun platformAFOnUserReady(event: AppEvent.AppsFlyer.OnUserReadyEvent) { unsupportedDesktop("platformAFOnUserReady") }
actual fun platformAFOnUserLogout(event: AppEvent.AppsFlyer.OnUserLogoutEvent) { unsupportedDesktop("platformAFOnUserLogout") }
actual fun platformAFOnLogEvent(event: AppEvent.AppsFlyer.OnLogAFEvent) { unsupportedDesktop("platformAFOnLogEvent") }
actual fun platformAFOnGameStarted(event: AppEvent.AppsFlyer.OnGameStartedEvent) { unsupportedDesktop("platformAFOnGameStarted") }
actual fun platformOnUserReady(event: AudioEvent.OnUserReadyEvent) { unsupportedDesktop("platformOnUserReady") }
actual fun platformOnUserLogout(event: AudioEvent.OnUserLogoutEvent) { unsupportedDesktop("platformOnUserLogout") }
actual fun platformOnPlayingResponsiveWebGame(event: AudioEvent.OnPlayingResponsiveWebGameEvent) { unsupportedDesktop("platformOnPlayingResponsiveWebGame") }
actual fun platformOnSetAmbientMusic(event: AudioEvent.OnSetAmbientMusicStateEvent) { unsupportedDesktop("platformOnSetAmbientMusic") }
actual fun platformOnSetAmbientMusicSetting(event: AudioEvent.OnSetAmbientMusicSettingEvent) { unsupportedDesktop("platformOnSetAmbientMusicSetting") }
actual fun platformOnSetAmbientMusicSelection(event: AudioEvent.OnSetAmbientMusicSelectionEvent) { unsupportedDesktop("platformOnSetAmbientMusicSelection") }
actual fun platformOnSetLoadingState(event: AudioEvent.OnSetLoadingStateEvent) { unsupportedDesktop("platformOnSetLoadingState") }
actual fun platformOnReturnFromGame(event: AudioEvent.OnReturnFromGameEvent) { unsupportedDesktop("platformOnReturnFromGame") }
actual fun platformOnActivityLifeCycle(event: AudioEvent.OnActivityLifeCycleEvent) { unsupportedDesktop("platformOnActivityLifeCycle") }
actual fun platformBrazeOnUserReady(event: AppEvent.Braze.OnUserReadyEvent) { unsupportedDesktop("platformBrazeOnUserReady") }
actual fun platformBrazeOnUserLogout(event: AppEvent.Braze.OnUserLogoutEvent) { unsupportedDesktop("platformBrazeOnUserLogout") }
actual fun platformBrazeOnPushNotificationChange(event: AppEvent.Braze.OnPushNotificationStateChangeEvent) { unsupportedDesktop("platformBrazeOnPushNotificationChange") }
actual fun platformOnNewIntent(event: AppEvent.DeepLink.OnNewIntentEvent) { unsupportedDesktop("platformOnNewIntent") }
actual fun platformFetchDeviceInfo() { unsupportedDesktop("platformFetchDeviceInfo") }
actual fun platformStoreDeviceInfo(info: AppEvent.DeviceInfo) { unsupportedDesktop("platformStoreDeviceInfo") }
actual fun platformOpenExternalLink(event: AppEvent.ExternalLink.OnOpenExternalLink) { unsupportedDesktop("platformOpenExternalLink") }
actual fun platformLoadGame() { unsupportedDesktop("platformLoadGame") }
actual fun platformStartGame() { unsupportedDesktop("platformStartGame") }
actual fun platformCompleteGame() { unsupportedDesktop("platformCompleteGame") }
actual fun platformPauseGame() { unsupportedDesktop("platformPauseGame") }
actual fun platformResumeGame() { unsupportedDesktop("platformResumeGame") }
actual fun platformQuitGame() { unsupportedDesktop("platformQuitGame") }
actual fun platformPlayHapticFeedbackEffect(effect: AppEvent.HapticFeedbackEffect) { unsupportedDesktop("platformPlayHapticFeedbackEffect") }
actual fun platformOnUserReady(event: AppEvent.Haptic.OnUserReadyEvent) { unsupportedDesktop("platformOnUserReady") }
actual fun platformOnSetHapticFeedbackSetting(event: AppEvent.Haptic.OnSetHapticFeedbackSettingEvent) { unsupportedDesktop("platformOnSetHapticFeedbackSetting") }
actual fun platformOnPlayHapticEffect(event: AppEvent.Haptic.OnPlayHapticEffectEvent) { unsupportedDesktop("platformOnPlayHapticEffect") }
actual fun platformRequestPermissions() { unsupportedDesktop("platformRequestPermissions") }
actual fun platformVerifyPermissions() { unsupportedDesktop("platformVerifyPermissions") }
actual fun platformClearSession() { unsupportedDesktop("platformClearSession") }
actual fun platformStoreSession(event: AppEvent.Session.OnSessionReady) { unsupportedDesktop("platformStoreSession") }
actual fun platformSetSessionInfo(event: AppEvent.Session.OnSetSessionInfo) { unsupportedDesktop("platformSetSessionInfo") }
actual fun platformSetSessionToken(event: AppEvent.Session.OnSetSessionToken) { unsupportedDesktop("platformSetSessionToken") }

private fun unsupportedDesktop(capability: String): Nothing =
    throw UnsupportedOperationException("Desktop capability is unavailable: $capability")
