package org.formulagen.wrapper.providers

import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.grok.GROK_VERSION_MESSAGE
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability

/** Subscription status and models; the separate development work gate owns project availability. */
internal class GrokProvider(
    private val connection: GrokConnection,
) : RuntimeProvider {
    constructor(environment: Map<String, String>) : this(GrokConnection(environment))
    override val id = RuntimeId.GROK_BUILD

    /** Refresh from the runtime on every check, including after a provider-owned login or logout. */
    override fun status(action: ProviderAction): ProviderStatus {
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
                message = "Grok Build could not pass the subscription and compatibility checks. $GROK_VERSION_MESSAGE Choose Check again to retry.")
        }
    }

    /** Expose only the catalog paired with a verified subscription response. */
    override fun models(): List<ProviderModel> = connection.check().account?.models.orEmpty()

    /** The verified Grok interface supplies no subscription quota; missing windows mean unknown. */
    override fun usage(): ProviderUsage = ProviderUsage(id, System.currentTimeMillis())

    override fun close() = Unit
}

internal const val GROK_SIGN_IN_DETECTED =
    "Grok Build reports a saved sign-in, but Hovel could not verify subscription access for companion chat. " +
        "You do not need to sign in again."
