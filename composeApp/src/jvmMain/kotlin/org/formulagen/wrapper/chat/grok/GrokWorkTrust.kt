package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import org.formulagen.wrapper.approvals.CancelSignal
import org.formulagen.wrapper.approvals.NativeDialog
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.displayPath
import org.formulagen.wrapper.approvals.visibleText

/**
 * Folder trust outlives a work lease, so it gets its own explicit native decision. [selected] is the
 * project folder chosen in Hovel; [root] is the folder Grok would trust, which can enclose it.
 */
internal fun interface GrokWorkTrust {
    fun ask(selected: Path, root: Path, cancel: CancelSignal): Boolean
}

/** A declined or revoked trust decision is an actionable setup state, not a provider outage. */
internal class GrokWorkTrustDeclined : IllegalStateException("Grok project trust was not approved")

/** Grok's trust root covers the player's home folder or the disk, so Hovel refuses without asking. */
internal class GrokWorkTrustTooBroad : IllegalStateException("Grok project trust would cover the home folder")

/** Uses the shared native dialog presenter, with no page-provided approval response. */
internal class NativeGrokWorkTrust(private val dialogs: NativeDialogs) : GrokWorkTrust {
    override fun ask(selected: Path, root: Path, cancel: CancelSignal) = dialogs.ask(grokWorkTrustDialog(selected, root), cancel) == true
}

/** The persistence and scope are explicit; Escape, close and cancellation always decline. */
internal fun grokWorkTrustDialog(selected: Path, root: Path) = NativeDialog(
    title = "Trust this project in Grok Build?",
    lines = listOfNotNull("Project folder in Hovel: ${displayPath(selected)}",
        "Folder Grok will trust: ${displayPath(root)}",
        if (root != selected) "These are different folders. Grok trusts the whole folder ${displayPath(root)}, " +
            "including everything in it outside your project folder." else null,
        "Grok needs project trust to load this folder's instructions and settings.",
        "Grok will remember trust for this folder and its subfolders, including future runs outside Hovel. " +
            "Project configuration can enable integrations or allow operations in those runs.",
        "Hovel still checks its supported tool and approval settings before starting work.").map(::visibleText),
    buttons = listOf("Trust project in Grok" to true, "Not now" to false), dismiss = false,
)
