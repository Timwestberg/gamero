package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformFetchDeviceInfo() {
    log(LogLevel.Info, DeviceManager.TAG, "Fetch device info")
}

actual fun platformStoreDeviceInfo(info: AppEvent.DeviceInfo) {
    log(LogLevel.Info, DeviceManager.TAG, "Store device info")
}
