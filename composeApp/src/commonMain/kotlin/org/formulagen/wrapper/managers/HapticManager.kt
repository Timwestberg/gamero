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
import org.formulagen.wrapper.managers.AppManager.Companion

class HapticManager {
    companion object{
        val TAG: String = "HapticManager"
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        scope.launch {
            EventBus.events
                .filterIsInstance<AppEvent.Haptic>()
                .collect { evt ->
                    when(evt) {
                        is AppEvent.Haptic.OnHapticFeedback -> play(AppEvent.HapticFeedbackEffect.fromType(evt.effect))
                        is AppEvent.Haptic.OnManagerStart -> log(LogLevel.Info, AppManager.TAG, "Manager: ${evt.name} has been verified on ${evt.platform}")
                    }
                }
        }
    }

    private fun play(effect: AppEvent.HapticFeedbackEffect){
        platformPlayHapticFeedbackEffect(effect)
    }
}

expect fun platformPlayHapticFeedbackEffect(effect: AppEvent.HapticFeedbackEffect)