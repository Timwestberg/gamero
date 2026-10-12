package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformAFOnInstall(event: AppEvent.AppsFlyer.OnInstallEvent) {
    postNotification(name = "onAFInstall")
}

actual fun platformAFOnUserReady(event: AppEvent.AppsFlyer.OnUserReadyEvent) {
    val user = event.data.user
    val payload = mapOf(
        "userId" to user.userId,
        "userName" to user.userName,
        "legacyUserId" to (user.legacyUserId ?: 0),
    )
    postNotification(name = "onAFUserReady", userInfo = payload)
}

actual fun platformAFOnUserLogout(event: AppEvent.AppsFlyer.OnUserLogoutEvent) {
    val payload = mapOf("username" to event.username)
    postNotification(name = "onAFUserLogout", userInfo = payload)
}

actual fun platformAFOnLogEvent(event: AppEvent.AppsFlyer.OnLogAFEvent) {
    val payload = mapOf(
        "eventName" to event.eventName,
        "eventData" to (event.eventData?.toString())
    )
    postNotification(name = "onAFLogEvent", userInfo = payload)
}

actual fun platformAFOnGameStarted(event: AppEvent.AppsFlyer.OnGameStartedEvent) {
    val payload = mapOf(
        "tournamentInstanceEntryId" to event.tournamentInstanceEntryId,
        "realCash" to event.realCash,
        "userCashGamesPlayed" to event.userCashGamesPlayed,
        "isCashTournament" to event.isCashTournament
    )
    postNotification(name = "onAFGameStarted", userInfo = payload)
}


