package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.JsonObject

/** Calls [onPause] and [onResume] when the host app leaves and returns to the foreground. */
fun interface Lifecycle {
    fun observe(onPause: () -> Unit, onResume: () -> Unit): AutoCloseable
}

/** Event names of `host-bridge.v2`. Mirrors `HOST_BRIDGE_EVENTS`. */
object LifecycleEvents {
    const val PAUSE = "lifecycle.pause"
    const val RESUME = "lifecycle.resume"

    internal val binder = CapabilityBinder<Lifecycle>(
        methods = emptyList(),
        events = listOf(PAUSE, RESUME),
        observe = { lifecycle, emit ->
            lifecycle.observe(
                onPause = { emit(PAUSE, JsonObject(emptyMap())) },
                onResume = { emit(RESUME, JsonObject(emptyMap())) },
            )
        },
    )
}
