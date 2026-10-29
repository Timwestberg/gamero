package org.formulagen.wrapper.providers

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlinx.coroutines.runBlocking
import org.formulagen.wrapper.bridge.*
import org.formulagen.wrapper.providers.grok.GROK_VERIFIED_VERSION
import kotlin.test.*

class ProviderInstallationTest {
    private fun <T> fixture(block: (Path) -> T): T {
        val root = Files.createTempDirectory("provider-login '")
        return try { block(root) } finally { root.toFile().deleteRecursively() }
    }

    private fun executable(root: Path, name: String, version: String = if (name == "grok") GROK_VERIFIED_VERSION else "2.1.304",
        help: Boolean = true): Path {
        val file = root.resolve("$name ' \$(touch injected)")
        val command = if (name == "claude") "claude auth login" else "$name login"
        Files.writeString(file, """#!/bin/sh
if [ "${'$'}1" = "--version" ]; then echo '$version'; exit 0; fi
for arg in "${'$'}@"; do
  if [ "${'$'}arg" = "--help" ]; then
    printf '%s\n' "${'$'}@" > help-args
    printf '%s\n' "${'$'}GROK_DISABLE_AUTOUPDATER" > help-environment
    echo '${if (help) "Usage: $command [OPTIONS]" else "Old CLI"}'; exit 0
  fi
done
printf '%s\n' "${'$'}@" > args
printf '%s\n' "${'$'}HOME" "${'$'}CODEX_HOME" "${'$'}GROK_HOME" "${'$'}OPENAI_API_KEY" "${'$'}GROK_DISABLE_AUTOUPDATER" > environment
""")
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwx------"))
        return file
    }

