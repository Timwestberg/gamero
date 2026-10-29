package org.formulagen.wrapper.providers.grok

import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

/** Explicit real-binary check in a disposable home; authentication and prompts are not permitted. */
class GrokAcpSmokeTest {
    @Test fun officialBinaryInitializesAndRefusesASessionWithoutCredentials() {
        val path = GrokSmoke.executable()
        val root = Files.createTempDirectory("hovel-grok-acp-smoke")
        try {
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
