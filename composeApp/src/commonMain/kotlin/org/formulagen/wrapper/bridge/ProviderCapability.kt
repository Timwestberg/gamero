package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A profile's runtime connections and explicit provider-owned setup. Tokens, keys and login URLs stay native.
 */
interface ProviderCapability {
    suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus

    /** The runtimes this host offers [profile]. A gated runtime is absent, so the page shows no card for it. */
    suspend fun list(profile: String): ProviderList

    /** List runtimes the requesting page can decode. */
    suspend fun listForClient(args: ProviderListForClientArgs): ProviderList

    /** The models a team member on [ProviderScope.runtimeId] may use. */
    suspend fun models(scope: ProviderScope): ProviderModels

    /** Shared subscription limits, without starting a model turn. Missing windows mean unknown. */
    suspend fun usage(scope: ProviderScope): ProviderUsage
    suspend fun setup(scope: ProviderScope): ProviderSetup
    suspend fun startLogin(scope: ProviderScope): ProviderLogin
}

/**
 * Every action answers the connection's status. Codex and Claude Code both sign in outside Hovel,
 * in the runtime itself (`codex login`, `claude auth login`), so `connect`, `cancel`, `reopen` and
 * `disconnect` answer the status unchanged, with a message that names the runtime's own command.
 */
enum class ProviderAction(val wireName: String) {
    STATUS("status"),
    CONNECT("connect"),
    CANCEL("cancel"),
    REOPEN("reopen"),
    DISCONNECT("disconnect"),
}

/** Bounds of what a page may see about providers. */
object ProviderLimits { // mirrors PROVIDER_LIMITS
    const val ACCOUNT_LABEL = 320
    const val DISPLAY_NAME = 80
    const val MESSAGE = 500
    const val MODELS = 32
    const val PLAN = 80
    const val RUNTIMES = 8
}

@Serializable
enum class ProviderState {
    @SerialName("disconnected") DISCONNECTED,
    @SerialName("signing-in") SIGNING_IN,
    @SerialName("connected") CONNECTED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("error") ERROR,
}

/** One profile's connection to one runtime. Every profile shares the person's own sign-in of each runtime on the Mac. */
@Serializable
data class ProviderScope(val profile: String, val runtimeId: RuntimeId)

@Serializable
data class ProviderListArgs(val profile: String)

@Serializable
data class ProviderListForClientArgs(val profile: String, val supportedRuntimes: List<String>)

/**
 * The case behind a state, so the page need not match message text: `not-installed`, `outdated` and
 * `not-offered` (this host gates the runtime off) with unavailable; `signed-out` with disconnected;
 * `api-key` and `unknown` with error.
 */
@Serializable
enum class ProviderDetail {
    @SerialName("not-installed") NOT_INSTALLED,
    @SerialName("outdated") OUTDATED,
    @SerialName("signed-out") SIGNED_OUT,
    @SerialName("api-key") API_KEY,
    @SerialName("unknown") UNKNOWN,
    @SerialName("not-offered") NOT_OFFERED,
    /** Grok reports a saved login, but subscription access could not be verified. */
    @SerialName("sign-in-detected") SIGN_IN_DETECTED,
}

/**
 * [accountLabel] is the account's email and [plan] its plan name, sent only when [state] is
 * connected. [providerId] is always the company behind [runtimeId].
 */
@Serializable
data class ProviderStatus(
    val runtimeId: RuntimeId,
    val state: ProviderState,
    val accountLabel: String? = null,
    val plan: String? = null,
    val message: String? = null,
    val detail: ProviderDetail? = null,
    val providerId: RuntimeProviderId = runtimeId.providerId,
) {
    init { require(providerId == runtimeId.providerId) { "A provider status names the provider of its runtime" } }
}

/**
 * The runtimes a host offers a profile, each shown as one card. [working] lists those this host
 * lets work in a project folder: the page offers "Works in the project" only for these, and a
 * gated runtime is absent.
 */
@Serializable
data class ProviderList(val runtimes: List<RuntimeId>, val working: List<RuntimeId>) {
    init { require(runtimes.containsAll(working)) { "A working runtime is one the host offers" } }
}

/** One model a team member may use: the runtime's own [displayName], and whether it is the runtime's default. */
@Serializable
data class ProviderModel(val displayName: String, val isDefault: Boolean, val modelId: String,
    val effortLevels: List<ModelEffort>? = null, val defaultEffort: ModelEffort? = null)

