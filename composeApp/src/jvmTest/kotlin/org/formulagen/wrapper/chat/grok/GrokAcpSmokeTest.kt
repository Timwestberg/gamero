package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.*
import org.junit.Assume.assumeTrue
import kotlin.test.*

/** Explicit real-binary check in a disposable home; authentication and prompts are not permitted. */
class GrokAcpSmokeTest {
    @Test fun officialBinaryInitializesAndRefusesASessionWithoutCredentials() {
        val executable = System.getenv("HOVEL_GROK_ACP_SMOKE")
        assumeTrue("Set HOVEL_GROK_ACP_SMOKE to an official Grok executable", executable != null)
        val root = Files.createTempDirectory("hovel-grok-acp-smoke")
        try {
            val path = Path.of(requireNotNull(executable))
            require(path.isAbsolute && Files.isExecutable(path))
            val builder = ProcessBuilder(path.toString(), "--no-auto-update", "agent", "--no-leader", "stdio")
                .directory(root.toFile()).apply {
                    environment().clear()
                    environment().putAll(mapOf("HOME" to root.toString(), "GROK_HOME" to root.toString(),
                        "PATH" to "/usr/bin:/bin", "GROK_DISABLE_AUTOUPDATER" to "1", "BROWSER" to "/usr/bin/false"))
                    for (brand in listOf("CURSOR", "CLAUDE")) for (feature in listOf("SKILLS", "RULES", "AGENTS", "MCPS", "HOOKS", "SESSIONS"))
                        environment()["GROK_${brand}_${feature}_ENABLED"] = "0"
                    environment()["GROK_CODEX_SESSIONS_ENABLED"] = "0"
                }
            val rpc = GrokAcp(builder, setOf("initialize", "session/new"))
            try {
                val handshake = grokHandshake(rpc.call("initialize", grokInitialize()))
                assertEquals(GrokAuthAvailability.SIGNED_OUT, handshake.auth)
                assertTrue(handshake.loadSession)
                val denied = assertFailsWith<GrokRequestRejected> {
                    rpc.call("session/new", buildJsonObject { put("cwd", root.toString()); putJsonArray("mcpServers") {} })
                }
                assertEquals(-32000, denied.code)
                println("GROK_ACP_SMOKE: initialized, signed-out session refused; no authentication or prompt sent")
            } finally { rpc.close(); rpc.awaitClosed() }
        } finally { root.toFile().deleteRecursively() }
    }
}
