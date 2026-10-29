package org.formulagen.wrapper.providers.grok

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

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
