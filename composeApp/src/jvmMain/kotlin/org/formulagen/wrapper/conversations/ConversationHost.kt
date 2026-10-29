package org.formulagen.wrapper.conversations

import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import org.formulagen.wrapper.approvals.ApprovalBroker
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.chat.ChatRuntime
import org.formulagen.wrapper.chat.WorkRuntime
import org.formulagen.wrapper.chat.loggingDaemonThreads
import org.formulagen.wrapper.closeInReverse
import org.formulagen.wrapper.hovel.HostConnection
import org.formulagen.wrapper.hovel.HovelStatusException

/**
 * What this Mac's voice needs from the running account: where heard turns go ([connected], null once the
 * runner stops), and who hears each claimed message's replies ([forClaim], as [ReplyListeners]).
 */
internal interface ConversationVoiceLink : ReplyListeners {
    fun connected(turns: HeardTurns?)
}

/**
 * Builds this Mac's conversation side for a registered account ([start]) and owns what outlives it:
 * the project folders and the `project.*` capability ([projects]), which needs the account the runner
 * serves. The runner gets runtime instances of its own ([talk], [work]), so team chat ending a page's
 * work never ends a conversation's, and every work approval goes through [RemoteApprovals]. [voice], when
 * this Mac has conversation voice, is linked to each runner it starts.
 */
internal class ConversationHost(
    private val providerRoot: Path,
    val folders: ProjectFolderStore,
    private val providers: ProviderCapability,
    private val dialogs: NativeDialogs,
    /** The runtimes this build offers, and those of them that may work here. */
    private val offered: Set<RuntimeId>,
    private val working: Set<RuntimeId>,
    private val talk: () -> List<ChatRuntime>,
    private val work: (ApprovalBroker) -> List<WorkRuntime>,
    private val voice: ConversationVoiceLink? = null,
) : AutoCloseable {
    @Volatile private var running: Running? = null
    private val publishing = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-project-folders"))

    /** The account the runner serves, how its folders reach the account, and whether a project is this Mac's. */
    private class Running(
        val accountId: String,
        val publisher: ProjectFolderPublisher,
        val readableBy: () -> List<RuntimeId>,
        val owns: (projectId: String) -> Boolean?,
    )

    /** `project.*` for the page: answers `failed` until this Mac is connected to an account, and for another Mac's project. */
    val projects = DesktopProjects(folders, { running?.accountId }, { running?.readableBy?.invoke().orEmpty() },
        changed = { accountId, projectId ->
            running?.takeIf { it.accountId == accountId }?.let { current ->
                runCatching { publishing.execute { current.publisher.publish(projectId) } }
            }
        },
        owns = { accountId, projectId -> running?.takeIf { it.accountId == accountId }?.owns?.invoke(projectId) },
    )

    /** Start the runner for [connection]'s account. Closing the answer stops it and its runtimes. */
    fun start(connection: HostConnection): AutoCloseable {
        val registration = connection.registration
        val api = HttpConversationApi(connection.transport, connection.transport::openStream)
        val store = ConversationStore(providerRoot, registration.ownerId).open()
        val owned = mutableListOf<AutoCloseable>(store)
        try {
            val approvals = RemoteApprovals(dialogs, api, registration.id)
            val talkRuntimes = talk().also { owned += it }
            val workRuntimes = work(approvals).also { owned += it }
            val turns = ProviderTurns(talkRuntimes, workRuntimes, providers, approvals)
            val engine = CoordinatorEngine(store, folders, turns, approvals, StartOfWork(dialogs), api)
            val publisher = ProjectFolderPublisher(folders, api, registration.ownerId, registration.id, engine::readableBy)
            val directory = HostDirectory(providers, registration.ownerId, registration.id, offered,
                working.intersect(workRuntimes.map { it.id }.toSet()), connection.features, connection.providerState)
            val runner = ConversationRunner(api, store, engine, approvals, folders, publisher, directory::heartbeat, connection.heartbeat,
                connection.status, replies = voice ?: ReplyListeners.NONE)
            owned += runner
            // A project the account confirmed answers at once and is checked again in the background, so a slow
            // server never hides it.
            val confirmed = ConcurrentHashMap.newKeySet<String>()
            val verify = { projectId: String ->
                projectOnThisMac(api, folders, registration.ownerId, registration.id, projectId)
                    .also { if (it == true) confirmed += projectId else if (it == false) confirmed -= projectId }
            }
            running = Running(registration.ownerId, publisher, engine::readableBy) { projectId ->
                if (projectId in confirmed) true.also { runCatching { publishing.execute { verify(projectId) } } } else verify(projectId)
            }
            runner.start()
            voice?.connected(heardTurns(api, runner))
            return AutoCloseable {
                running = null
                voice?.connected(null)
                closeInReverse(owned)
            }
        } catch (error: Throwable) {
            running = null
            runCatching { closeInReverse(owned) }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    override fun close() { publishing.shutdownNow() }

    companion object {
        /**
         * Whether [projectId] runs on computer [hostId], as the account says (`GET projects/:id`, which answers only this
         * computer's projects), recorded in [folders] as `ProjectFolderPublisher.read` records it: this Mac's project
         * (so its folders can be chosen again), or another computer's. Null when the account cannot be reached.
         */
        internal fun projectOnThisMac(api: ConversationApi, folders: ProjectFolderStore, accountId: String, hostId: String,
            projectId: String): Boolean? = try {
            val project = api.project(projectId)
            if (project.computerId == hostId) { folders.claimed(accountId, project); true }
            else { folders.foreign(accountId, projectId); false }
        } catch (refusal: HovelStatusException) {
            if (!refusal.final) null else { folders.foreign(accountId, projectId); false }
        } catch (_: Exception) {
            null
        }

        /** Where the runner's Grok runtimes keep their bindings: apart from team chat's, so neither prunes the other's. */
        fun grokRoot(providerRoot: Path): Path = providerRoot.resolve("conversation-runtimes")
    }
}
