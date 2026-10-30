package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*
import org.junit.Test
import org.formulagen.wrapper.providers.codexProcess
import org.formulagen.wrapper.providers.scrubCodexEnvironment

class CodexCliProviderTest {
    @Test fun memoryJobsRunOnThePlayersOwnCodexLoginWithNoSettingsOfHovels() {
        val directory = Files.createTempDirectory("hovel-codex-profile")
        try {
            val provider = CodexCliProvider(java.nio.file.Path.of("/bin/echo"), directory, directory.resolve("output"))
            val command = provider.builder(listOf("login", "status"))
            // No forced login method (it logs an API-key login out) and no credential store (the player's own applies).
            assertEquals(listOf("/bin/echo", "login", "status"), command.command())
            assertEquals(System.getenv("CODEX_HOME"), command.environment()["CODEX_HOME"])
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun everyCodexChildIsThePlayersCodexWithoutInheritedCredentials() {
        val directory = Files.createTempDirectory("hovel-codex-environment")
        try {
            val echo = java.nio.file.Path.of("/bin/echo")
            assertEquals(listOf("/bin/echo", "exec"), codexProcess(echo, listOf("exec"), directory).command())
            val inherited = mapOf("OPENAI_API_KEY" to "k", "OPENAI_BASE_URL" to "u", "ACCESS_TOKEN" to "t", "CODEX_API_KEY" to "k",
                "CODEX_SANDBOX" to "seatbelt", "CODEX_SANDBOX_NETWORK_DISABLED" to "1", "CODEX_THREAD_ID" to "x",
                "CODEX_CA_CERTIFICATE" to "ca", "CODEX_HOME" to "/player/codex", "PATH" to "/bin")
            val scrubbed = inherited.toMutableMap().also { scrubCodexEnvironment(it) }
            // The player's own home and network setup stay; billing keys and another Codex session's variables go.
            assertEquals(mapOf("CODEX_CA_CERTIFICATE" to "ca", "CODEX_HOME" to "/player/codex", "PATH" to "/bin"), scrubbed)
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun fixture(auth: String = "Logged in using ChatGPT", exit: Int = 0, store: String = "file"): Pair<CodexCliProvider, java.nio.file.Path> {
        val directory = Files.createTempDirectory("fake-codex")
        val executable = directory.resolve("codex")
        Files.writeString(executable, """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > all-arguments.txt
            found=""
            while [ "${'$'}1" = -c ]; do
              if [ "${'$'}2" = 'cli_auth_credentials_store="$store"' ]; then found=1; fi
              shift 2
            done
            if [ "${'$'}1" = login ]; then
              if [ -n "${'$'}found" ]; then echo '$auth'; else echo 'Not logged in'; fi
              exit 0
            fi
            mv all-arguments.txt arguments.txt
            cat > input.txt
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = --output-last-message ]; then shift; printf 'fixture result' > "${'$'}1"; fi
              shift
            done
            exit $exit
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        return CodexCliProvider(executable, directory, java.nio.file.Path.of("").toAbsolutePath().relativize(directory.resolve("output"))) to directory
    }

    @Test fun supportedCliUsesStdinAndRetainsOutputWithoutShellInterpolation() {
        val (provider, directory) = fixture()
        val id = UUID.randomUUID().toString()
        val request = ExecutionRequest(id, UUID.randomUUID().toString(), "literal ${'$'}(touch unwanted)")
        assertEquals(CodexCliProvider.Readiness.READY, provider.readiness())
        assertEquals(ProviderResult(true, "fixture result"), provider.execute(request))
        val args = Files.readAllLines(directory.resolve("arguments.txt"))
        assertTrue(args.containsAll(listOf("never", "--ignore-user-config", "--ephemeral", "read-only")))
        // The job reads the login where readiness found it; no forced login method, which would log an API key out.
        assertEquals(listOf("-c", "cli_auth_credentials_store=\"file\""), args.take(2))
        assertFalse(args.any { it.startsWith("forced_login_method") })
        assertFalse(args.contains(request.prompt))
        assertTrue(Files.readString(directory.resolve("input.txt")).contains(request.prompt))
        assertFalse(Files.exists(directory.resolve("unwanted")))
        assertEquals("fixture result", Files.readString(directory.resolve("output/$id.txt")))
        assertFails { provider.execute(request) }
    }

    @Test fun aMemoryJobRunsItsInputAsItIsOnItsModelAndEffort() {
        val (provider, directory) = fixture()
        val input = "Extract memory notes from these exchanges.\n[t1] Request:\nFix the build."
        val request = ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), input,
            choice = ProviderChoice("gpt-5.1-codex-mini", "low"))
        assertEquals(ProviderResult(true, "fixture result"), provider.execute(request))
        assertEquals(input, Files.readString(directory.resolve("input.txt")))
        val args = Files.readAllLines(directory.resolve("arguments.txt"))
        assertTrue(args.containsAll(listOf("--ignore-user-config", "--ephemeral", "read-only", "model_reasoning_effort=\"low\"")))
        assertEquals("gpt-5.1-codex-mini", args[args.indexOf("-m") + 1])
        assertTrue(args.indexOf("exec") < args.indexOf("-m"))
    }

    @Test fun onlyARequestWithAChoiceNamesAModelOrEffort() {
        val output = java.nio.file.Path.of("/tmp/out.txt")
        val tail = listOf("--ignore-user-config", "--ephemeral", "--sandbox", "read-only", "--skip-git-repo-check",
            "--color", "never", "--output-last-message", "/tmp/out.txt", "-")
        fun request(choice: ProviderChoice?) = ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "x", choice = choice)
        // Without a choice, Codex runs its own model and effort.
        val head = listOf("-c", "cli_auth_credentials_store=\"keyring\"", "-a", "never")
        assertEquals(head + "exec" + tail, codexExecArguments(request(null), output, "keyring"))
        assertEquals(head + "exec" + tail,
            codexExecArguments(request(ProviderChoice("runtime-default", "runtime-default")), output, "keyring"))
        assertEquals(head + listOf("-c", "model_reasoning_effort=\"xhigh\"", "exec", "-m", "o4-mini") + tail,
            codexExecArguments(request(ProviderChoice("o4-mini", "xhigh")), output, "keyring"))
        // A choice that could be read as a flag cannot exist.
        assertFails { ProviderChoice("--profile", "low") }
    }

    @Test fun apiKeyLoginIsNotAdvertisedReady() {
        val (provider, directory) = fixture("Logged in using an API key")
        assertEquals(CodexCliProvider.Readiness.AUTH_REQUIRED, provider.readiness())
        assertFails { provider.execute(ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hello")) }
        assertFalse(Files.exists(directory.resolve("arguments.txt")))
        assertFalse(provider.builder(listOf("login", "status")).environment().keys.any { it.startsWith("OPENAI_") || it == "CODEX_API_KEY" })
    }

    @Test fun cleanupForceKillsObservedChildAfterParentExit() {
        val child = ProcessBuilder("sh", "-c", "trap '' TERM; exec sleep 60").start()
        val parent = ProcessBuilder("sh", "-c", "exit 0").start()
        try {
            parent.waitFor()
            // Allow the fixture to install its ignored TERM disposition before cleanup.
            Thread.sleep(100)
            terminateProcessTree(parent, setOf(child.toHandle()))
            assertTrue(child.waitFor(2, java.util.concurrent.TimeUnit.SECONDS))
        } finally { child.destroyForcibly(); parent.destroyForcibly() }
    }

    @Test fun nonzeroProviderExitRemainsFailed() {
        val (provider, _) = fixture(exit = 4)
        assertFalse(provider.execute(ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hello")).succeeded)
    }

    @Test fun aKeyringOnlyLoginIsFoundAndTheJobReadsItFromTheKeyring() {
        // `--ignore-user-config` would otherwise read the default file store, where this player has no login.
        val (provider, directory) = fixture(store = "keyring")
        assertEquals(CodexCliProvider.Readiness.READY, provider.readiness())
        assertEquals(ProviderResult(true, "fixture result"), provider.execute(ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hello")))
        assertEquals(listOf("-c", "cli_auth_credentials_store=\"keyring\""), Files.readAllLines(directory.resolve("arguments.txt")).take(2))
        val (signedOut, _) = fixture(store = "nowhere")
        assertEquals(CodexCliProvider.Readiness.AUTH_REQUIRED, signedOut.readiness())
    }
}
