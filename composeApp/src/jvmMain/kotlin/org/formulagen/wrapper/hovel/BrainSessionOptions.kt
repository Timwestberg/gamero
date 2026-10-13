package org.formulagen.wrapper.hovel

import java.nio.file.Path
import org.formulagen.wrapper.companion.*

/** Native composition only. No web method, environment flag or login-status probe supplies trusted identity. */
data class BrainSessionOptions(
    val enabled: Boolean = false,
    val accountSource: BrainLocalAccountSource? = null,
) {
    internal fun attach(hostId: String, directory: Path, execution: ExecutionJournal,
                        provider: TaskProvider, transport: HovelTransport): BrainHostWork? {
        if (!enabled) return null
        val source = requireNotNull(accountSource) { "Brain requires an approved local identity adapter" }
        val remote = BrainHovelRemote(transport)
        val verifier = BrainLocalAccountVerifier(hostId, source)
        val presentation = ExecutionJournal(directory.resolve("presentation-$hostId"), hostId)
        try {
            return BrainHostWork(hostId, execution, presentation, provider, remote,
                { verifier.publish(remote) }, verifier::verify)
        } catch (error: Exception) { presentation.close(); throw error }
    }
}
