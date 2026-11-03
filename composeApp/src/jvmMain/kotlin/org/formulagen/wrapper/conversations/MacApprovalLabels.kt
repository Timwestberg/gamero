package org.formulagen.wrapper.conversations

/**
 * The Mac's own button for each option of each approval kind, as its native dialogs show it: the permission dialog
 * (`approvalDialog`), the start-of-work confirmation (`startOfWorkDialog`), a workflow stage (`stageDialog`) and the
 * resume review (`resumeDialog`). What the Mac says about an approval uses these words, so they match what the person
 * clicked. `RemoteApprovalsTest.theMacsLabelsAreItsDialogsButtons` checks every kind against the dialogs themselves.
 */
internal object MacApprovalLabels {
    private val LABELS: Map<ApprovalKind, Map<ApprovalOption, String>> = mapOf(
        ApprovalKind.PERMISSION to mapOf(ApprovalOption.ALLOW_ONCE to "Allow once", ApprovalOption.DENY to "Deny"),
        ApprovalKind.START_OF_WORK to mapOf(ApprovalOption.CONTINUE to "Start working", ApprovalOption.NOT_NOW to "Not now"),
        ApprovalKind.STAGE to mapOf(
            ApprovalOption.CONTINUE to "Continue",
            ApprovalOption.REQUEST_CHANGES to "Request changes",
            ApprovalOption.NOT_NOW to "Not now",
            ApprovalOption.STOP to "Stop",
        ),
        ApprovalKind.RESUME_REVIEW to mapOf(ApprovalOption.CONTINUE to "Continue", ApprovalOption.NOT_NOW to "Keep paused"),
    )

    /** The Mac's button for [option] on a [kind] dialog; an option the kind never offers keeps its contract name. */
    fun of(kind: ApprovalKind, option: ApprovalOption): String = LABELS[kind]?.get(option) ?: option.name.lowercase().replace('_', '-')
}
