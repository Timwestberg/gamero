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

class AppManager {
    companion object{
        val TAG: String = "AppManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.App>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.App.OnManagerStart -> log(LogLevel.Info, TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                        AppEvent.App.OnSplashAnimationStart -> platformRemoveAppSplashScreen()
                        AppEvent.App.OnVerifyManagers -> platformVerifyBridges()
                        AppEvent.App.OnWebContentReady -> log(LogLevel.Info, TAG, "Web Content is Ready")
                    }
                }
        }
    }
}

expect fun platformRemoveAppSplashScreen()
expect fun platformVerifyBridges()