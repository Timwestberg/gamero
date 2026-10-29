package org.formulagen.wrapper.phone

import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalKind
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalPresentation
import org.formulagen.wrapper.conversations.ApprovalState
import org.formulagen.wrapper.conversations.ApprovalSurface

/**
 * What the phone's approval dialogs say. Every line an approval shows is the server's, as the Mac built it
 * (folder-relative paths); this file only adds the kind, where it comes from, and the buttons.
 */
internal object PhoneApprovalCopy {
    val REGISTER = DevicePrompt(
        title = "Answer approvals on this phone?",
        message = "Hovel will show your companions' permission requests, start-of-work confirmations, stage approvals " +
            "and resume reviews here, and your answer goes to the Mac. Anything outside a project's folders is answered " +
            "on the Mac only.",
        accept = "Use this phone",
        decline = "Not now",
    )

    /** The server did not confirm an answer: it may not have reached the Mac. */
    val UNSENT = ApprovalNotice(
        title = "Answer not confirmed",
        message = "Hovel could not confirm your answer. Check the request on the Mac, or open it again from Review.",
        button = "OK",
    )

    /** The dismiss button of an approval: closing it leaves the approval open on the Mac. */
    const val LATER = "Later"
    const val OK = "OK"

    private val KIND_TITLES = mapOf(
        ApprovalKind.PERMISSION to "Permission request",
        ApprovalKind.START_OF_WORK to "Start work?",
        ApprovalKind.STAGE to "Workflow stage",
        ApprovalKind.RESUME_REVIEW to "Resume or retry?",
    )

    private val OPTION_LABELS = mapOf(
        ApprovalOption.ALLOW_ONCE to "Allow once",
        ApprovalOption.DENY to "Deny",
        ApprovalOption.CONTINUE to "Continue",
        ApprovalOption.REQUEST_CHANGES to "Request changes",
        ApprovalOption.NOT_NOW to "Not now",
        ApprovalOption.STOP to "Stop",
    )

    private val DESTRUCTIVE = setOf(ApprovalOption.DENY, ApprovalOption.STOP)

    fun label(option: ApprovalOption): String = OPTION_LABELS.getValue(option)

    /** Where an approval comes from, on the dialog's first line. */
    private fun origin(shown: DeviceApproval) = "${shown.companionName} · ${shown.projectName} on ${shown.computerName}"

    /** An approval the phone may answer: its exact folder-relative lines, and one button per option it offers. */
    fun prompt(shown: DeviceApproval, lines: List<String>): ApprovalPrompt = ApprovalPrompt(
        title = KIND_TITLES.getValue(shown.approval.kind),
        message = origin(shown) + "\n\n" + lines.joinToString("\n"),
        choices = shown.approval.options.map { ApprovalChoice(it, label(it), it in DESTRUCTIVE) },
        dismiss = LATER,
    )

    /** An approval that names something outside the project's folders: only its Mac may answer it. */
    fun macOnly(shown: DeviceApproval, summary: String): ApprovalNotice = ApprovalNotice(
        title = "Answer on ${shown.computerName}",
        message = origin(shown) + "\n\n" + summary + "\n\nThis request names something outside the project's folders, " +
            "so only ${shown.computerName} can answer it.",
        button = OK,
    )

    /** How [ended] ended without this phone's answer, so the person sees who won. [deviceId] is this phone. */
    fun lost(ended: AgentApproval, computerName: String, deviceId: String): ApprovalNotice {
        val answer = ended.answer
        val (title, message) = when {
            ended.state == ApprovalState.ANSWERED && answer != null -> {
                val where = when {
                    answer.by == ApprovalSurface.MAC -> computerName
                    answer.deviceId == deviceId -> "this phone"
                    else -> "another phone"
                }
                "Already answered on $where" to "${label(answer.option)} was chosen there first. Your answer was not used."
            }
            ended.state == ApprovalState.WITHDRAWN -> "No longer needed" to "The work no longer needs this approval."
            else -> "This approval expired" to "Nothing was answered in time."
        }
        return ApprovalNotice(title, message, OK)
    }

    /** The lines of an approval the phone may answer, or null for one only the Mac may answer. */
    fun linesOf(presentation: ApprovalPresentation): List<String>? = (presentation as? ApprovalPresentation.Anywhere)?.lines
}
