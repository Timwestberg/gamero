package org.formulagen.wrapper

import java.awt.EventQueue
import java.net.URI
import java.nio.file.Path
import javax.swing.JFrame
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.brain.BrainStore
import org.formulagen.wrapper.brain.DesktopBrain
import org.formulagen.wrapper.bridge.BrainCapability
import org.formulagen.wrapper.bridge.BrainMethods
import org.formulagen.wrapper.bridge.ChatCapability
import org.formulagen.wrapper.bridge.ChatMethods
import org.formulagen.wrapper.bridge.CompanionCapability
import org.formulagen.wrapper.bridge.CompanionMethods
import org.formulagen.wrapper.bridge.GameCapability
import org.formulagen.wrapper.bridge.GameMethods
import org.formulagen.wrapper.bridge.PetCapability
import org.formulagen.wrapper.bridge.PetMethods
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderMethods
import org.formulagen.wrapper.bridge.WorkspaceCapability
import org.formulagen.wrapper.bridge.WorkspaceMethods
import org.formulagen.wrapper.approvals.ComposeDialogPresenter
import org.formulagen.wrapper.approvals.DialogApprovalBroker
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.chat.ChatWork
import org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.chat.codex.CodexWorkRuntime
import org.formulagen.wrapper.workspace.DesktopWorkspace
import org.formulagen.wrapper.workspace.NativeFolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore
import org.formulagen.wrapper.chat.ClaudeChatRuntime
import org.formulagen.wrapper.chat.CodexChatRuntime
import org.formulagen.wrapper.chat.DesktopChat
import org.formulagen.wrapper.chat.DesktopSpeech
import org.formulagen.wrapper.chat.desktopVoiceConfig
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import org.formulagen.wrapper.pet.DesktopPetLauncher
import org.formulagen.wrapper.progression.embedded.DesktopGameLauncher
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeProvider
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.CodexProvider
import org.formulagen.wrapper.providers.DesktopProviders
import org.formulagen.wrapper.providers.claudeProviderEnabled
import org.formulagen.wrapper.providers.codexWorkEnabled
import org.formulagen.wrapper.providers.findClaudeExecutable
import org.formulagen.wrapper.providers.findCodexExecutable

/**
 * The desktop host's native services for one app window: built once by [create], exposed to
 * host-bridge v2 by [packs], released per page by [invalidate], and closed in reverse creation
 * order by [close]. The browser composable only wires these to the WebView.
 */
