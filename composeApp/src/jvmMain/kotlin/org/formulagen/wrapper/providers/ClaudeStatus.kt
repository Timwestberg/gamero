package org.formulagen.wrapper.providers

import kotlinx.serialization.Serializable
import org.formulagen.wrapper.bridge.ProviderLimits
import org.formulagen.wrapper.bridge.ProviderState

/** The fields Hovel reads from `claude auth status --json`; the organization and Claude Code's folders are never read. */
@Serializable
internal data class ClaudeAuthStatus(
    val loggedIn: Boolean,
    val authMethod: String,
    val subscriptionType: String? = null,
    val email: String? = null,
)

/** What the Claude card shows: the bridge's provider status without its ids, which the providers capability adds. */
internal data class ClaudeProviderStatus(
    val state: ProviderState,
    val accountLabel: String? = null,
    val plan: String? = null,
    val message: String? = null,
)

/**
 * The card state for an install and its sign-in. [installed] is whether Hovel found Claude Code;
 * [version] is null when `--version` could not be read; [auth] is null when the sign-in is unknown
 * (a timeout or unexpected output), which is an error, never "not signed in".
 */
internal fun claudeProviderStatus(installed: Boolean, version: ClaudeVersion?, auth: ClaudeAuthStatus?): ClaudeProviderStatus = when {
    !installed -> ClaudeProviderStatus(ProviderState.UNAVAILABLE, message = "Install Claude Code on this Mac to use Claude.")
    version == null || version < MIN_CLAUDE_VERSION -> ClaudeProviderStatus(ProviderState.UNAVAILABLE,
        message = "Update Claude Code to use it here. Hovel needs version $MIN_CLAUDE_VERSION or later.")
    auth == null -> ClaudeProviderStatus(ProviderState.ERROR, message = "Claude status could not be checked.")
    !auth.loggedIn -> ClaudeProviderStatus(ProviderState.DISCONNECTED)
    // Only a Claude subscription may pay for companion chat; an API key or any other method is refused.
    auth.authMethod != "claude.ai" -> ClaudeProviderStatus(ProviderState.ERROR,
        message = "Claude Code is using an API key. Sign in with your Claude subscription to use it here.")
    else -> ClaudeProviderStatus(ProviderState.CONNECTED,
        accountLabel = auth.email?.takeIf { it.length <= ProviderLimits.ACCOUNT_LABEL },
        plan = auth.subscriptionType?.let(::planName)?.takeIf { it.isNotEmpty() && it.length <= ProviderLimits.PLAN })
}

/** "max" → "Max", "max_20x" → "Max 20x". */
private fun planName(subscription: String): String =
    subscription.split('_', '-', ' ').filter { it.isNotEmpty() }.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
