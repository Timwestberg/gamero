package org.formulagen.wrapper.providers

import java.nio.file.Path
import java.time.Clock
import org.formulagen.wrapper.providers.grok.GROK_ACCOUNT_METHODS
import org.formulagen.wrapper.providers.grok.GrokAccount
import org.formulagen.wrapper.providers.grok.GrokAcp
import org.formulagen.wrapper.providers.grok.GrokAuthAvailability
import org.formulagen.wrapper.providers.grok.GrokBudget
import org.formulagen.wrapper.providers.grok.GrokLaunch
import org.formulagen.wrapper.providers.grok.findGrokExecutable
import org.formulagen.wrapper.providers.grok.grokAccount
import org.formulagen.wrapper.providers.grok.grokHandshake
import org.formulagen.wrapper.providers.grok.grokInitialize

/** One short-lived account check shared by the provider card and conversation readiness. */
internal data class GrokAccess(val executable: Path?, val auth: GrokAuthAvailability, val account: GrokAccount? = null)

/** Subscription checks never create a session, send a prompt, or invoke an interactive auth method. */
internal class GrokConnection(
    private val environment: Map<String, String>,
    private val clock: Clock = Clock.systemUTC(),
    private val probe: (Path) -> GrokAccess = { readGrokAccess(it, environment) },
) {
    private var cached: Pair<Long, GrokAccess>? = null

    /** The caller can bypass the five-second cache after login or before starting a conversation. */
    @Synchronized fun check(fresh: Boolean = false): GrokAccess {
        val executable = findGrokExecutable(environment)
        val now = clock.millis()
        cached?.takeIf { !fresh && now - it.first in 0 until 5_000 && it.second.executable == executable }?.let { return it.second }
        val access = if (executable == null) GrokAccess(null, GrokAuthAvailability.UNKNOWN) else probe(executable)
        cached = now to access
        return access
    }
}

/** Keep detected sign-in distinct when authentication, policy, catalog or isolation cannot be verified. */
private fun readGrokAccess(executable: Path, environment: Map<String, String>): GrokAccess {
    var auth = GrokAuthAvailability.UNKNOWN
    return try {
        val budget = GrokBudget()
        val launch = GrokLaunch.create(executable, environment, "Hovel account check. No prompts are sent.")
        var rpc: GrokAcp? = null
        try {
            launch.verify(budget.remaining())
            val transport = GrokAcp(launch.agentBuilder(), GROK_ACCOUNT_METHODS, allowReloadResponses = true)
            rpc = transport
            val initialized = transport.call("initialize", grokInitialize(), budget.remaining())
            auth = grokHandshake(initialized).auth
            launch.checkPolicies()
            if (auth != GrokAuthAvailability.CACHED_CREDENTIAL) GrokAccess(executable, auth)
            else GrokAccess(executable, auth, grokAccount(transport, initialized, budget)).also { launch.checkPolicies() }
        } finally { launch.closeAfter(rpc) }
    } catch (error: Exception) {
        if (error is InterruptedException || Thread.currentThread().isInterrupted) throw InterruptedException()
        GrokAccess(executable, auth)
    }
}
