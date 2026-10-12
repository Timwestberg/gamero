package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformClearSession() {
    postNotification(name = "onClearSession")
}

actual fun platformStoreSession(event: AppEvent.Session.OnSessionReady) {
    val payload = mapOf(
        "userId" to event.userId,
        "userName" to event.userName,
    )
    postNotification(name = "onStoreSession", userInfo = payload)
}

actual fun platformSetSessionInfo(event: AppEvent.Session.OnSetSessionInfo) {
    val payload = mapOf(
        "sessionId" to event.sessionId,
        "deviceId" to event.deviceId,
    )
    postNotification(name = "onSetSessionInfo", userInfo = payload)
}

actual fun platformSetSessionToken(event: AppEvent.Session.OnSetSessionToken) {
    val payload = mapOf(
        "token" to event.token,
    )
    postNotification(name = "onSetSessionToken", userInfo = payload)
}


