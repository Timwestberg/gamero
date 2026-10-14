package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformFetchDeviceInfo() {
    postNotification(name = "onFetchDeviceInfo")
}

actual fun platformStoreDeviceInfo(info: AppEvent.DeviceInfo) {
    val payload = mapOf(
        "deviceModel" to info.deviceModel,
        "osName" to info.osName,
        "osVersion" to info.osVersion,
        "appVersion" to info.appVersion,
        "locale" to info.locale,
        "advertisingId" to info.advertisingId,
        "deviceId" to info.deviceId
    )
    postNotification(name = "onStoreDeviceInfo", userInfo = payload)
}
