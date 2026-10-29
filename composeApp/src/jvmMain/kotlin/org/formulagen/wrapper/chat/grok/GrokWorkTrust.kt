package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.displayPath

/** Folder trust outlives a work lease, so it gets its own explicit native decision. */
internal fun interface GrokWorkTrust {
    fun ask(folder: Path, cancel: CancelSignal): Boolean
}

/** A declined or revoked trust decision is an actionable setup state, not a provider outage. */
internal class GrokWorkTrustDeclined : IllegalStateException("Grok project trust was not approved")

/** Uses the shared native dialog presenter, with no page-provided approval response. */
internal class NativeGrokWorkTrust(private val dialogs: NativeDialogs) : GrokWorkTrust {
    override fun ask(folder: Path, cancel: CancelSignal) = dialogs.ask(grokWorkTrustDialog(folder), cancel) == true
}

/** The persistence and scope are explicit; Escape, close and cancellation always decline. */
internal fun grokWorkTrustDialog(folder: Path) = NativeDialog(
    title = "Trust this project in Grok Build?",
    lines = listOf("Project folder: ${displayPath(folder)}",
        "Grok needs project trust to load this folder's instructions and settings.",
        "Grok will remember trust for this project and its subfolders, including future runs outside Hovel. " +
            "Project configuration can enable integrations or allow operations in those runs.",
        "Hovel still checks its supported tool and approval settings before starting work."),
    buttons = listOf("Trust project in Grok" to true, "Not now" to false), dismiss = false,
)