internal class DesktopServices private constructor(
    val chat: DesktopChat,
    val brain: DesktopBrain,
    val providers: DesktopProviders,
    val pet: DesktopPetLauncher,
    /** Each companion's project folder, chosen with the Mac's folder picker. */
    val workspace: DesktopWorkspace,
    /** The native approval and start-of-work dialogs; the window renders them with `NativeDialogHost`. */
    val dialogs: ComposeDialogPresenter,
    /** Hovel's Mac companion; null for another product or without Codex and `GAMERO_WORKSPACE`. */
    val companion: HovelCompanion?,
    /** The embedded game; null without `HOVEL_GAME_PROJECT` or a window to host it. */
    val games: DesktopGameLauncher?,
    private val owned: List<AutoCloseable>,
) : AutoCloseable {
    /** The product capability packs [product] registers with the bridge. */
    fun packs(product: String): List<CapabilityPack> =
        desktopProductPacks(product, companion?.let(::HovelCompanionCapability), chat, providers, pet, games, workspace, brain)

    /** The page's document context ended: end its chat view, close its game and void a pending approval. */
    fun invalidate() {
        companion?.invalidatePending()
        brain.invalidate()
        chat.invalidate()
        games?.let { EventQueue.invokeLater(it::close) }
    }

    /** Close an open adventure before its window goes away; the game's layer belongs to that window. */
    fun closeGame() {
        games?.let { onAwtThread(it::close) }
    }

    /** Reverse creation order. Every service is closed even when one fails; the failures are reported together. */
    override fun close() = closeInReverse(owned)

    companion object {
        /**
         * Build every service for [appUrl]. [support] is the app's support folder; [window] hosts the
         * embedded game, whose return reaches the page as the `games.closed` bridge event.
         */
        fun create(
            appUrl: String,
            support: Path,
            environment: Map<String, String>,
            product: String,
            window: JFrame?,
        ): DesktopServices {
            val owned = mutableListOf<AutoCloseable>()
            fun <T : AutoCloseable> own(service: T): T = service.also { owned += it }
            try {
                val codex = findCodexExecutable(environment)
                val origin = URI(appUrl).let { URI(it.scheme, null, it.host, it.port, null, null, null).toASCIIString() }
                val providerRoot = support.resolve("providers").resolve(HovelCompanion.sha256(origin))
                // Claude is offered only by a development build that asks for it, until Anthropic confirms a
                // companion persona on a player's subscription is allowed; anything else fails closed.
                val claudeOffered = claudeProviderEnabled(environment, release = System.getProperty("gamero.release") == "true",
                    development = System.getProperty("gamero.dev") == "true")
                val claude = if (claudeOffered) findClaudeExecutable(environment)?.let { ClaudeRuntime(it, providerRoot, environment) } else null
                // One reader of Claude Code's sign-in, for the Providers card and chat readiness alike.
                val claudeSignIn = if (claudeOffered) ClaudeConnection(claude) else null
                val runtimes = listOfNotNull(codex?.let { CodexChatRuntime(providerRoot, it) },
                    claude?.let { ClaudeChatRuntime(it, checkNotNull(claudeSignIn)) })
                val offered = setOfNotNull(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE.takeIf { claudeOffered })
                // Which offered runtimes may work in a project folder: the page reads it from providers.list, the chat runs it.
                // Codex's working protocol is read from its schema only, so it stays off until verified live.
                val working = setOfNotNull(RuntimeId.CLAUDE_CODE.takeIf { claudeOffered }, RuntimeId.CODEX.takeIf {
                    codexWorkEnabled(environment, release = System.getProperty("gamero.release") == "true", development = System.getProperty("gamero.dev") == "true")
                })
                // Working members: only native dialogs answer, in front of this window.
                val dialogs = ComposeDialogPresenter { window }
                val nativeDialogs = NativeDialogs(dialogs)
                val broker = DialogApprovalBroker(nativeDialogs)
                val workspaces = WorkspaceStore(providerRoot, NativeFolderPicker { window })
                val workers = listOfNotNull(claude?.takeIf { RuntimeId.CLAUDE_CODE in working }?.let { ClaudeWorkRuntime(it, checkNotNull(claudeSignIn), broker) },
                    codex?.takeIf { RuntimeId.CODEX in working }?.let { CodexWorkRuntime(providerRoot, it, broker) })
                val work = ChatWork(workspaces, StartOfWork(nativeDialogs), workers)
                val brainStore = BrainStore(providerRoot)
                val conversationOwnership = Any()
                var brain: DesktopBrain? = null
                val chat = own(DesktopChat(providerRoot, runtimes, DesktopSpeech(desktopVoiceConfig(environment)), offered, work,
                    isConverted = brainStore::converted, beforeOpen = { brain?.invalidate() }, ownershipGate = conversationOwnership))
                // Both runtimes sign in outside Hovel, on the player's own install, so a status check never signs a chat out.
                val providers = own(DesktopProviders(listOfNotNull(CodexProvider(providerRoot, codex),
                    claudeSignIn?.let(::ClaudeProvider)), working))
                brain = own(DesktopBrain(providerRoot, brainStore, runtimes, work, providers, nativeDialogs,
                    DesktopSpeech(desktopVoiceConfig(environment)), chat::invalidate, chat::whileIdle, conversationOwnership))
                val pet = own(DesktopPetLauncher(environment, support.resolve("ember-pet.log")))
                val workspace = environment["GAMERO_WORKSPACE"]?.let(Path::of)
                val companion = if (product == HOVEL_PRODUCT && System.getProperty("os.name").contains("Mac") &&
                    codex != null && workspace != null) {
                    own(HovelCompanion(appUrl, support.resolve("hovel-${HovelCompanion.sha256(appUrl)}"), codex, workspace))
                } else null
                val games = if (window != null && environment["HOVEL_GAME_PROJECT"] != null) {
                    DesktopGameLauncher(window, environment, support).also { game -> owned += AutoCloseable { onAwtThread(game::close) } }
                } else null
                return DesktopServices(chat, brain, providers, pet, DesktopWorkspace(workspaces), dialogs, companion, games, owned.toList())
            } catch (error: Throwable) {
                runCatching { closeInReverse(owned) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}

/**
 * The product packs a desktop host registers. Every desktop product has chat, providers, pet,
 * games and project folders; Hovel adds its companion. A missing implementation registers as
 * `Unsupported`, so its methods answer `unsupported` instead of `unknown-method`.
 */
internal fun desktopProductPacks(
    product: String,
    companion: CompanionCapability?,
    chat: ChatCapability?,
    providers: ProviderCapability?,
    pet: PetCapability?,
    games: GameCapability?,
    workspace: WorkspaceCapability?,
    brain: BrainCapability? = null,
): List<CapabilityPack> = buildList {
    if (product == HOVEL_PRODUCT) add(CompanionMethods.pack(supportedIfPresent(companion)))
    add(BrainMethods.pack(supportedIfPresent(brain)))
    add(ChatMethods.pack(supportedIfPresent(chat)))
    add(ProviderMethods.pack(supportedIfPresent(providers)))
    add(PetMethods.pack(supportedIfPresent(pet)))
    add(GameMethods.pack(supportedIfPresent(games)))
    add(WorkspaceMethods.pack(supportedIfPresent(workspace)))
}

private fun <T : Any> supportedIfPresent(impl: T?): Capability<T> = impl?.let { Capability.Supported(it) } ?: Capability.Unsupported

/** Close each resource, last first. The first failure is thrown with the later ones suppressed. */
internal fun closeInReverse(resources: List<AutoCloseable>) {
    var failure: Throwable? = null
    for (resource in resources.asReversed()) {
        try { resource.close() } catch (error: Throwable) {
            failure?.addSuppressed(error) ?: run { failure = error }
        }
    }
    failure?.let { throw it }
}

private fun onAwtThread(action: () -> Unit) {
    if (EventQueue.isDispatchThread()) action() else EventQueue.invokeAndWait(action)
}