    @Test fun readOnlyDiscoveryAndExplicitLaunchUseFixedCommandsForEveryProvider() = fixture { root ->
        for ((id, name) in listOf(RuntimeId.CODEX to "codex", RuntimeId.CLAUDE_CODE to "claude", RuntimeId.GROK_BUILD to "grok")) {
            val path = executable(root, name)
            val home = root.resolve("home '\$(touch injected)").toString()
            val account = root.resolve("custom-account").toString()
            var launches = 0
            val service = ProviderInstallation(root.resolve(name), mapOf("HOME" to home, "CODEX_HOME" to account,
                "GROK_HOME" to account, "OPENAI_API_KEY" to "fixture-secret"), discover = { path }, openTerminal = { script ->
                launches++
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(script))
                assertEquals(0, ProcessBuilder("/bin/sh", script.toString()).start().waitFor())
                assertFalse(Files.exists(script))
                true
            }, mac = true)
            assertEquals(ProviderSetupState.READY, service.setup(id).state)
            assertEquals(0, launches)
            assertFalse(Files.exists(root.resolve(name).resolve("sign-in/args")))
            assertTrue(service.startLogin(id))
            val folder = root.resolve(name).resolve("sign-in")
            assertEquals(if (id == RuntimeId.CLAUDE_CODE) listOf("auth", "login") else listOf("login"), Files.readAllLines(folder.resolve("args")))
            val grok = id == RuntimeId.GROK_BUILD
            assertEquals(listOf(home, if (id == RuntimeId.CODEX) account else "", if (grok) account else "", "", if (grok) "1" else ""),
                Files.readAllLines(folder.resolve("environment")))
            // Neither the setup probe nor the login can make Grok update itself away from the accepted release.
            assertEquals(when (id) { RuntimeId.CLAUDE_CODE -> listOf("auth", "login", "--help"); RuntimeId.GROK_BUILD -> listOf("--no-auto-update", "login", "--help")
                else -> listOf("login", "--help") }, Files.readAllLines(folder.resolve("help-args")))
            assertEquals(listOf(if (grok) "1" else ""), Files.readAllLines(folder.resolve("help-environment")))
            assertFalse(Files.exists(folder.resolve("injected")))
        }
    }

    @Test fun installUpdateAndRemovalAreRediscoveredBeforeLaunch() = fixture { root ->
        var path: Path? = null
        var launches = 0
        val service = ProviderInstallation(root, emptyMap(), discover = { path }, openTerminal = { launches++; true }, mac = true)
        assertEquals(ProviderSetupState.NOT_INSTALLED, service.setup(RuntimeId.CLAUDE_CODE).state)
        assertFalse(service.startLogin(RuntimeId.CLAUDE_CODE))
        path = executable(root, "claude", "2.1.303")
        assertEquals(ProviderSetupState.OUTDATED, service.setup(RuntimeId.CLAUDE_CODE).state)
        assertFalse(service.startLogin(RuntimeId.CLAUDE_CODE))
        path = executable(root, "claude")
        assertEquals(ProviderSetupState.READY, service.setup(RuntimeId.CLAUDE_CODE).state)
        Files.delete(path)
        assertFalse(service.startLogin(RuntimeId.CLAUDE_CODE))
        assertEquals(0, launches)
    }

    @Test fun aGrokReleaseOtherThanTheAcceptedOneIsOutdatedAndCannotSignIn() = fixture { root ->
        var launches = 0
        for (version in listOf("grok 1.0.71 (0123456789ab)", "grok 1.0.70", "1.0.70", "")) {
            val path = executable(root, "grok", version = version)
            val service = ProviderInstallation(root, emptyMap(), discover = { path }, openTerminal = { launches++; true }, mac = true)
            assertEquals(ProviderSetupState.OUTDATED, service.setup(RuntimeId.GROK_BUILD).state, version)
            assertFalse(service.startLogin(RuntimeId.GROK_BUILD))
            assertFalse(Files.exists(root.resolve("sign-in/help-args")), "No login probe runs for an unsupported release")
        }
        assertEquals(0, launches)
        val supported = executable(root, "grok")
        assertEquals(ProviderSetupState.READY, ProviderInstallation(root, emptyMap(), discover = { supported }, mac = true).setup(RuntimeId.GROK_BUILD).state)
    }

    @Test fun unsupportedHelpAndLaunchFailureNeverClaimSuccess() = fixture { root ->
        var path = executable(root, "grok", help = false)
        val service = ProviderInstallation(root, emptyMap(), discover = { path }, openTerminal = { false }, mac = true)
        assertEquals(ProviderSetupState.OUTDATED, service.setup(RuntimeId.GROK_BUILD).state)
        assertFalse(service.startLogin(RuntimeId.GROK_BUILD))
        path = executable(root, "grok")
        assertFalse(service.startLogin(RuntimeId.GROK_BUILD))
        Files.list(root.resolve("sign-in")).use { files -> assertEquals(0, files.filter { it.toString().endsWith(".command") }.count()) }
        assertEquals(ProviderSetupState.UNAVAILABLE, ProviderInstallation(root, emptyMap(), mac = false).setup(RuntimeId.CODEX).state)
        assertEquals(ProviderSetupState.UNAVAILABLE, ProviderInstallation(root, emptyMap(), discover = { error("private failure") }, mac = true).setup(RuntimeId.CODEX).state)
    }

    @Test fun gatedRuntimeAndInvalidProfileNeverDiscoverOrLaunch() = fixture { root -> runBlocking {
        val service = ProviderInstallation(root, emptyMap(), discover = { fail("Gated runtime must not be discovered") }, mac = true)
        DesktopProviders(emptyList(), installation = service).use { providers ->
            assertEquals(ProviderSetupState.UNAVAILABLE, providers.setup(ProviderScope("local", RuntimeId.GROK_BUILD)).state)
            assertFalse(providers.startLogin(ProviderScope("local", RuntimeId.GROK_BUILD)).launched)
            assertFailsWith<IllegalArgumentException> { providers.setup(ProviderScope("../profile", RuntimeId.CODEX)) }
            assertFailsWith<IllegalArgumentException> { providers.startLogin(ProviderScope("../profile", RuntimeId.CODEX)) }
            Unit
        }
    } }
}
