package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

class AnalyticManager {
    companion object{
        val TAG: String = "AnalyticManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Analytic>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.Analytic.OnLogAnalyticEvent -> logAnalyticEvent(evt)
                        is AppEvent.Analytic.OnMonitorError -> platformMonitorError(evt)
                        is AppEvent.Analytic.OnManagerStart -> log(LogLevel.Info, AppManager.TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                    }
                }
        }

        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Amplitude>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.Amplitude.OnAnalyticsQueueEvent -> platformAmplitudeOnAnalyticsQueueEvent(evt)
                        is AppEvent.Amplitude.OnGameEndedEvent -> platformAmplitudeOnGameEndedEvent(evt)
                        is AppEvent.Amplitude.OnLogAmplitudeEvent -> platformAmplitudeOnLogAmplitudeEvent(evt)
                        is AppEvent.Amplitude.OnResponsiveWebReadyEvent -> platformAmplitudeOnResponsiveWebReady(evt)
                        is AppEvent.Amplitude.OnSilentErrorEvent -> platformAmplitudeOnSilentErrorEvent(evt)
                        is AppEvent.Amplitude.OnStartGameEvent -> platformAmplitudeOnStartGameEvent(evt)
                        is AppEvent.Amplitude.OnUpdateCatalogContentEvent -> platformAmplitudeOnUpdateCatalogContent(evt)
                        is AppEvent.Amplitude.OnUserLogoutEvent -> platformAmplitudeOnUserLogout(evt)
                        is AppEvent.Amplitude.OnUserReadyEvent -> platformAmplitudeOnUserReady(evt)
                    }
                }
        }
    }

    private fun logAnalyticEvent(event: AppEvent.Analytic.OnLogAnalyticEvent){
        when(event.type) {
            AppEvent.AnalyticEventType.GENERAL -> {
                platformLogFunnelEvent(event)
                platformLogAttributionEvent(event)
            }
            AppEvent.AnalyticEventType.ATTRIBUTION -> platformLogAttributionEvent(event)
            AppEvent.AnalyticEventType.FUNNEL -> platformLogFunnelEvent(event)
        }
    }
}

expect fun platformLogFunnelEvent(event: AppEvent.Analytic.OnLogAnalyticEvent)
expect fun platformLogAttributionEvent(event: AppEvent.Analytic.OnLogAnalyticEvent)
expect fun platformMonitorError(event: AppEvent.Analytic.OnMonitorError)

// Amplitude expect hooks
expect fun platformAmplitudeOnResponsiveWebReady(event: AppEvent.Amplitude.OnResponsiveWebReadyEvent)
expect fun platformAmplitudeOnUserReady(event: AppEvent.Amplitude.OnUserReadyEvent)
expect fun platformAmplitudeOnUserLogout(event: AppEvent.Amplitude.OnUserLogoutEvent)
expect fun platformAmplitudeOnUpdateCatalogContent(event: AppEvent.Amplitude.OnUpdateCatalogContentEvent)
expect fun platformAmplitudeOnStartGameEvent(event: AppEvent.Amplitude.OnStartGameEvent)
expect fun platformAmplitudeOnGameEndedEvent(event: AppEvent.Amplitude.OnGameEndedEvent)
expect fun platformAmplitudeOnSilentErrorEvent(event: AppEvent.Amplitude.OnSilentErrorEvent)
expect fun platformAmplitudeOnAnalyticsQueueEvent(event: AppEvent.Amplitude.OnAnalyticsQueueEvent)
expect fun platformAmplitudeOnLogAmplitudeEvent(event: AppEvent.Amplitude.OnLogAmplitudeEvent)