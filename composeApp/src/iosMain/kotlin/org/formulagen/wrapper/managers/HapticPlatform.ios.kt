package org.formulagen.wrapper.managers

import org.formulagen.wrapper.event.AppEvent
import org.formulagen.wrapper.postNotification

actual fun platformPlayHapticFeedbackEffect(effect: AppEvent.HapticFeedbackEffect) {
    val payload = mapOf("effect" to effect.toString())
    postNotification(name = "onHapticFeedback", userInfo = payload)
}

actual fun platformOnUserReady(event: AppEvent.Haptic.OnUserReadyEvent) {
    postNotification(name = "onHapticUserReady", userInfo = mapOf("enabled" to event.enabled))
}

actual fun platformOnSetHapticFeedbackSetting(event: AppEvent.Haptic.OnSetHapticFeedbackSettingEvent) {
    postNotification(name = "onSetHapticFeedbackSetting", userInfo = mapOf("enabled" to event.enabled))
}

actual fun platformOnPlayHapticEffect(event: AppEvent.Haptic.OnPlayHapticEffectEvent) {
    postNotification(name = "onPlayHapticEffect", userInfo = mapOf("effect" to event.effect.toString()))
}



