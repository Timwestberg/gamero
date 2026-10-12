package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus

class DeviceManager {
    companion object {
        val TAG: String = "DeviceManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Device>()
                .collect { evt ->
                    when (evt) {
                        AppEvent.Device.OnFetchDeviceInfo -> platformFetchDeviceInfo()
                        is AppEvent.Device.OnStoreDeviceInfo -> platformStoreDeviceInfo(evt.info)
                    }
                }
        }
    }
}

expect fun platformFetchDeviceInfo()
expect fun platformStoreDeviceInfo(info: AppEvent.Device.DeviceInfo)


