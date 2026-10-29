package org.formulagen.wrapper.chat.grok

/**
 * The one Grok Build release this Hovel build accepts. Talk, work, setup and the provider card all
 * read it from here; a new release needs a fresh acceptance run before this changes (docs/grok/README.md).
 */
internal const val GROK_VERIFIED_VERSION = "grok 1.0.70 (f50904eaee6c)"

/** The accepted release as players read it ("1.0.70"), taken from [GROK_VERIFIED_VERSION]. */
internal val GROK_SUPPORTED_VERSION: String = GROK_VERIFIED_VERSION.removePrefix("grok ").substringBefore(" ")

/** Player-facing copy naming the accepted release. */
internal val GROK_VERSION_MESSAGE = "This Hovel build supports Grok Build $GROK_SUPPORTED_VERSION."

/** Whether `grok --version` printed exactly the accepted release. */
internal fun grokVersionSupported(output: String): Boolean = output.trim() == GROK_VERIFIED_VERSION
