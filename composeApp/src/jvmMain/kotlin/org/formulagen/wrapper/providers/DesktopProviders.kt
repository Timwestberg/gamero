package org.formulagen.wrapper.providers

import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.formulagen.wrapper.bridge.ProviderSetup
import org.formulagen.wrapper.bridge.ProviderSetupState
import org.formulagen.wrapper.bridge.ProviderLogin
import org.formulagen.wrapper.bridge.ProviderAction
import org.formulagen.wrapper.bridge.ProviderCapability
import org.formulagen.wrapper.bridge.ProviderList
import org.formulagen.wrapper.bridge.ProviderModels
import org.formulagen.wrapper.bridge.ProviderScope
import org.formulagen.wrapper.bridge.ProviderStatus
import org.formulagen.wrapper.bridge.ProviderUsage
import org.formulagen.wrapper.bridge.RuntimeId
import org.formulagen.wrapper.bridge.HovelContract
import org.formulagen.wrapper.closeInReverse

/**
 * The `providers.*` capability, routed by runtime with credentials kept off the bridge. [runtimes]
 * are the runtimes this build offers; one gated off is absent, so it has no card and every call for
 * it answers "not offered". [working] are those it lets work in a project folder, by the same
 * answer conversations run on. [pageContract] is the contract the page sent in `host.contract`
 * (null before it did); [list] leaves out a runtime newer than it. Each runtime runs one call at a time.
 */
internal class DesktopProviders(runtimes: List<RuntimeProvider>, private val working: Set<RuntimeId> = emptySet(),
    private val installation: ProviderInstallation? = null, private val pageContract: () -> Int? = { null },
) : ProviderCapability, AutoCloseable {
    private val runtimes = runtimes.associateBy { it.id }
    private val locks = runtimes.associate { it.id to Mutex() }
    @Volatile private var closed = false

    override suspend fun perform(action: ProviderAction, scope: ProviderScope): ProviderStatus {
        val runtime = runtimes[scope.runtimeId] ?: return notOfferedStatus(scope.runtimeId)
        return locks.getValue(runtime.id).withLock { check(!closed); runInterruptible { runtime.status(action) } }
    }

    override suspend fun setup(scope: ProviderScope): ProviderSetup {
        val lock = locks[scope.runtimeId] ?: return ProviderSetup(scope.runtimeId, ProviderSetupState.UNAVAILABLE)
        return lock.withLock {
            check(!closed)
            runInterruptible { installation?.setup(scope.runtimeId) ?: ProviderSetup(scope.runtimeId, ProviderSetupState.UNAVAILABLE) }
        }
    }

    override suspend fun startLogin(scope: ProviderScope): ProviderLogin {
        val lock = locks[scope.runtimeId] ?: return ProviderLogin(scope.runtimeId, false)
        return lock.withLock {
            check(!closed)
            runInterruptible { ProviderLogin(scope.runtimeId, installation?.startLogin(scope.runtimeId) == true) }
        }
    }

    /**
     * The offered runtimes the page's contract can render, in the contract's order, and those of them that may work. A page
     * that has not sent its contract is read as the first one.
     */
    override suspend fun list(): ProviderList {
        val offered = HovelContract.runtimesFor(pageContract() ?: HovelContract.FIRST).filter { it in runtimes }
        return ProviderList(offered, offered.filter { it in working })
    }

    /** Nothing known is an empty list, never an error: the page still offers the runtime's default. */
    override suspend fun models(scope: ProviderScope): ProviderModels {
        val runtime = runtimes[scope.runtimeId] ?: return ProviderModels(emptyList(), scope.runtimeId)
        val models = locks.getValue(runtime.id).withLock {
            check(!closed)
            try { runInterruptible { runtime.models() } }
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

    /** Provider errors and older protocols leave quotas unknown; cancellation still stops the child. */
    override suspend fun usage(scope: ProviderScope): ProviderUsage {
        val runtime = runtimes[scope.runtimeId] ?: return ProviderUsage(scope.runtimeId, System.currentTimeMillis())
        return locks.getValue(runtime.id).withLock {
            check(!closed)
            try { runInterruptible { runtime.usage() } }
            catch (error: Exception) {
                if (error is InterruptedException || error is kotlinx.coroutines.CancellationException) throw error
                ProviderUsage(scope.runtimeId, System.currentTimeMillis())
            }
        }
    }
}
