package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.util.UUID
import kotlinx.serialization.json.*
import kotlin.test.*
import org.junit.Test
import org.formulagen.wrapper.companion.*

class BrainLocalAccountTest {
    private val host = UUID.randomUUID().toString()
    private val account = UUID.randomUUID().toString()
    private fun selection(generation: Long = 1) = buildJsonObject {
        put("hostId", host); put("runtimeId", "codex"); put("providerId", "openai")
        put("localAccountRef", account); put("accountGeneration", generation)
    }

    @Test fun everyLaunchRechecksFreshNativeIdentityAndGeneration() {
        var observation: BrainLocalObservation? = BrainLocalObservation(account, 1, 1, true, 1_000)
        var clock = 1_000L
        val verifier = BrainLocalAccountVerifier(host, BrainLocalAccountSource { observation }) { clock }
        verifier.verify(selection())
        observation = observation!!.copy(accountGeneration = 2, sequence = 2)
        assertFails { verifier.verify(selection()) }
        verifier.verify(selection(2))
        observation = observation!!.copy(localAccountRef = UUID.randomUUID().toString())
        assertFails { verifier.verify(selection(2)) }
        observation = BrainLocalObservation(account, 1, 3, false, 1_000)
        assertFails { verifier.verify(selection()) }
        observation = observation!!.copy(available = true)
        clock = 61_000
        assertFails { verifier.verify(selection()) }
        clock = 999
        assertFails { verifier.verify(selection()) }
        observation = null
        assertFails { verifier.verify(selection()) }
    }

    @Test fun observationPayloadContainsOnlyCurrentLocalPinsAndPreservesRetrySequence() {
        val observation = BrainLocalObservation(account, 4, 7, true, 1_000)
        val verifier = BrainLocalAccountVerifier(host, BrainLocalAccountSource { observation }) { 1_000 }
        val sent = mutableListOf<JsonObject>()
        val remote = BrainHovelRemote(HovelTransport { route, body ->
            assertEquals("brain/observation", route); sent += body
            buildJsonObject { put("status", if (sent.size == 1) "accepted" else "duplicate") }
        })
        verifier.publish(remote); verifier.publish(remote)
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
        assertEquals(setOf("available", "sequence", "local", "models"), sent[0].keys)
        assertEquals(setOf("runtimeId", "providerId", "localAccountRef", "accountGeneration"), sent[0].getValue("local").jsonObject.keys)
        assertEquals(7, sent[0].getValue("sequence").jsonPrimitive.int)
        assertEquals(0, sent[0].getValue("models").jsonArray.size)
    }

    @Test fun compositionDefaultsOffAndEnabledCompositionRequiresAnIdentitySource() {
        val directory = Files.createTempDirectory("brain-composition-")
        try {
            ExecutionJournal(directory.resolve("execution"), host).use { execution ->
                val provider = TaskProvider { error("No provider invocation") }
                val transport = HovelTransport { _, _ -> error("No transport invocation") }
                assertNull(BrainSessionOptions(accountSource = BrainLocalAccountSource { error("Must remain unused") })
                    .attach(host, directory, execution, provider, transport))
                assertFails { BrainSessionOptions(enabled = true).attach(host, directory, execution, provider, transport) }
                assertFalse(Files.exists(directory.resolve("presentation-$host")))
            }
        } finally { directory.toFile().deleteRecursively() }
    }
}
