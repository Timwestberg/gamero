package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A profile's account connections. Every action answers the connection's display status; tokens,
 * keys and the sign-in URL stay native.
 */
fun interface ProviderCapability {
    suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus
}

/** `connect` opens the provider's sign-in page in the system browser, `reopen` opens a pending one again, `cancel` abandons it, `disconnect` signs out. */
enum class ProviderAction(val wireName: String) {
    STATUS("status"),
    CONNECT("connect"),
    CANCEL("cancel"),
    REOPEN("reopen"),
    DISCONNECT("disconnect"),
}

/** Bounds of the display state a provider connection shows. */
object ProviderLimits { // mirrors PROVIDER_LIMITS
    const val ACCOUNT_LABEL = 320
    const val MESSAGE = 500
    const val PLAN = 80
}

/** Codex signs in with the person's ChatGPT account. */
@Serializable
enum class ProviderId {
    @SerialName("codex") CODEX,
}

@Serializable
enum class ProviderState {
    @SerialName("disconnected") DISCONNECTED,
    @SerialName("signing-in") SIGNING_IN,
    @SerialName("connected") CONNECTED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("error") ERROR,
}

/** One profile's connection to one provider. Native code keeps each profile's sign-in in its own folder. */
@Serializable
data class ProviderScope(val profile: String, val providerId: ProviderId)

/** [accountLabel] is the account's email and [plan] its plan name, sent only when [state] is connected. */
@Serializable
data class ProviderStatus(
    val providerId: ProviderId,
    val state: ProviderState,
    val accountLabel: String? = null,
    val plan: String? = null,
    val message: String? = null,
)

/** Mirrors event-contracts/host-bridge/providers.ts. */
object ProviderMethods {
    private fun method(action: ProviderAction) = BridgeMethod("providers.${action.wireName}", ProviderScope.serializer(),
        ProviderStatus.serializer()) { SharedRules.isProfileId(it.profile) }

    val STATUS = method(ProviderAction.STATUS)
    val CONNECT = method(ProviderAction.CONNECT)
    val CANCEL = method(ProviderAction.CANCEL)
    val REOPEN = method(ProviderAction.REOPEN)
    val DISCONNECT = method(ProviderAction.DISCONNECT)

    fun pack(providers: Capability<ProviderCapability>): CapabilityPack = binder.pack(providers)

    internal val binder = CapabilityBinder("providers", listOf(STATUS, CONNECT, CANCEL, REOPEN, DISCONNECT)
        .zip(ProviderAction.entries)
        .map { (method, action) -> method.handle { providers: ProviderCapability, scope -> providers.perform(action, scope) } })
}
