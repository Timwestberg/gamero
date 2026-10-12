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

class BrazeManager {
    companion object {
        val TAG: String = "BrazeManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Braze>()
                .collect { evt ->
                    when (evt) {
                        is AppEvent.Braze.OnUserReadyEvent -> platformBrazeOnUserReady(evt)
                        is AppEvent.Braze.OnUserLogoutEvent -> platformBrazeOnUserLogout(evt)
                        is AppEvent.Braze.OnPushNotificationStateChangeEvent -> platformBrazeOnPushNotificationChange(evt)
                    }
                }
        }
    }
}

expect fun platformBrazeOnUserReady(event: AppEvent.Braze.OnUserReadyEvent)
expect fun platformBrazeOnUserLogout(event: AppEvent.Braze.OnUserLogoutEvent)
expect fun platformBrazeOnPushNotificationChange(event: AppEvent.Braze.OnPushNotificationStateChangeEvent)


