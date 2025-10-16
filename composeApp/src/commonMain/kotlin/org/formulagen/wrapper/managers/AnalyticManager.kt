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
                        is AppEvent.Analytic.OnManagerStart -> log(LogLevel.Info, AppManager.TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
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