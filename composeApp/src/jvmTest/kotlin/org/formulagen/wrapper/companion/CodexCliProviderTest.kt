package org.formulagen.wrapper.companion

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*
import org.junit.Test

class CodexCliProviderTest {
    private fun fixture(auth: String = "Logged in using ChatGPT", exit: Int = 0): Pair<CodexCliProvider, java.nio.file.Path> {
        val directory = Files.createTempDirectory("fake-codex")
        val executable = directory.resolve("codex")
        Files.writeString(executable, """
            #!/bin/sh
            if [ "${'$'}1" = login ]; then
              echo '$auth'
              exit 0
            fi
            printf '%s\n' "${'$'}@" > arguments.txt
            cat > input.txt
            while [ "${'$'}#" -gt 0 ]; do
              if [ "${'$'}1" = --output-last-message ]; then shift; printf 'fixture result' > "${'$'}1"; fi
              shift
            done
            exit $exit
        """.trimIndent())
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"))
        return CodexCliProvider(executable, directory, directory.resolve("output")) to directory
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

    @Test fun nonzeroProviderExitRemainsFailed() {
        val (provider, _) = fixture(exit = 4)
        assertFalse(provider.execute(ExecutionRequest(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "hello")).succeeded)
    }
}
