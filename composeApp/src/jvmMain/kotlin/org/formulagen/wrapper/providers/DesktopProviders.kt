package org.formulagen.wrapper.providers

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderList
import org.formulagen.wrapper.bridge.ProviderModels
import org.formulagen.wrapper.bridge.ProviderScope
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.SharedRules
import org.formulagen.wrapper.closeInReverse

/**
 * The `providers.*` capability, routed by runtime with credentials kept off the bridge. [runtimes]
 * are the runtimes this build offers; one gated off is absent, so it has no card and every call for
 * it answers "not offered". Each runtime runs one call at a time.
 */
internal class DesktopProviders(runtimes: List<RuntimeProvider>) : ProviderCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val locks = runtimes.associate { it.id to Mutex() }
    @Volatile private var closed = false

    /** The router has validated the scope; the runtime resolves the profile's folder before starting anything. */
    override suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus {
        require(SharedRules.isProfileId(scope.profile)) { "Invalid profile" }
        val runtime = runtimes[scope.runtimeId] ?: return notOfferedStatus(scope.runtimeId)
        return locks.getValue(runtime.id).withLock { check(!closed); runInterruptible { runtime.status(scope.profile, action) } }
    }

    /** The offered runtimes, in the contract's order. */
    override suspend fun list(profile: String): ProviderList {
        require(SharedRules.isProfileId(profile)) { "Invalid profile" }
        return ProviderList(RuntimeId.entries.filter { it in runtimes })
    }

    /** Nothing known is an empty list, never an error: the page still offers the runtime's default. */
    override suspend fun models(scope: ProviderScope): ProviderModels {
        require(SharedRules.isProfileId(scope.profile)) { "Invalid profile" }
        val runtime = runtimes[scope.runtimeId] ?: return ProviderModels(emptyList(), scope.runtimeId)
        val models = locks.getValue(runtime.id).withLock {
            check(!closed)
            try { runInterruptible { runtime.models(scope.profile) } }
            catch (error: Exception) { if (error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error; emptyList() }
        }
        return ProviderModels(models, scope.runtimeId)
    }

    /** Close the owned account processes after the native bridge has stopped taking requests. */
    override fun close() = kotlinx.coroutines.runBlocking<Unit> {
        closed = true
        val held = locks.values.onEach { it.lock() }
        try { closeInReverse(runtimes.values.toList()) } finally { held.forEach { it.unlock() } }
    }
}
