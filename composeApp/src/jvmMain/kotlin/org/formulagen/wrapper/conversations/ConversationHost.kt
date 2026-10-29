package org.formulagen.wrapper.conversations

import java.nio.file.Path
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

/**
 * Builds this Mac's conversation side for a registered account ([start]) and owns what outlives it:
 * the project folders and the `project.*` capability ([projects]), which needs the account the runner
 * serves. The runner gets runtime instances of its own ([talk], [work]), so team chat ending a page's
 * work never ends a conversation's, and every work approval goes through [RemoteApprovals].
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
) : AutoCloseable {
    @Volatile private var running: Running? = null
    private val publishing = Executors.newSingleThreadExecutor(loggingDaemonThreads("hovel-project-folders"))

    /** The account the runner serves, and how its folders reach the account. */
    private class Running(val accountId: String, val publisher: ProjectFolderPublisher, val readableBy: () -> List<RuntimeId>)

    /** `project.*` for the page: answers `failed` until this Mac is connected to an account. */
    val projects = DesktopProjects(folders, { running?.accountId }, { running?.readableBy?.invoke().orEmpty() }) { accountId, projectId ->
        running?.takeIf { it.accountId == accountId }?.let { current -> runCatching { publishing.execute { current.publisher.publish(projectId) } } }
    }

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
            val runner = ConversationRunner(api, store, engine, approvals, folders, publisher, directory::heartbeat, connection.heartbeat, connection.status)
            owned += runner
            running = Running(registration.ownerId, publisher, engine::readableBy)
            runner.start()
            return AutoCloseable {
                running = null
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
        /** Where the runner's Grok runtimes keep their bindings: apart from team chat's, so neither prunes the other's. */
        fun grokRoot(providerRoot: Path): Path = providerRoot.resolve("conversation-runtimes")
    }
}
