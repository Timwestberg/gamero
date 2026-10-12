package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus

class AppsFlyerManager {
    companion object {
        val TAG: String = "AppsFlyerManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.AppsFlyer>()
                .collect { evt ->
                    when (evt) {
                        is AppEvent.AppsFlyer.OnInstallEvent -> platformAFOnInstall(evt)
                        is AppEvent.AppsFlyer.OnUserReadyEvent -> platformAFOnUserReady(evt)
                        is AppEvent.AppsFlyer.OnUserLogoutEvent -> platformAFOnUserLogout(evt)
                        is AppEvent.AppsFlyer.OnLogAFEvent -> platformAFOnLogEvent(evt)
                        is AppEvent.AppsFlyer.OnGameStartedEvent -> platformAFOnGameStarted(evt)
                    }
                }
        }
    }
}

expect fun platformAFOnInstall(event: AppEvent.AppsFlyer.OnInstallEvent)
expect fun platformAFOnUserReady(event: AppEvent.AppsFlyer.OnUserReadyEvent)
expect fun platformAFOnUserLogout(event: AppEvent.AppsFlyer.OnUserLogoutEvent)
expect fun platformAFOnLogEvent(event: AppEvent.AppsFlyer.OnLogAFEvent)
expect fun platformAFOnGameStarted(event: AppEvent.AppsFlyer.OnGameStartedEvent)


