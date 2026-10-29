package org.formulagen.wrapper.conversations

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.*
import org.formulagen.wrapper.bridge.*

class HostDirectoryTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun instant() = now
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
    }

    private class Counting(private val inner: ProviderCapability) : ProviderCapability by inner {
        var statuses = 0
        var usages = 0
        override suspend fun perform(action: ProviderAction, scope: ProviderScope) = inner.perform(action, scope).also { statuses++ }
        override suspend fun usage(scope: ProviderScope) = inner.usage(scope).also { usages++ }
    }

    @Test fun everyRuntimeIsListedWithItsReadinessAndOnlyReadyOnesWork() {
        val providers = Counting(ConnectedProviders(setOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE)))
        val clock = MutableClock(Instant.parse("2026-10-29T12:00:00Z"))
        val features = listOf("provider-input", "memory-context", "memory-extraction")
        val directory = HostDirectory(providers, id(), id(), offered = setOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE),
            working = setOf(RuntimeId.CLAUDE_CODE), features = features, clock = clock, directoryEvery = 60.seconds, usageEvery = 300.seconds)
        val beat = directory.heartbeat()
        assertEquals(features, beat.features)
        assertEquals("unavailable", beat.providerState, "ready runtimes, but no task lane: no task is queued here")
        assertEquals("2026-10-29T12:00:00Z", beat.directory.observedAt)
        assertEquals(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE, RuntimeId.GROK_BUILD), beat.directory.runtimes.map { it.runtimeId })
        assertEquals(listOf(ProviderReadiness.READY, ProviderReadiness.READY, ProviderReadiness.UNAVAILABLE), beat.directory.runtimes.map { it.readiness })
        assertEquals(listOf(false, true, false), beat.directory.runtimes.map { it.canWork })
        assertEquals(listOf("test-model"), beat.directory.runtimes.first().models.map { it.modelId })
        assertEquals(listOf(RuntimeId.CODEX, RuntimeId.CLAUDE_CODE), beat.allowances.map { it.runtimeId })
        // Readings are reused between their intervals.
        clock.now = clock.now.plusSeconds(30)
        assertEquals(beat, directory.heartbeat())
        assertEquals(2, providers.statuses)
        clock.now = clock.now.plusSeconds(31)
        directory.heartbeat()
        assertEquals(4, providers.statuses)
        assertEquals(2, providers.usages)
    }

    @Test fun aSignedOutRuntimeHasNoModelsAndNoUsage() {
        val providers = ConnectedProviders().apply { connected = false }
        val beat = HostDirectory(providers, id(), id(), setOf(RuntimeId.CODEX), setOf(RuntimeId.CODEX), listOf("memory-context")).heartbeat()
        val codex = beat.directory.runtimes.first()
        assertEquals(ProviderRuntime(false, emptyList(), ProviderReadiness.SIGNED_OUT, RuntimeId.CODEX), codex)
        assertEquals(emptyList(), beat.allowances)
        assertEquals("unavailable", beat.providerState)
    }

    @Test fun providerStateIsTheTaskLanesCodexAndUnavailableWithoutOne() {
        val ready = ConnectedProviders()
        var codex: String? = "auth-required"
        val directory = HostDirectory(ready, id(), id(), setOf(RuntimeId.CODEX), emptySet(), listOf("provider-input"),
            taskProviderState = { codex })
        val beat = directory.heartbeat()
        assertEquals("auth-required", beat.providerState, "the task lane's own Codex, though the directory's Codex is ready")
        assertEquals(ProviderReadiness.READY, beat.directory.runtimes.first { it.runtimeId == RuntimeId.CODEX }.readiness,
            "conversation readiness stays in the directory")
        codex = "ready"
        assertEquals("ready", directory.heartbeat().providerState)
        val noTasks = HostDirectory(ready, id(), id(), RuntimeId.entries.toSet(), RuntimeId.entries.toSet(), listOf("memory-context"))
        assertEquals("unavailable", noTasks.heartbeat().providerState, "a Mac without a task lane is never offered a task")
    }

    @Test fun usageIsAnAllowanceSnapshotWithAStableOpaqueAccount() {
        val host = id()
        val usage = ProviderUsage(RuntimeId.CLAUDE_CODE, 1_000, ProviderUsageWindow(12.5, 1_800_000_000), ProviderUsageWindow(40.0))
        val snapshot = allowanceSnapshot(usage, host)
        assertEquals(snapshot.getValue("account"), allowanceSnapshot(usage, host).getValue("account"))
        assertNotEquals(snapshot.getValue("account"), allowanceSnapshot(usage, id()).getValue("account"))
        assertEquals("anthropic", snapshot.getValue("account").jsonObject.getValue("providerId").jsonPrimitive.content)
        val buckets = snapshot.getValue("buckets").jsonArray.map { it.jsonObject }
        assertEquals(listOf("session", "weekly"), buckets.map { it.getValue("bucketId").jsonPrimitive.content })
        assertEquals(1_800_000_000_000, buckets[0].getValue("window").jsonObject.getValue("resetsAt").jsonPrimitive.long)
        assertEquals(JsonNull, buckets[1].getValue("window").jsonObject.getValue("resetsAt"))
        assertEquals("available", snapshot.getValue("state").jsonPrimitive.content)
        assertEquals(setOf("account", "buckets", "observedAt", "source", "staleAfterMs", "state", "version"), snapshot.keys)
        assertEquals("unknown", allowanceSnapshot(ProviderUsage(RuntimeId.CODEX, 5), host).getValue("state").jsonPrimitive.content)
    }
}
