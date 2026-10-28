package org.formulagen.wrapper

import java.awt.EventQueue
import java.net.URI
import java.nio.file.Path
import javax.swing.JFrame
import org.formulagen.wrapper.bridge.Capability
import org.formulagen.wrapper.bridge.CapabilityPack
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
import org.formulagen.wrapper.chat.CodexChatRuntime
import org.formulagen.wrapper.chat.DesktopChat
import org.formulagen.wrapper.chat.DesktopSpeech
import org.formulagen.wrapper.chat.desktopVoiceConfig
import org.formulagen.wrapper.hovel.HOVEL_PRODUCT
import org.formulagen.wrapper.hovel.HovelCompanion
import org.formulagen.wrapper.hovel.HovelCompanionCapability
import org.formulagen.wrapper.pet.DesktopPetLauncher
import org.formulagen.wrapper.progression.embedded.DesktopGameLauncher
import org.formulagen.wrapper.providers.DesktopProviders
import org.formulagen.wrapper.providers.codexProfileHome
import org.formulagen.wrapper.providers.findCodexExecutable

/**
 * The desktop host's native services for one app window: built once by [create], exposed to
 * host-bridge v2 by [packs], released per page by [invalidate], and closed in reverse creation
 * order by [close]. The browser composable only wires these to the WebView.
 */
internal class DesktopServices private constructor(
    val chat: DesktopChat,
    val providers: DesktopProviders,
    val pet: DesktopPetLauncher,
    /** Hovel's Mac companion; null for another product or without Codex and `GAMERO_WORKSPACE`. */
    val companion: HovelCompanion?,
    /** The embedded game; null without `HOVEL_GAME_PROJECT` or a window to host it. */
    val games: DesktopGameLauncher?,
    private val owned: List<AutoCloseable>,
) : AutoCloseable {
    /** The product capability packs [product] registers with the bridge. */
    fun packs(product: String): List<CapabilityPack> =
        desktopProductPacks(product, companion?.let(::HovelCompanionCapability), chat, providers, pet, games)

    /** The page's document context ended: end its chat view, close its game and void a pending approval. */
    fun invalidate() {
        companion?.invalidatePending()
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
                val chat = own(DesktopChat(providerRoot, listOfNotNull(codex?.let { CodexChatRuntime(providerRoot, it) }),
                    DesktopSpeech(desktopVoiceConfig(environment))))
                val providers = own(DesktopProviders(providerRoot, codex, onDisconnect = chat::invalidate))
                val pet = own(DesktopPetLauncher(environment, support.resolve("ember-pet.log")))
                val workspace = environment["GAMERO_WORKSPACE"]?.let(Path::of)
                val companion = if (product == HOVEL_PRODUCT && System.getProperty("os.name").contains("Mac") &&
                    codex != null && workspace != null) {
                    own(HovelCompanion(appUrl, support.resolve("hovel-${HovelCompanion.sha256(appUrl)}"), codex, workspace,
                        codexHomeForOwner = { codexProfileHome(providerRoot, it) }))
                } else null
                val games = if (window != null && environment["HOVEL_GAME_PROJECT"] != null) {
                    DesktopGameLauncher(window, environment, support).also { game -> owned += AutoCloseable { onAwtThread(game::close) } }
                } else null
                return DesktopServices(chat, providers, pet, companion, games, owned.toList())
            } catch (error: Throwable) {
                runCatching { closeInReverse(owned) }.exceptionOrNull()?.let(error::addSuppressed)
                throw error
            }
        }
    }
}

/**
 * The product packs a desktop host registers. Every desktop product has chat, providers, pet and
 * games; Hovel adds its companion. A missing implementation registers as `Unsupported`, so its
 * methods answer `unsupported` instead of `unknown-method`.
 */
internal fun desktopProductPacks(
    product: String,
    companion: CompanionCapability?,
    chat: ChatCapability?,
    providers: ProviderCapability?,
    pet: PetCapability?,
    games: GameCapability?,
): List<CapabilityPack> = buildList {
    if (product == HOVEL_PRODUCT) add(CompanionMethods.pack(supportedIfPresent(companion)))
    add(ChatMethods.pack(supportedIfPresent(chat)))
    add(ProviderMethods.pack(supportedIfPresent(providers)))
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
