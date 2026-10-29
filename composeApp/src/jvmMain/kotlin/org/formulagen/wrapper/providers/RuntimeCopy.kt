package org.formulagen.wrapper.providers

import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules.RUNTIME_DEFAULT_MODEL

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
    /** A working message to this runtime answers as talk with this when this build does not let it work. */
    val cannotWork: String,
    /** A model's name for the player, by its id; null when the runtime has no names of its own for its models. */
    private val modelNames: Map<String, String> = emptyMap(),
) {
    /** "Sonnet" for `sonnet`, "default model" for the runtime's default, the id itself otherwise. */
    fun modelName(modelId: String): String = if (modelId == RUNTIME_DEFAULT_MODEL) "default model" else modelNames[modelId] ?: modelId
}

internal val RuntimeId.copy: RuntimeCopy get() = when (this) {
    RuntimeId.CODEX -> CODEX_COPY
    RuntimeId.CLAUDE_CODE -> CLAUDE_COPY
    RuntimeId.GROK_BUILD -> GROK_COPY
}

private val CODEX_COPY = RuntimeCopy(
    product = "Codex",
    program = "Codex",
    notInstalled = "Install Codex on this Mac, then restart Hovel to connect.",
    notOffered = "Codex is not available in this version of Hovel.",
    signedOut = "Sign in to Codex again: run `codex login` in Terminal.",
    cannotWork = "Codex can't work in projects yet in this version of Hovel.",
)

private val CLAUDE_COPY = RuntimeCopy(
    product = "Claude",
    program = "Claude Code",
    notInstalled = "Install Claude Code on this Mac, then restart Hovel to use Claude.",
    notOffered = "Claude is not available in this version of Hovel.",
    signedOut = "Sign in to Claude Code again.",
    cannotWork = "Claude can't work in projects in this version of Hovel.",
    modelNames = CLAUDE_MODELS.associate { it.modelId to it.displayName },
)

private val GROK_COPY = RuntimeCopy(
    product = "Grok",
    program = "Grok Build",
    notInstalled = "Install Grok Build on this Mac, then restart Hovel to connect.",
    notOffered = "Grok is not available in this version of Hovel.",
    signedOut = "Sign in to Grok Build again: run `grok login` in Terminal.",
    cannotWork = "Grok can't work in projects yet in this version of Hovel.",
)
