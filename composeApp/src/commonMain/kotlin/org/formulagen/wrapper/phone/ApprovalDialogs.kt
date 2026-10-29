package org.formulagen.wrapper.phone

import org.formulagen.wrapper.conversations.ApprovalOption

/**
 * The phone's native dialogs: a UIAlertController on iOS, an AlertDialog on Android, a script in tests. Each call
 * shows one dialog and returns when the person closes it; cancelling the call closes it unanswered.
 */
internal interface ApprovalDialogs {
    /** Asks whether this phone should answer approvals for the account. True when the person agrees. */
    suspend fun confirm(prompt: DevicePrompt): Boolean

    /** Shows an approval with one button per choice and a dismiss button. The option picked, or null when dismissed. */
    suspend fun ask(prompt: ApprovalPrompt): ApprovalOption?

    /** Shows [notice] with one button, until the person closes it. */
    suspend fun tell(notice: ApprovalNotice)
}

/** The registration question: what answering approvals here means, and its two buttons. */
internal data class DevicePrompt(val title: String, val message: String, val accept: String, val decline: String)

/** An approval as its dialog shows it: the kind, where it comes from with the exact lines, and its answers. */
internal data class ApprovalPrompt(val title: String, val message: String, val choices: List<ApprovalChoice>, val dismiss: String)

/** One answer button. A destructive one ([ApprovalOption.DENY], [ApprovalOption.STOP]) is drawn as such. */
internal data class ApprovalChoice(val option: ApprovalOption, val label: String, val destructive: Boolean)

/** A dialog with one button: an approval only the Mac may answer, or how an approval ended without this answer. */
internal data class ApprovalNotice(val title: String, val message: String, val button: String)
