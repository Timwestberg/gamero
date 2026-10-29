package org.formulagen.wrapper.providers.grok

import java.nio.file.Path
import kotlin.time.Duration
import kotlinx.serialization.json.*
import org.formulagen.wrapper.process.runBounded

/**
 * How one Grok agent process launches and stays acceptable: an explicit agent profile, run with exactly this
 * launch's environment. [GrokLaunch] isolates talk; [GrokWorkLaunch] works in the player's own setup.
 * Sessions depend only on this surface.
 */
internal sealed class GrokLaunchPolicy(
    private val executable: Path,
    private val environment: Map<String, String>,
    /** The agent's working folder, which its sessions also send as `cwd`. */
    val workspace: Path,
    /** This launch's agent profile; the launch removes it on [close]. */
    protected val profile: Path,
) : AutoCloseable {
    /**
     * The accepted release, then the runtime's own inspection of this launch, within [timeout]. False when
     * Grok does not trust [workspace] yet; only a work launch reports that.
     */
    abstract fun verify(timeout: Duration): Boolean

    /** The cheap check of this launch's accepted configuration, repeated between session startup steps. */
    abstract fun recheck()

    /** A local ACP process with this launch's explicit profile, and no shared leader, subagents or web search. */
    fun agentBuilder(): ProcessBuilder = builder(listOf("--no-auto-update", "--no-subagents", "--disable-web-search",
        "agent", "--no-leader", "--agent-profile", profile.toString(), "stdio"))

    /** The executable in [workspace] with exactly this launch's environment: nothing else is inherited. */
    protected fun builder(arguments: List<String>): ProcessBuilder = ProcessBuilder(listOf(executable.toString()) + arguments)
        .directory(workspace.toFile()).apply { environment().clear(); environment().putAll(environment) }

    /** `grok --version` must print the accepted release before `inspect --json` runs; both share [budget]. */
    protected fun inspect(budget: GrokBudget): JsonObject {
        val version = runBounded(builder(listOf("--version")), budget.remaining(), 256)
        check(version?.exitCode == 0 && grokVersionSupported(version.output)) { "Untested Grok version" }
        val inspected = runBounded(builder(listOf("--no-auto-update", "inspect", "--json")), budget.remaining())
        check(inspected?.exitCode == 0) { "Grok inspection unavailable" }
        return Json.parseToJsonElement(inspected.output).jsonObject
    }

    /** Close and reap [rpc] before removing this launch's files; an interrupted caller still waits, then keeps its interrupt. */
    fun closeAfter(rpc: GrokAcp?) {
        val interrupted = Thread.interrupted()
        try { rpc?.close(); rpc?.awaitClosed(); close() }
        finally { if (interrupted) Thread.currentThread().interrupt() }
    }
}
