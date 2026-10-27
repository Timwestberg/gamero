package org.formulagen.wrapper.progression.embedded

import java.awt.Canvas

/** Tiny JAWT bridge; its lifetime is bounded by the host's native Canvas. */
internal object MacGameLayer {
    init { System.load(requireNotNull(System.getenv("HOVEL_EMBED_LIBRARY")) { "Launch with Play Embedded Companions.command" }) }
    /** Attach the engine's remote layer to this realized component. */
    external fun attach(canvas: Canvas, contextId: Long): Boolean
    /** Release the remote layer before disposing the host component. */
    external fun detach(canvas: Canvas)
}
