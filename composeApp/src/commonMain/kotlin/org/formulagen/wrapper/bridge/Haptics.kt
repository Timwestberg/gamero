package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

fun interface Haptics {
    suspend fun play(effect: HapticEffect)
}

@Serializable
enum class HapticEffect {
    @SerialName("light") LIGHT,
    @SerialName("medium") MEDIUM,
    @SerialName("heavy") HEAVY,
    @SerialName("selection") SELECTION,
    @SerialName("success") SUCCESS,
    @SerialName("warning") WARNING,
    @SerialName("error") ERROR,
}

@Serializable
data class HapticPlayArgs(val effect: HapticEffect)

object HapticMethods {
    val PLAY = BridgeMethod("haptic.play", HapticPlayArgs.serializer(), NoResult.serializer())

    internal val binder = CapabilityBinder<Haptics>(listOf(PLAY), handlers = { haptics ->
        listOf(PLAY.handle { args -> haptics.play(args.effect); NoResult })
    })
}
