package org.formulagen.wrapper.workspace

import java.nio.file.Path

/** A release build has no project workspaces: none of their code is compiled into it (docs/desktop.md, "Build boundaries"). */
@Suppress("UNUSED_PARAMETER")
internal fun desktopWorkspaces(environment: Map<String, String>, support: Path): DesktopWorkspaces? = null
