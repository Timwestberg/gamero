package org.formulagen.wrapper

import java.awt.EventQueue
import java.nio.file.Path
import javax.swing.JFrame
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
import org.formulagen.wrapper.bridge.CompanionCapability
import org.formulagen.wrapper.bridge.CompanionMethods
import org.formulagen.wrapper.bridge.GameCapability
import org.formulagen.wrapper.bridge.GameMethods
import org.formulagen.wrapper.bridge.PetCapability
import org.formulagen.wrapper.bridge.PetMethods
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderMethods
import org.formulagen.wrapper.bridge.guardedBy
import org.formulagen.wrapper.bridge.ApprovalDeviceMethods
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
import org.formulagen.wrapper.conversations.ProjectFolderStore
import org.formulagen.wrapper.conversations.NativeFolderPicker
import org.formulagen.wrapper.approvals.ComposeDialogPresenter
import org.formulagen.wrapper.approvals.NativeDialogs
import org.formulagen.wrapper.runtimes.claude.ClaudeWorkRuntime
import org.formulagen.wrapper.runtimes.codex.CodexWorkRuntime
import org.formulagen.wrapper.runtimes.ClaudeChatRuntime
import org.formulagen.wrapper.runtimes.grok.GrokChatRuntime
import org.formulagen.wrapper.runtimes.grok.GrokWorkRuntime
import org.formulagen.wrapper.runtimes.grok.NativeGrokWorkTrust
import org.formulagen.wrapper.runtimes.CodexChatRuntime
import org.formulagen.wrapper.conversation.voice.DesktopSpeech
import org.formulagen.wrapper.conversation.voice.DesktopHearing
import org.formulagen.wrapper.conversation.voice.HearingRoute
import org.formulagen.wrapper.conversation.voice.SharedHearing
import org.formulagen.wrapper.conversation.voice.TalkSettings
import org.formulagen.wrapper.conversation.voice.VoicePickerModel
import java.nio.file.Files
import org.formulagen.wrapper.voice.EmberAudio
import org.formulagen.wrapper.voice.chooseDuplex
import org.formulagen.wrapper.voice.hearingBargeMs
import org.formulagen.wrapper.voice.openHearingEngine
import org.formulagen.wrapper.voice.readDuplexRequest
import org.formulagen.wrapper.voice.readFillerSettings
import org.formulagen.wrapper.voice.readSelfCheck
import org.formulagen.wrapper.voice.selfCheckFile
import org.formulagen.wrapper.conversation.voice.desktopVoiceConfig
import org.formulagen.wrapper.conversation.voice.fillerChoiceFile
import org.formulagen.wrapper.conversation.voice.hearingGuardMs
import org.formulagen.wrapper.conversation.voice.hearingSilenceMs
import org.formulagen.wrapper.conversation.voice.readSavedFillers
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import org.formulagen.wrapper.hovel.appOrigin
import org.formulagen.wrapper.hovel.hovelStateFolder
import org.formulagen.wrapper.pet.DesktopPetLauncher
import org.formulagen.wrapper.progression.embedded.DesktopGameLauncher
import org.formulagen.wrapper.logger.LogLevel
import org.formulagen.wrapper.logger.log
import org.formulagen.wrapper.progression.GAMES_ROOT_VARIABLE
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
    val providers: DesktopProviders,
    val pet: DesktopPetLauncher,
    /** The native approval and start-of-work dialogs; the window renders them with `NativeDialogHost`. */
    val dialogs: ComposeDialogPresenter,
    /** Hovel's Mac companion; null for another product or off macOS. */
    val companion: HovelCompanion?,
    /** Companion conversations on this Mac: the runner's builder and `project.*`; null without the companion. */
    val conversations: ConversationHost?,
    /** `host.contract`: the page's companion contract for its context. */
    val hostContract: DesktopHostContract,
    /** The embedded games; null without `HOVEL_GAMES_ROOT` or a window to host them. */
    val games: DesktopGameLauncher?,
    /** `conversationVoice.*`: voice in project conversations; null without conversations on this Mac. */
    val conversationVoice: DesktopConversationVoice?,
    /** The voice row of the conversation the voice serves. */
    val conversationPicker: VoicePickerModel,
    private val owned: List<AutoCloseable>,
) : AutoCloseable {
    /** The product capability packs [product] registers with the bridge. */
    fun packs(product: String): List<CapabilityPack> =
        desktopProductPacks(product, companion?.let(::HovelCompanionCapability), providers, pet, games, conversations?.projects,
            hostContract, conversationVoice, pageCheck = hostContract::requireCurrentPage)

    /**
     * The page's document context ended: forget its contract, close its game, void a pending approval and let go of
     * the microphone. Conversations keep running: they never belonged to the page.
     */
    fun invalidate() {
        hostContract.invalidate()
        companion?.invalidatePending()
        conversationVoice?.pageEnded()
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
                val providerRoot = support.resolve("providers").resolve(HovelCompanion.sha256(appOrigin(appUrl)))
                // Claude is offered only by a development build that asks for it, until Anthropic confirms a
                // companion persona on a player's subscription is allowed; anything else fails closed.
                val claudeOffered = claudeProviderEnabled(environment, release = System.getProperty("gamero.release") == "true",
                    development = System.getProperty("gamero.dev") == "true")
                val grokOffered = grokProviderEnabled(environment, release = System.getProperty("gamero.release") == "true",
                    development = System.getProperty("gamero.dev") == "true")
                val claude = if (claudeOffered) findClaudeExecutable(environment)?.let { ClaudeRuntime(it, providerRoot, environment) } else null
                // One reader of Claude Code's sign-in, for the Providers card and turn readiness alike.
                val claudeSignIn = if (claudeOffered) ClaudeConnection(claude) else null
                val grokConnection = if (grokOffered) GrokConnection(environment) else null
                val offered = setOfNotNull(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE.takeIf { claudeOffered }, RuntimeId.GROK_BUILD.takeIf { grokOffered })
                // Which offered runtimes may work in a project folder: the page reads it from providers.list, conversations run it.
                // Codex's working protocol is read from its schema only, so it stays off until verified live.
                val working = setOfNotNull(RuntimeId.CLAUDE_CODE.takeIf { claudeOffered }, RuntimeId.CODEX.takeIf {
                    codexWorkEnabled(environment, release = System.getProperty("gamero.release") == "true", development = System.getProperty("gamero.dev") == "true")
                }, RuntimeId.GROK_BUILD.takeIf {
                    grokWorkEnabled(environment, release = System.getProperty("gamero.release") == "true", development = System.getProperty("gamero.dev") == "true")
                })
                // Working roles: only native dialogs answer, in front of this window.
                val dialogs = ComposeDialogPresenter { window }
                val nativeDialogs = NativeDialogs(dialogs)
                val selfCheck = readSelfCheck(runCatching { Files.readString(selfCheckFile(environment)) }.getOrNull())
                val choice = chooseDuplex(readDuplexRequest(environment), EmberAudio.tryLoad(environment), selfCheck)
                val route = HearingRoute(choice, environment)
                // One microphone: each conversation's voice holds a share, and only one captures at a time.
                val hearingEngine = openHearingEngine(environment["HOVEL_HEARING_MODEL"].orEmpty())
                val hearing = own(SharedHearing(DesktopHearing(hearingEngine, route::openMic)))
                val fillerFile = fillerChoiceFile(environment)
                // One set of voice preferences: a filler choice or a duplex fallback holds in every conversation.
                val talkSettings = TalkSettings(readFillerSettings(environment, readSavedFillers(fillerFile)), fillerFile, route, choice,
                    hearingSilenceMs(environment), hearingGuardMs(environment), hearingBargeMs(environment))
                val hostContract = DesktopHostContract()
                // Every runtime signs in outside Hovel, on the player's own install, so a status check never signs anything out.
                val providers = own(DesktopProviders(listOfNotNull(CodexProvider(providerRoot, codex),
                    claudeSignIn?.let(::ClaudeProvider), grokConnection?.let(::GrokProvider)), working, ProviderInstallation(providerRoot, environment),
                    pageContract = { hostContract.pageContract }))
                val pet = own(DesktopPetLauncher(environment, support.resolve("ember-pet.log")))
                // Conversations get their runtime instances from the runner, and every work approval races the phone
                // through the runner's broker.
                val grokRoot = ConversationHost.grokRoot(providerRoot)
                val conversationsHere = product == HOVEL_PRODUCT && System.getProperty("os.name").contains("Mac")
                val conversationPicker = VoicePickerModel()
                val conversationVoice = if (conversationsHere) own(DesktopConversationVoice(
                    DesktopSpeech(desktopVoiceConfig(environment), route::openLine), hearing, talkSettings, conversationPicker,
                )) else null
                val conversations = if (conversationsHere) own(ConversationHost(
                    providerRoot, ProjectFolderStore(providerRoot, NativeFolderPicker { window }), providers, nativeDialogs, offered, working,
                    talk = { retained ->
                        listOfNotNull(codex?.let { CodexChatRuntime(providerRoot, it) }, claude?.let { ClaudeChatRuntime(it, checkNotNull(claudeSignIn)) },
                            grokConnection?.let { GrokChatRuntime(grokRoot, environment, it, retained) })
                    },
                    work = { approvals, retained ->
                        listOfNotNull(claude?.takeIf { RuntimeId.CLAUDE_CODE in working }?.let { ClaudeWorkRuntime(it, checkNotNull(claudeSignIn), approvals) },
                            codex?.takeIf { RuntimeId.CODEX in working }?.let { CodexWorkRuntime(providerRoot, it, approvals) },
                            grokConnection?.takeIf { RuntimeId.GROK_BUILD in working }?.let {
                                GrokWorkRuntime(grokRoot, environment, it, approvals, retained, NativeGrokWorkTrust(nativeDialogs))
                            })
                    },
                    voice = conversationVoice,
                )) else null
                // Codex, when installed, runs this Mac's memory jobs; registration needs no provider.
                val companion = conversations?.let { host ->
                    own(HovelCompanion(appUrl, hovelStateFolder(support, appUrl), codex, conversations = host::start))
                }
                if (environment["HOVEL_GAME_PROJECT"] != null && environment[GAMES_ROOT_VARIABLE] == null) {
                    log(LogLevel.Warn, "games", "HOVEL_GAME_PROJECT is replaced by HOVEL_GAMES_ROOT; games stay off until it is set")
                }
                val games = if (window != null && environment[GAMES_ROOT_VARIABLE] != null) {
                    DesktopGameLauncher(window, environment, support).also { game -> owned += AutoCloseable { onAwtThread(game::close) } }
                } else null
                return DesktopServices(providers, pet, dialogs, companion, conversations, hostContract, games, conversationVoice,
                    conversationPicker, owned.toList())
            } catch (error: Throwable) {
                runCatching { closeInReverse(owned) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}

/**
 * The product packs a desktop host registers. Every desktop product has providers, pet and games; Hovel adds its
 * companion and the companion projects capabilities: `project` (a project's folders on this Mac), the `host.contract`
 * handshake, `conversationVoice` (voice in conversations, on a Mac with them) and phone `approvals`, which desktop never
 * supports. A missing implementation registers as `Unsupported`, so its methods answer `unsupported` instead of
 * `unknown-method`. [pageCheck] runs before every `companion.*`, `project.*` and `providers.*` call, the methods whose
 * answers changed in contract 3 ([DesktopHostContract.requireCurrentPage]).
 */
internal fun desktopProductPacks(
    product: String,
    companion: CompanionCapability?,
    providers: ProviderCapability?,
    pet: PetCapability?,
    games: GameCapability?,
    projects: ProjectCapability? = null,
    hostContract: HostContractCapability? = null,
    conversationVoice: ConversationVoiceCapability? = null,
    pageCheck: () -> Unit = {},
): List<CapabilityPack> = buildList {
    if (product == HOVEL_PRODUCT) {
        add(CompanionMethods.pack(supportedIfPresent(companion)).guardedBy(pageCheck))
        add(ProjectMethods.pack(supportedIfPresent(projects)).guardedBy(pageCheck))
        add(ConversationVoiceMethods.pack(supportedIfPresent(conversationVoice)))
        add(ApprovalMethods.pack(Capability.Unsupported))
        add(ApprovalDeviceMethods.pack(Capability.Unsupported))
        add(HostContractMethods.pack(supportedIfPresent(hostContract)))
    }
    add(ProviderMethods.pack(supportedIfPresent(providers)).guardedBy(pageCheck))
    add(PetMethods.pack(supportedIfPresent(pet)))
    add(GameMethods.pack(supportedIfPresent(games)))
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
