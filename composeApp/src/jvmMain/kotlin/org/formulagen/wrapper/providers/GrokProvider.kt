package org.formulagen.wrapper.providers

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.chat.grok.GrokAuthAvailability

/** Subscription status and models; the separate development work gate owns project availability. */
internal class GrokProvider(
    private val connection: GrokConnection,
) : RuntimeProvider {
    constructor(environment: Map<String, String>) : this(GrokConnection(environment))
    override val id = RuntimeId.GROK_BUILD

    /** Refresh from the runtime on every check, including after a provider-owned login or logout. */
    override fun status(profile: String, action: ProviderAction): ProviderStatus {
        require(SharedRules.isProfileId(profile))
        val access = connection.check(fresh = true)
        if (access.executable == null) return ProviderStatus(id, ProviderState.UNAVAILABLE, message = id.copy.notInstalled, detail = ProviderDetail.NOT_INSTALLED)
        if (access.account != null) return ProviderStatus(id, ProviderState.CONNECTED,
            message = "Your Grok subscription is connected.")
        return when (access.auth) {
            GrokAuthAvailability.CACHED_CREDENTIAL -> ProviderStatus(id, ProviderState.UNAVAILABLE,
                message = GROK_SIGN_IN_DETECTED, detail = ProviderDetail.SIGN_IN_DETECTED)
            GrokAuthAvailability.SIGNED_OUT -> ProviderStatus(id, ProviderState.DISCONNECTED, detail = ProviderDetail.SIGNED_OUT)
            GrokAuthAvailability.API_KEY -> ProviderStatus(id, ProviderState.ERROR, detail = ProviderDetail.API_KEY,
                message = "Grok Build is offering API-key access. Sign in with your Grok account to use your subscription here.")
            GrokAuthAvailability.UNKNOWN -> ProviderStatus(id, ProviderState.ERROR, detail = ProviderDetail.UNKNOWN,
                message = "Grok Build could not pass the subscription and compatibility checks. This Hovel build supports Grok Build 1.0.70. Choose Check again to retry.")
        }
    }

    /** Expose only the catalog paired with a verified subscription response. */
    override fun models(profile: String): List<ProviderModel> {
        require(SharedRules.isProfileId(profile))
        return connection.check().account?.models.orEmpty()
    }

    /** The verified Grok interface supplies no subscription quota; missing windows mean unknown. */
    override fun usage(profile: String): ProviderUsage {
        require(SharedRules.isProfileId(profile))
        return ProviderUsage(id, System.currentTimeMillis())
    }

    override fun close() = Unit
}

internal const val GROK_SIGN_IN_DETECTED =
    "Grok Build reports a saved sign-in, but Hovel could not verify subscription access for companion chat. " +
        "You do not need to sign in again."

/** Explicit override, PATH, official user install and common package-manager locations. Never installs Grok. */
internal fun findGrokExecutable(environment: Map<String, String>,
    fixed: List<String> = listOf("/opt/homebrew/bin/grok", "/usr/local/bin/grok")): Path? {
    val configured = environment["GAMERO_GROK_PATH"]
    val home = environment["HOME"] ?: System.getProperty("user.home")
    val candidates = if (configured != null) listOf(configured) else
        environment["PATH"].orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { "$it/grok" } +
            listOf("$home/.grok/bin/grok", "$home/.local/bin/grok") + fixed
    return candidates.mapNotNull { runCatching { Path.of(it) }.getOrNull() }
        .firstOrNull { it.isAbsolute && Files.isRegularFile(it) && Files.isExecutable(it) }
}
