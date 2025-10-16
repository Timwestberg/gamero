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

class SessionManager {
    companion object{
        val TAG: String = "SessionManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Session>()
                .collect { evt ->
                    when(evt) {
                        AppEvent.Session.OnClearSession -> platformClearSession()
                        is AppEvent.Session.OnManagerStart -> log(LogLevel.Info, TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                        is AppEvent.Session.OnSessionReady -> platformStoreSession(evt)
                        is AppEvent.Session.OnSetSessionInfo -> platformSetSessionInfo(evt)
                        is AppEvent.Session.OnSetSessionToken -> platformSetSessionToken(evt)
                        AppEvent.Session.OnUserLogOut -> TODO()
                    }
                }
        }
    }
}

expect fun platformClearSession()
expect fun platformStoreSession(event: AppEvent.Session.OnSessionReady)
expect fun platformSetSessionInfo(event: AppEvent.Session.OnSetSessionInfo)
expect fun platformSetSessionToken(event: AppEvent.Session.OnSetSessionToken)
