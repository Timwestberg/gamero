package org.formulagen.wrapper.companion

import java.util.UUID
import java.util.concurrent.TimeUnit

interface HostCredentials {
    fun save(hostId: String, token: String)
    fun read(hostId: String): String
}

/** The OS owns persistence/access prompts. No keychain ACL broadening or secret argv. */
class MacHostCredentials : HostCredentials {
    private val service = "org.formulagen.gamero.host"
    private fun validate(hostId: String) { require(UUID.fromString(hostId).toString() == hostId) }
    override fun save(hostId: String, token: String) {
        validate(hostId)
        require(token.matches(Regex("[0-9a-f]{64}")))
        check(System.getProperty("os.name").contains("Mac"))
        val process = ProcessBuilder("/usr/bin/security", "-i")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            // All interpolated values are fixed literals or strictly validated UUID/hex.
            process.outputStream.bufferedWriter().use {
                it.write("add-generic-password -s $service -a $hostId -w $token\n")
            }
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "Keychain registration was not approved" }
            check(read(hostId) == token) { "Keychain did not retain the host credential" }
        } finally { terminateProcessTree(process) }
    }
    override fun read(hostId: String): String {
        validate(hostId)
        check(System.getProperty("os.name").contains("Mac"))
        val process = ProcessBuilder("/usr/bin/security", "find-generic-password", "-s", service, "-a", hostId, "-w")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        try {
            check(process.waitFor(60, TimeUnit.SECONDS) && process.exitValue() == 0) { "Host credential unavailable in Keychain" }
            val value = process.inputStream.readNBytes(256).toString(Charsets.UTF_8).trim()
            check(value.matches(Regex("[0-9a-f]{64}"))) { "Invalid host credential" }
            return value
        } finally { terminateProcessTree(process) }
    }
}
