package org.formulagen.wrapper.providers

import org.formulagen.wrapper.bridge.RuntimeId

/**
 * What players read about a runtime, in one place for chat and providers. [product] is the name
 * players see ("Claude"); [program] is what they install and sign in to ("Claude Code").
 */
internal class RuntimeCopy(
    val product: String,
    val program: String,
    /** The runtime was not found when Hovel started. */
    val notInstalled: String,
    /** This build does not offer the runtime (the release gate). */
    val notOffered: String,
    /** A chat notice when the runtime's sign-in ended. */
    val signedOut: String,
    /** How to sign in, for a Providers card whose sign-in happens outside Hovel; null when Hovel signs in itself. */
    val signInHint: String?,
)

internal val RuntimeId.copy: RuntimeCopy get() = when (this) {
    RuntimeId.CODEX -> CODEX_COPY
    RuntimeId.CLAUDE_CODE -> CLAUDE_COPY
}

private val CODEX_COPY = RuntimeCopy(
    product = "Codex",
    program = "Codex",
    notInstalled = "Install Codex on this Mac, then restart Hovel to connect.",
    notOffered = "Codex is not available in this version of Hovel.",
    signedOut = "Connect Codex in Profile → Providers to message your companion.",
    signInHint = null,
)

private val CLAUDE_COPY = RuntimeCopy(
    product = "Claude",
    program = "Claude Code",
    notInstalled = "Install Claude Code on this Mac, then restart Hovel to use Claude.",
    notOffered = "Claude is not available in this version of Hovel.",
    signedOut = "Sign in to Claude Code again.",
    signInHint = "Sign in to Claude Code with your Claude subscription: run `claude auth login` in Terminal, then choose Check again.",
)
