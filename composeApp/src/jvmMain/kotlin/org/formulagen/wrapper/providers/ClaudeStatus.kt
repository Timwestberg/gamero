package org.formulagen.wrapper.providers

import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.ProviderDetail
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId

/**
 * The fields Hovel reads from `claude auth status --json`; the organization and Claude Code's
 * folders are never read. A signed-out answer may leave out [authMethod].
 */
@Serializable
internal data class ClaudeAuthStatus(
    val loggedIn: Boolean,
    val authMethod: String = "none",
    val subscriptionType: String? = null,
    val email: String? = null,
)

/**
 * The card state for an install and its sign-in. [installed] is whether Hovel found Claude Code;
 * [version] is null when `--version` could not be read (or timed out); [auth] is null when the
 * sign-in is unknown. Anything Hovel could not read is an error, never "outdated" or "not signed in".
 */
internal fun claudeProviderStatus(installed: Boolean, version: ClaudeVersion?, auth: ClaudeAuthStatus?): ProviderStatus {
    val copy = RuntimeId.CLAUDE_CODE.copy
    return when {
        !installed -> claude(ProviderState.UNAVAILABLE, ProviderDetail.NOT_INSTALLED, copy.notInstalled)
        version == null -> unknownClaudeStatus()
        version < MIN_CLAUDE_VERSION -> claude(ProviderState.UNAVAILABLE, ProviderDetail.OUTDATED,
            "Update Claude Code to use it here. Hovel needs version $MIN_CLAUDE_VERSION or later.")
        auth == null -> unknownClaudeStatus()
        !auth.loggedIn -> claude(ProviderState.DISCONNECTED, ProviderDetail.SIGNED_OUT, copy.signInHint)
        // Only a Claude subscription may pay for companion chat; an API key or any other method is refused.
        auth.authMethod != "claude.ai" -> claude(ProviderState.ERROR, ProviderDetail.API_KEY,
            "Claude Code is using an API key. Sign in with your Claude subscription to use it here.")
        else -> ProviderStatus(RuntimeId.CLAUDE_CODE, ProviderState.CONNECTED,
            accountLabel = auth.email?.takeIf { it.length <= ProviderLimits.ACCOUNT_LABEL },
            // The raw subscription type ("max"); the page names the plan.
            plan = auth.subscriptionType?.takeIf { it.isNotEmpty() && it.length <= ProviderLimits.PLAN })
    }
}

/** A runtime this build does not offer, as every Providers call and every chat send for it answers. */
internal fun notOfferedStatus(runtime: RuntimeId) =
    ProviderStatus(runtime, ProviderState.UNAVAILABLE, message = runtime.copy.notOffered, detail = ProviderDetail.NOT_OFFERED)

/** Claude Code ran, but Hovel could not read its version or sign-in. */
internal const val CLAUDE_STATUS_UNKNOWN = "Claude status could not be checked."

private fun unknownClaudeStatus() = claude(ProviderState.ERROR, ProviderDetail.UNKNOWN, CLAUDE_STATUS_UNKNOWN)

private fun claude(state: ProviderState, detail: ProviderDetail, message: String?) =
    ProviderStatus(RuntimeId.CLAUDE_CODE, state, message = message, detail = detail)
