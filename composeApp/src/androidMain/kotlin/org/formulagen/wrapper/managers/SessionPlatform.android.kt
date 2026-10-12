package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformClearSession() {
    log(LogLevel.Info, SessionManager.TAG, "Clear session (Android stub)")
}

actual fun platformStoreSession(event: AppEvent.Session.OnSessionReady) {
    log(LogLevel.Info, SessionManager.TAG, "Store session for ${event.userId} (Android stub)")
}

actual fun platformSetSessionInfo(event: AppEvent.Session.OnSetSessionInfo) {
    log(LogLevel.Info, SessionManager.TAG, "Set session info ${event.sessionId} (Android stub)")
}

actual fun platformSetSessionToken(event: AppEvent.Session.OnSetSessionToken) {
    log(LogLevel.Info, SessionManager.TAG, "Set session token (Android stub)")
}


