package org.formulagen.wrapper.runtimes.grok

import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GrokBudget
import org.formulagen.wrapper.providers.grok.GrokLaunch

/** One authenticated isolated session. Creation never sends a prompt; every prompt rechecks the account. */
internal class GrokTalkSession private constructor(launch: GrokLaunch, opened: GrokOpenedSession) :
    GrokSession<GrokLaunch>(launch, opened, promptTimeout = 90.seconds) {

    /** Hovel wrote this session's configuration itself; only the managed-policy check repeats. */
    override fun revalidate(budget: GrokBudget) = launch.recheck()

    companion object {
        /** Open only after supported-version, discovery, subscription and effective-tool checks all pass. */
        fun open(executable: Path, environment: Map<String, String>, instructions: String, model: String? = null,
            timeout: Duration = 20.seconds, persistentFolder: Path? = null, resume: String? = null,
            expectedAccount: String? = null, onUpdate: (JsonObject) -> Unit = {}): GrokTalkSession {
            val budget = GrokBudget(timeout)
            val launch = GrokLaunch.create(executable, environment, instructions, persistentFolder = persistentFolder)
            // An isolated talk launch is never untrusted; one deadline covers its inspection and the ACP startup.
            val opened = openGrokSession(launch, GrokToolEvidence(), model, expectedAccount, resume, onUpdate) {
                launch.verify(budget.remaining()); budget
            }
            return GrokTalkSession(launch, opened)
        }
    }
}
