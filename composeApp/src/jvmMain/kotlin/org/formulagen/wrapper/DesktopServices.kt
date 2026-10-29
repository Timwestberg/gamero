package org.formulagen.wrapper

import java.awt.EventQueue
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
import org.formulagen.wrapper.bridge.ApprovalMethods
import org.formulagen.wrapper.bridge.ConversationVoiceMethods
import org.formulagen.wrapper.bridge.HostContractMethods
import org.formulagen.wrapper.bridge.ProjectMethods
import org.formulagen.wrapper.bridge.DesktopHostContract
import org.formulagen.wrapper.bridge.HostContractCapability
import org.formulagen.wrapper.bridge.ProjectCapability
import org.formulagen.wrapper.bridge.ConversationVoiceCapability
import org.formulagen.wrapper.conversations.ConversationHost
import org.formulagen.wrapper.conversations.DesktopConversationVoice
import org.formulagen.wrapper.conversations.ConversationStore
import org.formulagen.wrapper.conversations.ProjectFolderStore
import org.formulagen.wrapper.approvals.ComposeDialogPresenter
import org.formulagen.wrapper.approvals.DialogApprovalBroker
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.approvals.StartOfWork
import org.formulagen.wrapper.chat.ChatWork
import org.formulagen.wrapper.chat.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.chat.codex.CodexWorkRuntime
import org.formulagen.wrapper.workspace.DesktopWorkspace
import org.formulagen.wrapper.workspace.NativeFilePicker
import org.formulagen.wrapper.workspace.NativeFolderPicker
import org.formulagen.wrapper.workspace.WorkspaceStore
import org.formulagen.wrapper.chat.ClaudeChatRuntime
import org.formulagen.wrapper.chat.grok.GrokChatRuntime
import org.formulagen.wrapper.chat.grok.GrokWorkRuntime
import org.formulagen.wrapper.chat.grok.NativeGrokWorkTrust
import org.formulagen.wrapper.chat.CodexChatRuntime
import org.formulagen.wrapper.chat.DesktopChat
import org.formulagen.wrapper.chat.DesktopSpeech
import org.formulagen.wrapper.chat.DesktopHearing
import org.formulagen.wrapper.chat.HearingRoute
import org.formulagen.wrapper.chat.SharedHearing
import org.formulagen.wrapper.chat.TalkSettings
import org.formulagen.wrapper.chat.VoicePickerModel
import java.nio.file.Files
import org.formulagen.wrapper.voice.EmberAudio
import org.formulagen.wrapper.voice.chooseDuplex
import org.formulagen.wrapper.voice.hearingBargeMs
import org.formulagen.wrapper.voice.openHearingEngine
import org.formulagen.wrapper.voice.readDuplexRequest
import org.formulagen.wrapper.voice.readFillerSettings
import org.formulagen.wrapper.voice.readSelfCheck
import org.formulagen.wrapper.voice.selfCheckFile
import org.formulagen.wrapper.chat.desktopVoiceConfig
import org.formulagen.wrapper.chat.fillerChoiceFile
import org.formulagen.wrapper.chat.hearingGuardMs
import org.formulagen.wrapper.chat.hearingSilenceMs
import org.formulagen.wrapper.chat.readSavedFillers
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import org.formulagen.wrapper.hovel.appOrigin
import org.formulagen.wrapper.hovel.hovelStateFolder
import org.formulagen.wrapper.pet.DesktopPetLauncher
import org.formulagen.wrapper.progression.embedded.DesktopGameLauncher
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.providers.ClaudeConnection
import org.formulagen.wrapper.providers.ClaudeProvider
import org.formulagen.wrapper.providers.ClaudeRuntime
import org.formulagen.wrapper.providers.CodexProvider
import org.formulagen.wrapper.providers.GrokProvider
import org.formulagen.wrapper.providers.GrokConnection
import org.formulagen.wrapper.providers.grokProviderEnabled
import org.formulagen.wrapper.providers.grokWorkEnabled
import org.formulagen.wrapper.providers.DesktopProviders
import org.formulagen.wrapper.providers.ProviderInstallation
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
    /** Hovel's Mac companion; null for another product or off macOS. */
    val companion: HovelCompanion?,
    /** Companion conversations on this Mac: the runner's builder and `project.*`; null without the companion. */
    val conversations: ConversationHost?,
    /** `host.contract`: the page's companion contract for its context. */
    val hostContract: DesktopHostContract,
    /** The embedded game; null without `HOVEL_GAME_PROJECT` or a window to host it. */
    val games: DesktopGameLauncher?,
    /** Team chat's voice row. */
    val voicePicker: VoicePickerModel,
    /** `conversationVoice.*`: voice in project conversations; null without conversations on this Mac. */
    val conversationVoice: DesktopConversationVoice?,
    /** The voice row of the conversation the voice serves. */
    val conversationPicker: VoicePickerModel,
    private val owned: List<AutoCloseable>,
) : AutoCloseable {
    /** The product capability packs [product] registers with the bridge. */
    fun packs(product: String): List<CapabilityPack> =
        desktopProductPacks(product, companion?.let(::HovelCompanionCapability), chat, providers, pet, games, workspace, brain,
            conversations?.projects, hostContract, conversationVoice)

    /**
     * The page's document context ended: end its chat view, close its game, void a pending approval and let go of
     * the microphone. Conversations keep running: they never belonged to the page.
     */
    fun invalidate() {
        hostContract.invalidate()
        companion?.invalidatePending()
        brain.invalidate()
        chat.invalidate()
        conversationVoice?.pageEnded()
        games?.let { EventQueue.invokeLater(it::close) }
    }

    /** Escape: interrupts or ends a voice conversation, a project conversation's first. False when none is on. */
    fun endVoiceConversation(): Boolean = conversationVoice?.escape() == true || chat.endConversation()

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
                val providerRoot = support.resolve("providers").resolve(HovelCompanion.sha256(appOrigin(appUrl)))
                // Claude is offered only by a development build that asks for it, until Anthropic confirms a
                // companion persona on a player's subscription is allowed; anything else fails closed.
                val claudeOffered = claudeProviderEnabled(environment, release = System.getProperty("gamero.release") == "true",
                    development = System.getProperty("gamero.dev") == "true")
                val grokOffered = grokProviderEnabled(environment, release = System.getProperty("gamero.release") == "true",
                    development = System.getProperty("gamero.dev") == "true")
                val claude = if (claudeOffered) findClaudeExecutable(environment)?.let { ClaudeRuntime(it, providerRoot, environment) } else null
                // One reader of Claude Code's sign-in, for the Providers card and chat readiness alike.
                val claudeSignIn = if (claudeOffered) ClaudeConnection(claude) else null
                val grokConnection = if (grokOffered) GrokConnection(environment) else null
                val runtimes = listOfNotNull(codex?.let { CodexChatRuntime(providerRoot, it) },
                    claude?.let { ClaudeChatRuntime(it, checkNotNull(claudeSignIn)) },
                    grokConnection?.let { GrokChatRuntime(providerRoot, environment, it) })
                val offered = setOfNotNull(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE.takeIf { claudeOffered }, RuntimeId.GROK_BUILD.takeIf { grokOffered })
                // Which offered runtimes may work in a project folder: the page reads it from providers.list, the chat runs it.
                // Codex's working protocol is read from its schema only, so it stays off until verified live.
                val working = setOfNotNull(RuntimeId.CLAUDE_CODE.takeIf { claudeOffered }, RuntimeId.CODEX.takeIf {
                    codexWorkEnabled(environment, release = System.getProperty("gamero.release") == "true", development = System.getProperty("gamero.dev") == "true")
                }, RuntimeId.GROK_BUILD.takeIf {
                    grokWorkEnabled(environment, release = System.getProperty("gamero.release") == "true", development = System.getProperty("gamero.dev") == "true")
                })
                // Working members: only native dialogs answer, in front of this window.
                val dialogs = ComposeDialogPresenter { window }
                val nativeDialogs = NativeDialogs(dialogs)
                val broker = DialogApprovalBroker(nativeDialogs)
                val workspaces = WorkspaceStore(providerRoot, NativeFolderPicker { window })
                val workers = listOfNotNull(claude?.takeIf { RuntimeId.CLAUDE_CODE in working }?.let { ClaudeWorkRuntime(it, checkNotNull(claudeSignIn), broker) },
                    codex?.takeIf { RuntimeId.CODEX in working }?.let { CodexWorkRuntime(providerRoot, it, broker) },
                    grokConnection?.takeIf { RuntimeId.GROK_BUILD in working }?.let { GrokWorkRuntime(providerRoot, environment, it, broker, NativeGrokWorkTrust(nativeDialogs)) })
                val work = ChatWork(workspaces, StartOfWork(nativeDialogs), workers)
                val brainStore = BrainStore(providerRoot)
                val conversationOwnership = Any()
                var brain: DesktopBrain? = null
                val voicePicker = VoicePickerModel()
                val selfCheck = readSelfCheck(runCatching { Files.readString(selfCheckFile(environment)) }.getOrNull())
                val choice = chooseDuplex(readDuplexRequest(environment), EmberAudio.tryLoad(environment), selfCheck)
                val route = HearingRoute(choice, environment)
                // One microphone: team chat and conversations each hold a share, and only one captures at a time.
                val hearingEngine = openHearingEngine(environment["HOVEL_HEARING_MODEL"].orEmpty())
                val hearing = own(SharedHearing(DesktopHearing(hearingEngine, route::openMic)))
                val fillerFile = fillerChoiceFile(environment)
                // One set of voice preferences: a filler choice or a duplex fallback holds in team chat and every conversation.
                val talkSettings = TalkSettings(readFillerSettings(environment, readSavedFillers(fillerFile)), fillerFile, route, choice,
                    hearingSilenceMs(environment), hearingGuardMs(environment), hearingBargeMs(environment))
                val chat = own(DesktopChat(
                    providerRoot, runtimes, DesktopSpeech(desktopVoiceConfig(environment), route::openLine), offered, work, voicePicker,
                    hearing = hearing.user(),
                    settings = talkSettings,
                    product = product,
                    isConverted = brainStore::converted,
                    beforeOpen = { brain?.invalidate() },
                    ownershipGate = conversationOwnership,
                ))
                // Both runtimes sign in outside Hovel, on the player's own install, so a status check never signs a chat out.
                val providers = own(DesktopProviders(listOfNotNull(CodexProvider(providerRoot, codex),
                    claudeSignIn?.let(::ClaudeProvider), grokConnection?.let(::GrokProvider)), working, ProviderInstallation(providerRoot, environment)))
                brain = own(DesktopBrain(providerRoot, brainStore, runtimes, work, providers, nativeDialogs, NativeFilePicker { window },
                    DesktopSpeech(desktopVoiceConfig(environment), route::openLine), chat::invalidate, chat::whileIdle, conversationOwnership))
                val pet = own(DesktopPetLauncher(environment, support.resolve("ember-pet.log")))
                // Conversations get runtime instances of their own, so a page closing team chat never ends their work,
                // and every work approval races the phone through the runner's broker.
                val grokRoot = ConversationHost.grokRoot(providerRoot)
                val retained = { profile: String -> ConversationStore.bindingHandles(providerRoot, profile) }
                val conversationsHere = product == HOVEL_PRODUCT && System.getProperty("os.name").contains("Mac")
                val conversationPicker = VoicePickerModel()
                // Conversation voice has its own speaker until step E removes team chat's; each conversation gets its own
                // share of the microphone.
                val conversationVoice = if (conversationsHere) own(DesktopConversationVoice(
                    DesktopSpeech(desktopVoiceConfig(environment), route::openLine), hearing, talkSettings, conversationPicker, product,
                )) else null
                val conversations = if (conversationsHere) own(ConversationHost(
                    providerRoot, ProjectFolderStore(providerRoot, NativeFolderPicker { window }), providers, nativeDialogs, offered, working,
                    talk = {
                        listOfNotNull(codex?.let { CodexChatRuntime(providerRoot, it) }, claude?.let { ClaudeChatRuntime(it, checkNotNull(claudeSignIn)) },
                            grokConnection?.let { GrokChatRuntime(grokRoot, environment, it, retained) })
                    },
                    work = { approvals ->
                        listOfNotNull(claude?.takeIf { RuntimeId.CLAUDE_CODE in working }?.let { ClaudeWorkRuntime(it, checkNotNull(claudeSignIn), approvals) },
                            codex?.takeIf { RuntimeId.CODEX in working }?.let { CodexWorkRuntime(providerRoot, it, approvals) },
                            grokConnection?.takeIf { RuntimeId.GROK_BUILD in working }?.let {
                                GrokWorkRuntime(grokRoot, environment, it, approvals, NativeGrokWorkTrust(nativeDialogs), retained = retained)
                            })
                    },
                    voice = conversationVoice,
                )) else null
                // Hovel tasks keep their read-only Codex workspace until step E removes them with the web task UI.
                val taskWorkspace = environment["GAMERO_WORKSPACE"]?.let(Path::of)
                val companion = conversations?.let { host ->
                    own(HovelCompanion(appUrl, hovelStateFolder(support, appUrl), codex, taskWorkspace,
                        conversations = host::start))
                }
                val games = if (window != null && environment["HOVEL_GAME_PROJECT"] != null) {
                    DesktopGameLauncher(window, environment, support).also { game -> owned += AutoCloseable { onAwtThread(game::close) } }
                } else null
                return DesktopServices(chat, brain, providers, pet, DesktopWorkspace(workspaces), dialogs, companion, conversations,
                    DesktopHostContract(), games, voicePicker, conversationVoice, conversationPicker, owned.toList())
            } catch (error: Throwable) {
                runCatching { closeInReverse(owned) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}

/**
 * The product packs a desktop host registers. Every desktop product has chat, providers, pet,
 * games and project folders; Hovel adds its companion and the companion projects capabilities:
 * `project` (a project's folders on this Mac), the `host.contract` handshake, `conversationVoice`
 * (voice in conversations, on a Mac with them) and phone `approvals`, which desktop never supports.
 * A missing implementation registers as `Unsupported`, so its methods answer `unsupported` instead
 * of `unknown-method`.
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
    projects: ProjectCapability? = null,
    hostContract: HostContractCapability? = null,
    conversationVoice: ConversationVoiceCapability? = null,
): List<CapabilityPack> = buildList {
    if (product == HOVEL_PRODUCT) {
        add(CompanionMethods.pack(supportedIfPresent(companion)))
        add(ProjectMethods.pack(supportedIfPresent(projects)))
        add(ConversationVoiceMethods.pack(supportedIfPresent(conversationVoice)))
        add(ApprovalMethods.pack(Capability.Unsupported))
        add(HostContractMethods.pack(supportedIfPresent(hostContract)))
    }
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
