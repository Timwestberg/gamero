package org.formulagen.wrapper.bridge

/** Calls [onPause] and [onResume] when the host app leaves and returns to the foreground. */
fun interface Lifecycle {
    fun observe(onPause: () -> Unit, onResume: () -> Unit): AutoCloseable
}

/** Events of the lifecycle capability. Mirrors `HOST_BRIDGE_EVENTS`. */
object LifecycleEvents {
    val PAUSE = BridgeEvent("lifecycle.pause", NoData.serializer())
    val RESUME = BridgeEvent("lifecycle.resume", NoData.serializer())

    internal val binder = CapabilityBinder<Lifecycle>(
        name = "lifecycle",
        handlers = emptyList(),
        events = listOf(PAUSE, RESUME),
        observe = { lifecycle, events ->
            lifecycle.observe(
                onPause = { events.emit(PAUSE, NoData) },
                onResume = { events.emit(RESUME, NoData) },
            )
        },
    )
}
