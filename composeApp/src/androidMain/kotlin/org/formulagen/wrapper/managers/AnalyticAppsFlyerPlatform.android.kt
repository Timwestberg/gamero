package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformAFOnInstall(event: AppEvent.AppsFlyer.OnInstallEvent) {
    log(LogLevel.Info, AppsFlyerManager.TAG, "AF install")
}

actual fun platformAFOnUserReady(event: AppEvent.AppsFlyer.OnUserReadyEvent) {
    log(LogLevel.Info, AppsFlyerManager.TAG, "AF user ready: ${event.data.user.userId}")
}

actual fun platformAFOnUserLogout(event: AppEvent.AppsFlyer.OnUserLogoutEvent) {
    log(LogLevel.Info, AppsFlyerManager.TAG, "AF user logout: ${event.username}")
}

actual fun platformAFOnLogEvent(event: AppEvent.AppsFlyer.OnLogAFEvent) {
    log(LogLevel.Info, AppsFlyerManager.TAG, "AF event: ${event.eventName}")
}

actual fun platformAFOnGameStarted(event: AppEvent.AppsFlyer.OnGameStartedEvent) {
    log(LogLevel.Info, AppsFlyerManager.TAG, "AF game started: ${event.tournamentInstanceEntryId}")
}


