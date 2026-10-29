package org.formulagen.wrapper.providers.grok

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assume.assumeTrue

/**
 * The opt-ins for live checks against an installed Grok Build; without them every live test skips.
 * `HOVEL_GROK_SMOKE=/absolute/path/to/grok` runs the checks that send no model turn, and
 * `HOVEL_GROK_SMOKE_TURNS=1` also runs the checks that send controlled model turns on the subscription.
 */
internal object GrokSmoke {
    /** The runtime under test. Skips the calling test unless `HOVEL_GROK_SMOKE` names it. */
    fun executable(): Path {
        val value = System.getenv("HOVEL_GROK_SMOKE")
        assumeTrue("Set HOVEL_GROK_SMOKE=/absolute/path/to/grok for live Grok checks", value != null)
        return Path.of(requireNotNull(value)).also { require(it.isAbsolute && Files.isExecutable(it)) }
    }

    /** [executable], for a check that sends model turns: also requires `HOVEL_GROK_SMOKE_TURNS=1`. */
    fun turnsExecutable(): Path = executable().also {
        assumeTrue("Set HOVEL_GROK_SMOKE_TURNS=1 as well to allow controlled model turns", turns)
    }

    /** Whether model turns are allowed, for a check that adds an optional turn when they are. */
    val turns: Boolean get() = System.getenv("HOVEL_GROK_SMOKE_TURNS") == "1"

    /** The player's Grok home: `$GROK_HOME`, by default `~/.grok`. */
    val providerHome: Path get() = Path.of(System.getenv("GROK_HOME") ?: (System.getenv("HOME") + "/.grok"))

    /** The player's login, which tests reference and never read: `$GROK_AUTH_PATH`, by default in [providerHome]. */
    val authPath: Path get() = System.getenv("GROK_AUTH_PATH")?.takeIf(String::isNotBlank)?.let(Path::of)
        ?: providerHome.resolve("auth.json")

    /** The player's own environment selecting [executable], with [authPath] set explicitly for talk and work alike. */
    fun environment(executable: Path): Map<String, String> =
        System.getenv() + mapOf("GAMERO_GROK_PATH" to executable.toString(), "GROK_AUTH_PATH" to authPath.toString())

    /**
     * [environment] with a disposable provider home in [root], holding a private copy of the player's
     * `config.toml`: a test's folder-trust grant stays out of their store, and their login is still only referenced.
     */
    fun disposableHomeEnvironment(root: Path, executable: Path): Map<String, String> {
        val home = Files.createDirectory(root.resolve("grok-home"),
            PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        Files.copy(providerHome.resolve("config.toml"), home.resolve("config.toml"))
        Files.setPosixFilePermissions(home.resolve("config.toml"), PosixFilePermissions.fromString("rw-------"))
        return environment(executable) + ("GROK_HOME" to home.toString())
    }
}
