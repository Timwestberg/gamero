package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus

class PermissionManager {
    companion object {
        val TAG = "PermissionManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Permission>()
                .collect { evt ->
                    when(evt) {
                        AppEvent.Permission.OnPermissionRequest -> TODO()
                        is AppEvent.Permission.OnManagerStart -> TODO()
                    }
                }
        }
    }
}