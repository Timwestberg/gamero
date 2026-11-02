package org.formulagen.wrapper.phone

import org.formulagen.wrapper.bridge.CompanionLimits
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.conversations.AgentApproval
import org.formulagen.wrapper.conversations.ApprovalKind
import org.formulagen.wrapper.conversations.ApprovalOption
import org.formulagen.wrapper.conversations.ApprovalPresentation
import org.formulagen.wrapper.conversations.ApprovalState
import org.formulagen.wrapper.conversations.ApprovalSurface
import org.formulagen.wrapper.conversations.ShareableText

/**
 * What the phone's approval dialogs say. An approval's message is only its lines, exactly as the server holds them
 * (the Mac built them with folder-relative paths), and only when every one passes [ShareableText]; anything else is
 * answered on the Mac. Where it comes from goes in the title, built from names cleaned to one line
 * ([SharedRules.dialogName]), so no name can pass for a request line. This file adds only that, the kind, and the
 * buttons.
 */
internal object PhoneApprovalCopy {
    val REGISTER = DevicePrompt(
        title = "Answer approvals on this phone?",
        message = "Hovel will show your companions' permission requests, start-of-work confirmations, stage approvals " +
            "and resume reviews here, and your answer goes to the Mac. You confirm this phone on each Mac it may " +
            "answer for. Anything outside a project's folders is answered on the Mac only.",
        accept = "Use this phone",
        decline = "Not now",
    )

    /** The server did not confirm an answer: it may not have reached the Mac. */
    val UNSENT = ApprovalNotice(
        title = "Answer not confirmed",
        message = "Hovel could not confirm your answer. Check the request on the Mac, or open it again from Review.",
        button = "OK",
    )

    /** The phone may no longer answer: its answer did not count. */
    val NOT_USED = ApprovalNotice(
        title = "Answer on the Mac",
        message = "This phone can no longer answer this request, so your answer was not used. Answer it on the Mac.",
        button = "OK",
    )

    /** The dismiss button of an approval: closing it leaves the approval open on the Mac. */
    const val LATER = "Later"
    const val OK = "OK"

    private val KIND_TITLES = mapOf(
        ApprovalKind.PERMISSION to "Permission request",
        ApprovalKind.START_OF_WORK to "Start of work",
        ApprovalKind.STAGE to "Workflow stage",
        ApprovalKind.RESUME_REVIEW to "Resume or retry",
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

    /** The approval's Mac, cleaned to one line, for a title. */
    fun computerOf(shown: DeviceApproval): String = SharedRules.dialogName(shown.computerName, "your Mac")

    /** The companion and project an approval comes from, each cleaned to one line. */
    private fun source(shown: DeviceApproval): String =
        "${SharedRules.dialogName(shown.companionName, "Your companion")} · ${SharedRules.dialogName(shown.projectName, "a project")}"


    /**
     * An approval the phone may answer: its Mac first in the title (a narrow alert cuts a title at its end), then the
     * kind and where it comes from; its exact folder-relative [lines] as the whole message; one button per option.
     */
    fun prompt(shown: DeviceApproval, lines: List<String>): ApprovalPrompt = ApprovalPrompt(
        title = "${computerOf(shown)}: ${KIND_TITLES.getValue(shown.approval.kind)} · ${source(shown)}",
        message = lines.joinToString("\n"),
        choices = shown.approval.options.map { ApprovalChoice(it, label(it), it in DESTRUCTIVE) },
        dismiss = LATER,
    )

    /**
     * An approval only its Mac may answer: it names something outside the project's folders, or holds text this phone
     * will not show. Its summary shows only when it passes [ShareableText].
     */
    fun macOnly(shown: DeviceApproval): ApprovalNotice {
        val summary = (shown.approval.presentation as? ApprovalPresentation.MacOnly)?.summary
            ?.takeIf { ShareableText.isShareable(it, CompanionLimits.APPROVAL_SUMMARY) }
        val why = if (summary != null) "This request names something outside the project's folders"
            else "This request holds text this phone will not show"
        return ApprovalNotice(
            title = "Answer on ${computerOf(shown)} · ${source(shown)}",
            message = listOfNotNull(summary, "$why, so only that Mac can answer it.").joinToString("\n\n"),
            button = OK,
        )
    }

    /** An approval of a Mac that has not confirmed this phone: answer it there, or pair the phone there. */
    fun unpaired(shown: DeviceApproval): ApprovalNotice = ApprovalNotice(
        title = "Answer on ${computerOf(shown)} · ${source(shown)}",
        message = "This phone is not paired with that Mac, so it can't answer this request. Answer it there, or pair " +
            "this phone on that Mac: choose Pair a phone… in its Hovel menu.",
        button = OK,
    )

    /** The phone's pairing screen: its code, to type into a Mac's "Pair a phone…" dialog. */
    fun pairing(code: DevicePairingCode): ApprovalNotice = ApprovalNotice(
        title = "Pair with a Mac",
        message = "On the Mac, choose Pair a phone… in the Hovel menu and type this code:\n\n" +
            "${code.code.take(3)} ${code.code.drop(3)}\n\nThe code works for 10 minutes. This phone answers only the " +
            "approvals of the Macs that confirm it.",
        button = "Close",
    )

    /** How [ended] ended without this phone's answer, so the person sees who won. [deviceId] is this phone. */
    fun lost(ended: AgentApproval, shown: DeviceApproval, deviceId: String): ApprovalNotice {
        val answer = ended.answer
        val (title, message) = when {
            ended.state == ApprovalState.ANSWERED && answer != null -> {
                val where = when {
                    answer.by == ApprovalSurface.MAC -> computerOf(shown)
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

    /**
     * The lines of an approval the phone may answer, or null for one only the Mac may answer: `mac-only`, or any line
     * that fails [ShareableText] (a control, a separator, an invisible character or an absolute path), or more lines
     * than the contract allows.
     */
    fun linesOf(presentation: ApprovalPresentation): List<String>? {
        val lines = (presentation as? ApprovalPresentation.Anywhere)?.lines ?: return null
        val shown = lines.size in 1..CompanionLimits.APPROVAL_LINES &&
            lines.all { ShareableText.isShareable(it, CompanionLimits.APPROVAL_LINE) }
        return lines.takeIf { shown }
    }
}
