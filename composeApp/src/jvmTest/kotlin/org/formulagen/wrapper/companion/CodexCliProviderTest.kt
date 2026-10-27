package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*
import org.junit.Test
import org.formulagen.wrapper.providers.codexProcess
import org.formulagen.wrapper.providers.scrubCodexEnvironment

class CodexCliProviderTest {
    @Test fun profileTasksUseTheSameIsolatedKeyringAsProviderSettings() {
        val directory = Files.createTempDirectory("hovel-codex-profile")
        try {
            val home = directory.resolve("provider-account")
            val provider = CodexCliProvider(java.nio.file.Path.of("/bin/echo"), directory, directory.resolve("output"), home)
            val command = provider.builder(listOf("login", "status"))
            assertEquals(home.toString(), command.environment()["CODEX_HOME"])
            assertTrue(command.command().contains("cli_auth_credentials_store=\"keyring\""))
            assertEquals(1, command.command().count { it == "forced_login_method=\"chatgpt\"" })
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test fun everyCodexChildIsChatgptOnlyWithoutInheritedCredentials() {
        val directory = Files.createTempDirectory("hovel-codex-environment")
        try {
            val home = directory.resolve("home")
            val echo = java.nio.file.Path.of("/bin/echo")
            assertEquals(listOf("/bin/echo", "-c", "forced_login_method=\"chatgpt\"", "-c", "cli_auth_credentials_store=\"keyring\"", "exec"),
                codexProcess(echo, listOf("exec"), directory, home).command())
            assertEquals(listOf("/bin/echo", "-c", "forced_login_method=\"chatgpt\"", "exec"), codexProcess(echo, listOf("exec"), directory).command())
            val inherited = mapOf("OPENAI_API_KEY" to "k", "ACCESS_TOKEN" to "t", "CODEX_API_KEY" to "k", "CODEX_SANDBOX" to "s",
                "CODEX_CA_CERTIFICATE" to "ca", "CODEX_HOME" to "/user/codex", "PATH" to "/bin")
            val isolated = inherited.toMutableMap().also { scrubCodexEnvironment(it, home) }
            assertEquals(mapOf("CODEX_CA_CERTIFICATE" to "ca", "CODEX_HOME" to home.toAbsolutePath().toString(), "PATH" to "/bin"), isolated)
            val userLogin = inherited.toMutableMap().also { scrubCodexEnvironment(it, null) }
            assertEquals(mapOf("CODEX_CA_CERTIFICATE" to "ca", "CODEX_HOME" to "/user/codex", "PATH" to "/bin"), userLogin)
        } finally { directory.toFile().deleteRecursively() }
    }

    private fun fixture(auth: String = "Logged in using ChatGPT", exit: Int = 0): Pair<CodexCliProvider, java.nio.file.Path> {
        val directory = Files.createTempDirectory("fake-codex")
        val executable = directory.resolve("codex")
        Files.writeString(executable, """
            #!/bin/sh
            printf '%s\n' "${'$'}@" > all-arguments.txt
            while [ "${'$'}1" = -c ]; do shift 2; done
            if [ "${'$'}1" = login ]; then
              echo '$auth'
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
        val request = ExecutionRequest(id, UUID.randomUUID().toString(), "literal ${'$'}(touch unwanted)", "Ada")
        assertEquals(CodexCliProvider.Readiness.READY, provider.readiness())
        assertEquals(ProviderResult(true, "fixture result"), provider.execute(request))
        val args = Files.readAllLines(directory.resolve("arguments.txt"))
        assertTrue(args.containsAll(listOf("never", "forced_login_method=\"chatgpt\"", "--ignore-user-config", "--ephemeral", "read-only")))
        assertFalse(args.contains(request.prompt))
        assertTrue(Files.readString(directory.resolve("input.txt")).contains(request.prompt))
        assertFalse(Files.exists(directory.resolve("unwanted")))
        assertEquals("fixture result", Files.readString(directory.resolve("output/$id.txt")))
        assertFails { provider.execute(request) }
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
}
