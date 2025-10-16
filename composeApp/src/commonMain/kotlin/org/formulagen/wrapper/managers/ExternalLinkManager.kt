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

class ExternalLinkManager {
    companion object{
        val TAG: String = "ExternalLinkManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.ExternalLink>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.ExternalLink.OnManagerStart -> log(LogLevel.Info, TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                        is AppEvent.ExternalLink.OnOpenExternalLink -> platformOpenExternalLink(evt)
                    }
                }
        }
    }
}

expect fun platformOpenExternalLink(event: AppEvent.ExternalLink.OnOpenExternalLink)