package org.formulagen.wrapper.runtimes

import org.formulagen.wrapper.bridge.ModelEffort

import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.serialization.Serializable
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.conversation.TeamMember
import org.formulagen.wrapper.providers.copy

/**
 * A runtime's own conversation with one role: Codex's thread id, Claude Code's session id or a Grok binding's handle.
 * [personaHash] is the [Persona.hash] the conversation was last used with. [playerLogin] is set on every conversation
 * opened since every runtime runs on the player's own sign-in; only Codex reads it, because a Codex thread from before
 * then lives in Hovel's old Codex home and cannot be resumed.
 */
@Serializable
internal data class NativeConversation(val id: String, val personaHash: String, val playerLogin: Boolean = false)

/**
 * A talk-only runtime, one implementation per runtime, which a conversation's coordinator and its talk roles run on.
 * Implementations deliver [TurnEvents] from their own threads and must never hold a lock of theirs while doing so,
 * because the caller may call [release] and [TurnHandle.stop] while holding its own.
 */
internal interface ChatRuntime : AutoCloseable {
    val id: RuntimeId

    /**
     * A short local check of the runtime's own sign-in, for [accountId]'s turns, without starting one. It may block
     * briefly, so callers run it off the bridge thread.
     */
    fun readiness(accountId: String): Readiness

    /**
     * One reply. [TurnRequest.conversation] is this runtime's own conversation for the companion, or
     * null for a new one. Blocks until the turn has started (or throws); the reply then arrives
     * through [events], possibly before this returns.
     */
    fun startTurn(turn: TurnRequest, events: TurnEvents): TurnHandle

    /** End everything this runtime is running for chat now (teardown, watchdog, a closed view). Never blocks; safe to call repeatedly. */
    fun release()
}

/**
 * What one turn needs, for the signed-in account [accountId] (its local folders are keyed by it). [model] is a model
 * id or `runtime-default` (null means the same). [text] is the turn's whole input. [work] is set for a working turn,
 * which only a working runtime takes; [persona] is then [Persona.working] and [conversation] the runtime's working
 * conversation.
 */
internal data class TurnRequest(
    val accountId: String,
    val companionId: String,
    val persona: Persona,
    val model: String?,
    val conversation: NativeConversation?,
    val text: String,
    val work: WorkScope? = null,
    val effort: ModelEffort? = null,
) {
    init { require(SharedRules.isRecordId(accountId)) { "Turns are keyed by an account id" } }
}

/**
 * Where and for whom a member works: the project's [folders] (a working folder, and read-only context
 * folders the runtime was cleared to read), the [lease] a working process lives for (a chat view's
 * session id, or a conversation thread's id), the [companion]'s name and the [member].
 */
internal data class WorkScope(val folders: ProjectFolders, val lease: String, val companion: String, val member: TeamMember) {
    /** A scope for one working folder and no context folders. */
    constructor(folder: Path, lease: String, companion: String, member: TeamMember) : this(ProjectFolders(folder), lease, companion, member)

    init { require(folders.working != null) { "A working turn needs a working folder" } }

    /** The working folder: where the member works and the only folder it may change. */
    val folder: Path get() = checkNotNull(folders.working)
}

/**
 * A project's folders on this Mac: the one [working] folder agents may edit (none for a talk-only
 * project), and [context] folders they may only read. A runtime that cannot keep a context folder
 * read-only ([ContextFolderSupport]) is never given it.
 */
internal data class ProjectFolders(val working: Path?, val context: List<Path> = emptyList()) {
    init { require(working != null || context.isEmpty()) { "Context folders take effect only beside a working folder" } }

    /** These folders as [runtime] may be given them: its context folders only when it keeps them read-only. */
    fun forRuntime(support: ContextFolderSupport): ProjectFolders = if (support.readable) this else copy(context = emptyList())
}

/**
 * Whether a work runtime keeps a project's context folders read-only. Hovel overrides the player's
 * provider settings for those paths only; a runtime that cannot is not given them, and the project
 * lists it as unable to read them (`readableBy`).
 */
internal enum class ContextFolderSupport {
    /** The mechanism is in place and a live probe confirmed it: the runtime is given context folders. */
    VERIFIED,
    /** The mechanism is in place, but no live probe has confirmed it yet, so it counts as [NONE]. */
    UNVERIFIED,
    /** No mechanism: the runtime is never given context folders. */
    NONE;