@Serializable
data class ProviderModels(val models: List<ProviderModel>, val runtimeId: RuntimeId)

/** Subscription percentage used, with an optional reset time in Unix seconds. */
@Serializable
data class ProviderUsageWindow(val usedPercent: Double, val resetsAt: Long? = null) {
    init {
        require(usedPercent.isFinite() && usedPercent in 0.0..100.0)
        require(resetsAt == null || resetsAt in 0..9_007_199_254_740_991L)
    }
}

/** Checked at Unix milliseconds. Session is the five-hour quota; absent windows mean unavailable. */
@Serializable
data class ProviderUsage(
    val runtimeId: RuntimeId,
    val checkedAt: Long,
    val session: ProviderUsageWindow? = null,
    val weekly: ProviderUsageWindow? = null,
) {
    init { require(checkedAt in 0..9_007_199_254_740_991L) }
}
/** Installation support only; ready never implies account eligibility or chat availability. */
@Serializable
enum class ProviderSetupState {
    @SerialName("ready") READY,
    @SerialName("not-installed") NOT_INSTALLED,
    @SerialName("outdated") OUTDATED,
    @SerialName("unavailable") UNAVAILABLE,
}

@Serializable
data class ProviderSetup(val runtimeId: RuntimeId, val state: ProviderSetupState)

/** Opening Terminal is not proof of successful authentication. */
@Serializable
data class ProviderLogin(val runtimeId: RuntimeId, val launched: Boolean)

/** Mirrors event-contracts/host-bridge/providers.ts. */
object ProviderMethods {
    private fun method(action: ProviderAction) = BridgeMethod("providers.${action.wireName}", ProviderScope.serializer(),
        ProviderStatus.serializer()) { SharedRules.isProfileId(it.profile) }

    val STATUS = method(ProviderAction.STATUS)
    val CONNECT = method(ProviderAction.CONNECT)
    val CANCEL = method(ProviderAction.CANCEL)
    val REOPEN = method(ProviderAction.REOPEN)
    val DISCONNECT = method(ProviderAction.DISCONNECT)
    val LIST = BridgeMethod("providers.list", ProviderListArgs.serializer(), ProviderList.serializer()) { SharedRules.isProfileId(it.profile) }
    val LIST_FOR_CLIENT = BridgeMethod("providers.listForClient", ProviderListForClientArgs.serializer(), ProviderList.serializer()) {
        SharedRules.isProfileId(it.profile) && SharedRules.validClientRuntimes(it.supportedRuntimes)
    }
    val MODELS = BridgeMethod("providers.models", ProviderScope.serializer(), ProviderModels.serializer()) { SharedRules.isProfileId(it.profile) }
    val USAGE = BridgeMethod("providers.usage", ProviderScope.serializer(), ProviderUsage.serializer()) { SharedRules.isProfileId(it.profile) }
    val SETUP = BridgeMethod("providers.setup", ProviderScope.serializer(), ProviderSetup.serializer()) { SharedRules.isProfileId(it.profile) }
    val START_LOGIN = BridgeMethod("providers.startLogin", ProviderScope.serializer(), ProviderLogin.serializer()) { SharedRules.isProfileId(it.profile) }

    fun pack(providers: Capability<ProviderCapability>): CapabilityPack = binder.pack(providers)

    internal val binder = CapabilityBinder("providers", listOf(STATUS, CONNECT, CANCEL, REOPEN, DISCONNECT)
        .zip(ProviderAction.entries)
        .map { (method, action) -> method.handle { providers: ProviderCapability, scope -> providers.perform(action, scope) } } + listOf(
        LIST.handle { providers: ProviderCapability, args -> providers.list(args.profile) },
        LIST_FOR_CLIENT.handle { providers: ProviderCapability, args -> providers.listForClient(args) },
        MODELS.handle { providers: ProviderCapability, scope -> providers.models(scope) },
        USAGE.handle { providers: ProviderCapability, scope -> providers.usage(scope) },
        SETUP.handle { providers: ProviderCapability, scope -> providers.setup(scope) },
        START_LOGIN.handle { providers: ProviderCapability, scope -> providers.startLogin(scope) },
    ))
}
