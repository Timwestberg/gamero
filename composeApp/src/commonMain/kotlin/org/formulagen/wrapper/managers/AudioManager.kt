package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus

class AudioManager {
    companion object {
        val TAG: String = "AudioManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AudioEvent>()
                .collect { evt ->
                    when (evt) {
                        is AudioEvent.OnUserReadyEvent -> platformOnUserReady(evt)
                        is AudioEvent.OnUserLogoutEvent -> platformOnUserLogout(evt)
                        is AudioEvent.OnPlayingResponsiveWebGameEvent -> platformOnPlayingResponsiveWebGame(evt)
                        is AudioEvent.OnSetAmbientMusicStateEvent -> platformOnSetAmbientMusic(evt)
                        is AudioEvent.OnSetAmbientMusicSettingEvent -> platformOnSetAmbientMusicSetting(evt)
                        is AudioEvent.OnSetAmbientMusicSelectionEvent -> platformOnSetAmbientMusicSelection(evt)
                        is AudioEvent.OnSetLoadingStateEvent -> platformOnSetLoadingState(evt)
                        is AudioEvent.OnReturnFromGameEvent -> platformOnReturnFromGame(evt)
                        is AudioEvent.OnActivityLifeCycleEvent -> platformOnActivityLifeCycle(evt)
                    }
                }
        }
    }
}

sealed interface AudioEvent {
    data class OnUserReadyEvent(val data: org.formulagen.wrapper.event.AppEvent.Amplitude.OnUserReadyEvent.UserReady): AudioEvent
    data class OnUserLogoutEvent(val username: String?): AudioEvent
    data class OnPlayingResponsiveWebGameEvent(val isPlaying: Boolean): AudioEvent
    data class OnSetAmbientMusicStateEvent(val isPaused: Boolean): AudioEvent
    data class OnSetAmbientMusicSettingEvent(val enabled: Boolean?): AudioEvent
    data class OnSetAmbientMusicSelectionEvent(val musicSelection: MusicType): AudioEvent
    data class OnSetLoadingStateEvent(val loadProgress: Float): AudioEvent
    data object OnReturnFromGameEvent: AudioEvent
    data class OnActivityLifeCycleEvent(val isPaused: Boolean): AudioEvent
}

enum class MusicType { DEFAULT, NONE }

expect fun platformOnUserReady(event: AudioEvent.OnUserReadyEvent)
expect fun platformOnUserLogout(event: AudioEvent.OnUserLogoutEvent)
expect fun platformOnPlayingResponsiveWebGame(event: AudioEvent.OnPlayingResponsiveWebGameEvent)
expect fun platformOnSetAmbientMusic(event: AudioEvent.OnSetAmbientMusicStateEvent)
expect fun platformOnSetAmbientMusicSetting(event: AudioEvent.OnSetAmbientMusicSettingEvent)
expect fun platformOnSetAmbientMusicSelection(event: AudioEvent.OnSetAmbientMusicSelectionEvent)
expect fun platformOnSetLoadingState(event: AudioEvent.OnSetLoadingStateEvent)
expect fun platformOnReturnFromGame(event: AudioEvent.OnReturnFromGameEvent)
expect fun platformOnActivityLifeCycle(event: AudioEvent.OnActivityLifeCycleEvent)


