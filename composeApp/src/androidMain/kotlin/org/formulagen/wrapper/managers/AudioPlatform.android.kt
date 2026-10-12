package org.formulagen.wrapper.managers

import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

actual fun platformOnUserReady(event: AudioEvent.OnUserReadyEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio user ready: ${event.data.userId}")
}

actual fun platformOnUserLogout(event: AudioEvent.OnUserLogoutEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio user logout: ${event.username}")
}

actual fun platformOnPlayingResponsiveWebGame(event: AudioEvent.OnPlayingResponsiveWebGameEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio playing responsive: ${event.isPlaying}")
}

actual fun platformOnSetAmbientMusic(event: AudioEvent.OnSetAmbientMusicStateEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio set ambient state: ${event.isPaused}")
}

actual fun platformOnSetAmbientMusicSetting(event: AudioEvent.OnSetAmbientMusicSettingEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio set ambient setting: ${event.enabled}")
}

actual fun platformOnSetAmbientMusicSelection(event: AudioEvent.OnSetAmbientMusicSelectionEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio set ambient selection: ${event.musicSelection}")
}

actual fun platformOnSetLoadingState(event: AudioEvent.OnSetLoadingStateEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio set loading: ${event.loadProgress}")
}

actual fun platformOnReturnFromGame(event: AudioEvent.OnReturnFromGameEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio return from game")
}

actual fun platformOnActivityLifeCycle(event: AudioEvent.OnActivityLifeCycleEvent) {
    log(LogLevel.Info, AudioManager.TAG, "Audio activity paused: ${event.isPaused}")
}


