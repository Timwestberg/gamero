package org.formulagen.wrapper.conversations

import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.formulagen.wrapper.hovel.HovelHttpTransport
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Opt-in HTTP acceptance against the disposable contract-window server, never a provider runtime. */
class RobotContractLocalStackTest {
    @Test fun nativeClaimReadsTheLookSavedByTheWebEditor() {
        val path = System.getenv("HOVEL_ROBOT_CONTRACT_FIXTURE")
        assumeTrue("Set HOVEL_ROBOT_CONTRACT_FIXTURE to a disposable local-stack fixture", path != null)
        val fixture = Json.parseToJsonElement(Files.readString(Path.of(path!!))).jsonObject
        fun value(key: String) = fixture.getValue(key).jsonPrimitive.content
        val base = URI(value("base"))
        require(base.scheme == "http" && base.host == "127.0.0.1") { "Local disposable server only" }
        val transport = HovelHttpTransport(URI("${value("base")}/v1/hovel"), value("owner"), value("hostId"), value("hostToken"))
        val api = HttpConversationApi(transport, transport::openStream)
        val response = assertIs<ClaimResponse.Claimed>(api.claim(ClaimRequest(UUID.randomUUID().toString(), value("conversationId"))))
        assertEquals(value("companionId"), response.claim.companion.id)
        assertEquals(fixture.getValue("expectedLook"), response.claim.companion.look)
        println("CONTRACT4_NATIVE_REAL_SERVICE_CLAIM_OK")
    }
}
