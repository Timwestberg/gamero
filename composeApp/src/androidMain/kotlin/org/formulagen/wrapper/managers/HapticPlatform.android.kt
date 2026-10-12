package org.formulagen.wrapper.managers

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import org.formulagen.wrapper.platform.AppContext
import org.formulagen.wrapper.event.AppEvent

private fun vibrator(context: Context): Vibrator? = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

actual fun platformPlayHapticFeedbackEffect(effect: AppEvent.HapticFeedbackEffect) {
    // Simple mapping using one-shot vibrate
    val ctx = AppContext.get() ?: return
    val vib = vibrator(ctx) ?: return
    val duration = when (effect) {
        AppEvent.HapticFeedbackEffect.LIGHT, AppEvent.HapticFeedbackEffect.SELECTION, AppEvent.HapticFeedbackEffect.SOFT -> 20L
        AppEvent.HapticFeedbackEffect.MEDIUM, AppEvent.HapticFeedbackEffect.RIGID -> 35L
        AppEvent.HapticFeedbackEffect.HEAVY, AppEvent.HapticFeedbackEffect.SUCCESS, AppEvent.HapticFeedbackEffect.WARNING -> 50L
        AppEvent.HapticFeedbackEffect.ERROR, AppEvent.HapticFeedbackEffect.FAILURE -> 70L
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vib.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vib.vibrate(duration)
    }
}

actual fun platformOnUserReady(event: AppEvent.Haptic.OnUserReadyEvent) { /* no-op */ }
actual fun platformOnSetHapticFeedbackSetting(event: AppEvent.Haptic.OnSetHapticFeedbackSettingEvent) { /* no-op */ }
actual fun platformOnPlayHapticEffect(event: AppEvent.Haptic.OnPlayHapticEffectEvent) {
    platformPlayHapticFeedbackEffect(event.effect)
}


