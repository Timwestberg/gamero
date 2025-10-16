package org.formulagen.wrapper.managers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.event.EventBus
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log

class GameManager {
    companion object{
        val TAG: String = "GameManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Game>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.Game.OnGameComplete -> platformCompleteGame()
                        is AppEvent.Game.OnLoadGame -> platformLoadGame()
                        is AppEvent.Game.OnManagerStart -> log(LogLevel.Info, TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                        is AppEvent.Game.OnStartGame -> platformStartGame()
                    }
                }
        }
    }
}

expect fun platformLoadGame()
expect fun platformStartGame()
expect fun platformCompleteGame()
