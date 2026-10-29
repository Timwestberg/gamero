package org.formulagen.wrapper.chat.grok

import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.providers.grok.GrokBudget
import org.formulagen.wrapper.providers.grok.GrokWorkLaunch

/** One project turn and its process, including persisted-session restoration before live routing begins. */
internal class GrokWorkSession private constructor(launch: GrokWorkLaunch, opened: GrokOpenedSession) :
    GrokSession(launch, opened, promptTimeout = 600.seconds) {

    /** Recheck the original configuration and Grok's folder trust before accepting any project request. */
    override fun revalidate(budget: GrokBudget) {
        if (!launch.verify(budget.remaining())) throw GrokWorkTrustDeclined()
    }

    companion object {
        /** No prompt or permission can run during startup. The caller supplies only the active turn's router. */
        fun open(executable: Path, environment: Map<String, String>, workspace: Path, instructions: String,
            model: String, expectedAccount: String, resume: String?, onUpdate: (JsonObject) -> Unit,
            confirmTrust: (selected: Path, root: Path) -> Boolean, loginPath: String? = null,
            onPermission: (JsonObject, (JsonObject?) -> Unit) -> Unit): GrokWorkSession {
            val launch = GrokWorkLaunch.create(executable, environment, workspace, instructions, loginPath)
            val opened = openGrokSession(launch, GrokToolEvidence(GROK_WORK_TOOLS), model, expectedAccount, resume,
                onUpdate, onPermission) {
                if (!it.verify(20.seconds)) trust(it, confirmTrust)
                GrokBudget(30.seconds)
            }
            return GrokWorkSession(launch, opened)
        }

        /** Grok does not trust the project yet: never offer a root covering home, ask, then require exactly that root. */
        private fun trust(launch: GrokWorkLaunch, confirmTrust: (selected: Path, root: Path) -> Boolean) {
            val root = launch.trustFolder
            if (launch.trustTooBroad) throw GrokWorkTrustTooBroad()
            if (!confirmTrust(launch.workspace, root)) throw GrokWorkTrustDeclined()
            launch.trust(root, 20.seconds)
        }
    }
}

/** The work profile's exact tool allowlist, as Grok reports the effective tools. */
private val GROK_WORK_TOOLS = setOf("read_file", "list_dir", "grep", "search_replace", "run_terminal_cmd")
