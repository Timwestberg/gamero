package org.formulagen.wrapper.chat

import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.approvals.memberLabel
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.TeamMember
import org.formulagen.wrapper.providers.copy
import org.formulagen.wrapper.workspace.ProjectFolder
import org.formulagen.wrapper.workspace.WorkspaceStore

/**
 * A runtime that works in a project folder: it takes only [TurnRequest]s with
 * [TurnRequest.work], keeps its working session for the chat lease, and never writes summaries (a
 * working member summarizes through its talk-only [ChatRuntime]).
 */
internal interface WorkRuntime : AutoCloseable {
    val id: RuntimeId

    /** As [ChatRuntime.readiness]: the runtime's own sign-in. */
    fun readiness(profile: String): Readiness

    /** As [ChatRuntime.startTurn], for a working turn. */
    fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle

    /** End every working session now: what waits on the player is denied, and processes end. Never blocks for long. */
    fun release()
}

/** Whether a working message works or talks, before anything is asked. */
internal sealed interface WorkAvailability {
    data class Work(val folder: ProjectFolder) : WorkAvailability
    /** Answer as talk, with [notice] saying why. */
    data class Talk(val notice: String) : WorkAvailability
}

/**
 * What working members need: each companion's project folder ([workspaces]), the start-of-work
 * confirmation ([startOfWork]), and the [runtimes] this build lets work, by the same answer that
 * `providers.list.working` gives the page. A runtime without one answers working messages as talk.
 */
internal class ChatWork(val workspaces: WorkspaceStore, val startOfWork: StartOfWork, runtimes: List<WorkRuntime>) {
    val runtimes: Map<RuntimeId, WorkRuntime> = runtimes.associateBy { it.id }

    /**
     * Whether [member] can work for this companion now, with no side effect: its runtime may work
     * here, and the companion has a project folder that still exists. The start of work is asked
     * after this, only for [WorkAvailability.Work].
     */
    fun availability(profile: String, companionId: String, member: TeamMember): WorkAvailability {
        val label = memberLabel(member)
        if (member.runtimeId !in runtimes) return WorkAvailability.Talk(member.runtimeId.copy.cannotWork)
        val folder = workspaces.folder(profile, companionId) ?: return WorkAvailability.Talk("Choose a project folder to let $label work.")
        if (!folder.exists) return WorkAvailability.Talk("The project folder ${folder.name} was moved or deleted. Choose it again to let $label work.")
        return WorkAvailability.Work(folder)
    }
}
