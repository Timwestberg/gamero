package org.formulagen.wrapper.progression.embedded

import java.awt.Canvas

/**
 * Tiny JAWT bridge; its lifetime is bounded by the host's native Canvas. Both the standalone
 * prototype (Play Embedded Companions) and the catalog app's games.launch (Play From Catalog)
 * load it from HOVEL_EMBED_LIBRARY; [DesktopGameLauncher.installedGame] checks the file first.
 */
internal object MacGameLayer {
    init {
        // The library leaves JAWT_GetAWT unbound (scripts/build-embedded-macos.sh): load the running runtime's jawt first.
        System.loadLibrary("jawt")
        System.load(requireNotNull(System.getenv("HOVEL_EMBED_LIBRARY")) { "Set HOVEL_EMBED_LIBRARY to the built embed library" })
    }
    /** Attach the engine's remote layer to this realized component. */
    external fun attach(canvas: Canvas, contextId: Long): Boolean
    /** Release the remote layer before disposing the host component. */
    external fun detach(canvas: Canvas)
}