    /** Whether the runtime may be given context folders now. */
    val readable: Boolean get() = this == VERIFIED
}

/**
 * What a turn reports. Nothing is reported after [completed] or [failed]. After the caller's
 * [TurnHandle.stop] or [ChatRuntime.release] an event already on its way may still arrive, so the
 * caller ignores events for a turn it ended.
 */
internal interface TurnEvents {
    /** The runtime started a new native conversation; the caller saves it before the reply ends, so a stopped turn keeps it. */
    fun opened(conversation: String)

    /** The runtime took the turn, with its context (Codex: `turn/start` answered; Claude: a verified start), so the context is no longer owed. */
    fun accepted()

    /** The reply so far. Each call replaces the previous text: Codex restarts it when a new message item begins. */
    fun partial(text: String)

    fun completed(text: String, conversation: String)

    fun failed(failure: ChatFailure)

    /** A working turn's activity so far; each call replaces the previous one. */
    fun activity(activity: Activity) = Unit
}

/** The person chose Stop: end the reply gracefully and keep the text so far. Never blocks. */
internal fun interface TurnHandle {
    fun stop()
}

/** Why a turn ended without a reply, independent of which runtime ran it. */
internal sealed interface ChatFailure {
    /** The runtime's sign-in is gone or expired. */
    data object SignedOut : ChatFailure
    /** The plan's limit is reached; [resetsAt] when the runtime says. */
    data class UsageLimit(val resetsAt: Instant?) : ChatFailure
    data object ModelUnavailable : ChatFailure
    /** The provider's explicit project trust was declined or revoked before work could start. */
    data object ProjectTrustRequired : ChatFailure
    /** The provider would trust the player's whole home folder (or more) for this project, which Hovel never asks. */
    data object ProjectTrustTooBroad : ChatFailure
    /** Overloaded, a server error or a dropped connection: sending again later may work. */
    data object Temporary : ChatFailure
    /** The runtime no longer has the conversation, or it was started with another persona: start a new one. */
    data object ConversationLost : ChatFailure
    /** The runtime started with tools, servers, plugins or permissions a talk-only companion must not have. */
    data object Lockdown : ChatFailure
    data object Unexpected : ChatFailure
}

internal sealed interface Readiness {
    data object Ready : Readiness
    /** Installed, but not signed in with a subscription. */
    data object SignedOut : Readiness
    /** Cannot run here (not installed, too old, status unknown); [reason] is shown to the person as is. */
    data class Unavailable(val reason: String) : Readiness
}

/** The person-facing reason a turn failed. Raw runtime output never reaches it. */
internal fun failureNotice(runtime: RuntimeId, failure: ChatFailure, zone: ZoneId = ZoneId.systemDefault()): String = when (failure) {
    ChatFailure.ProjectTrustRequired -> "Work did not start. ${runtime.copy.product} needs your approval to trust this project and load its instructions."
    ChatFailure.ProjectTrustTooBroad -> "Work did not start. ${runtime.copy.program} treats your home folder, or a folder above it, " +
        "as this project, and Hovel will not ask you to trust all of that. Choose a project folder that is its own repository."
    ChatFailure.SignedOut -> runtime.copy.signedOut
    is ChatFailure.UsageLimit -> "You've reached your ${runtime.copy.product} usage limit. " +
        (failure.resetsAt?.let { "It resets at ${resetTime(it, zone)}." } ?: "Try again later.")
    ChatFailure.ModelUnavailable -> "${runtime.copy.product} can't use this model. Choose another model and try again."
    ChatFailure.Temporary -> "The reply stopped. Try again when ${runtime.copy.product} is ready."
    ChatFailure.ConversationLost -> "${runtime.copy.product} could not continue this conversation. Send your message again to start fresh."
    ChatFailure.Lockdown -> "${runtime.copy.program} started with tools or plugins that companion chat does not allow, so the reply was stopped."
    ChatFailure.Unexpected -> "${runtime.copy.product} could not finish this reply. Check Providers and try again."
}

/** "3:00 PM" today, otherwise the date too, in the Mac's time zone. */
private fun resetTime(at: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return if (local.toLocalDate() == LocalDate.now(zone)) DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(local)
    else DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).format(local)
}
