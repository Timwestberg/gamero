package org.formulagen.wrapper.providers

import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderState
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId

/** The fields Hovel reads from `claude auth status --json`; the organization and Claude Code's folders are never read. */
@Serializable
internal data class ClaudeAuthStatus(
    val loggedIn: Boolean,
    val authMethod: String,
    val subscriptionType: String? = null,
    val email: String? = null,
)

/**
 * The card state for an install and its sign-in. [installed] is whether Hovel found Claude Code;
 * [version] is null when `--version` could not be read; [auth] is null when the sign-in is unknown
 * (a timeout or unexpected output), which is an error, never "not signed in".
 */
internal fun claudeProviderStatus(installed: Boolean, version: ClaudeVersion?, auth: ClaudeAuthStatus?): ProviderStatus = when {
    !installed -> claude(ProviderState.UNAVAILABLE, message = "Install Claude Code on this Mac to use Claude.")
    version == null || version < MIN_CLAUDE_VERSION -> claude(ProviderState.UNAVAILABLE,
        message = "Update Claude Code to use it here. Hovel needs version $MIN_CLAUDE_VERSION or later.")
    auth == null -> claude(ProviderState.ERROR, message = "Claude status could not be checked.")
    !auth.loggedIn -> claude(ProviderState.DISCONNECTED)
    // Only a Claude subscription may pay for companion chat; an API key or any other method is refused.
    auth.authMethod != "claude.ai" -> claude(ProviderState.ERROR,
        message = "Claude Code is using an API key. Sign in with your Claude subscription to use it here.")
    else -> claude(ProviderState.CONNECTED,
        accountLabel = auth.email?.takeIf { it.length <= ProviderLimits.ACCOUNT_LABEL },
        // The raw subscription type ("max"); the page names the plan.
        plan = auth.subscriptionType?.takeIf { it.isNotEmpty() && it.length <= ProviderLimits.PLAN })
}

private fun claude(state: ProviderState, accountLabel: String? = null, plan: String? = null, message: String? = null) =
    ProviderStatus(RuntimeId.CLAUDE_CODE, state, accountLabel, plan, message)
