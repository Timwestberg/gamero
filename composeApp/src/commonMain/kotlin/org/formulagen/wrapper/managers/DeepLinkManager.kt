package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus

class DeepLinkManager {
    companion object {
        val TAG: String = "DeepLinkManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.DeepLink>()
                .collect { evt ->
                    when (evt) {
                        is AppEvent.DeepLink.OnNewIntentEvent -> platformOnNewIntent(evt)
                    }
                }
        }
    }
}

expect fun platformOnNewIntent(event: AppEvent.DeepLink.OnNewIntentEvent)


