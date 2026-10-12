package org.formulagen.wrapper.managers

import org.formulagen.wrapper.postNotification

actual fun platformOnUserReady(event: AudioEvent.OnUserReadyEvent) {
    postNotification("onAudioUserReady", mapOf("userId" to event.data.userId))
}

actual fun platformOnUserLogout(event: AudioEvent.OnUserLogoutEvent) {
    postNotification("onAudioUserLogout", mapOf("username" to event.username))
}

actual fun platformOnPlayingResponsiveWebGame(event: AudioEvent.OnPlayingResponsiveWebGameEvent) {
    postNotification("onAudioPlayingResponsiveWebGame", mapOf("isPlaying" to event.isPlaying))
}

actual fun platformOnSetAmbientMusic(event: AudioEvent.OnSetAmbientMusicStateEvent) {
    postNotification("onAudioSetAmbientMusicState", mapOf("isPaused" to event.isPaused))
}

actual fun platformOnSetAmbientMusicSetting(event: AudioEvent.OnSetAmbientMusicSettingEvent) {
    postNotification("onAudioSetAmbientMusicSetting", mapOf("enabled" to event.enabled))
}

actual fun platformOnSetAmbientMusicSelection(event: AudioEvent.OnSetAmbientMusicSelectionEvent) {
    postNotification("onAudioSetAmbientMusicSelection", mapOf("selection" to event.musicSelection.toString()))
}

actual fun platformOnSetLoadingState(event: AudioEvent.OnSetLoadingStateEvent) {
    postNotification("onAudioSetLoadingState", mapOf("loadProgress" to event.loadProgress))
}

actual fun platformOnReturnFromGame(event: AudioEvent.OnReturnFromGameEvent) {
    postNotification("onAudioReturnFromGame")
}

actual fun platformOnActivityLifeCycle(event: AudioEvent.OnActivityLifeCycleEvent) {
    postNotification("onAudioActivityLifeCycle", mapOf("isPaused" to event.isPaused))
}


