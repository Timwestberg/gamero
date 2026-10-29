package org.formulagen.wrapper.chat.grok

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import kotlin.test.*

/** Private-storage integration checks, using disposable roots only. */
class GrokSessionsTest {
    private val companion = "11111111-1111-4111-8111-111111111111"
    private val scope = GrokSessionScope("local", companion, GrokMode.TALK, "grok-model", "a".repeat(64))

    @Test fun opaqueProviderIdsRoundTripThroughPrivateUuidHandles() {
        val root = Files.createTempDirectory("grok-bindings")
        try {
            val store = GrokSessions(root)
            val binding = store.create("provider/session:opaque", scope)

            assertEquals(binding.handle, UUID.fromString(binding.handle).toString())
            assertEquals(GrokSessionLookup.Found(binding), GrokSessions(root).find(binding.handle, scope))
            assertEquals(setOf("${binding.handle}.json"), Files.list(root).use { it.map { file -> file.fileName.toString() }.toList().toSet() })
            assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(root.resolve("${binding.handle}.json")))
            assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(root))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun noVerifiedAccountIdentityMeansNoPersistedResume() {
        val root = Files.createTempDirectory("grok-local-binding")
        try {
            val store = GrokSessions(root)
            val transient = scope.copy(accountFingerprint = null)
            val binding = store.create("ephemeral/session", transient)

            assertEquals(GrokSessionLookup.Found(binding), store.find(binding.handle, transient))
            assertEquals(0, Files.list(root).use { it.count() }.toInt())
            assertEquals(GrokSessionLookup.Missing, GrokSessions(root).find(binding.handle, transient))
            store.clearLocal()
            assertEquals(GrokSessionLookup.Missing, store.find(binding.handle, transient))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun accountProfileCompanionAndModelChangesCannotSelectTheSavedSession() {
        val root = Files.createTempDirectory("grok-binding-owner")
        try {
            val binding = GrokSessions(root).create("provider/owned", scope)
            val store = GrokSessions(root)
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, scope.copy(accountFingerprint = "b".repeat(64))))
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, scope.copy(profile = companion)))
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, scope.copy(companionId = "22222222-2222-4222-8222-222222222222")))
            assertEquals(GrokSessionLookup.ModelChanged, store.find(binding.handle, scope.copy(resolvedModel = "other-model")))
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, scope.copy(accountFingerprint = null)))
            assertFailsWith<IllegalArgumentException> { store.create("invalid-default", scope.copy(resolvedModel = "runtime-default")) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun workBindingsRequireTheExactWorkspaceAndCannotBeResumedAsTalk() {
        val root = Files.createTempDirectory("grok-binding-work")
        try {
            val work = scope.copy(mode = GrokMode.WORK, workspaceFingerprint = "c".repeat(64))
            val binding = GrokSessions(root).create("work/session", work)
            val store = GrokSessions(root)
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, work.copy(workspaceFingerprint = "d".repeat(64))))
            assertEquals(GrokSessionLookup.Mismatch, store.find(binding.handle, scope))
            assertFailsWith<IllegalArgumentException> { store.create("invalid", work.copy(workspaceFingerprint = null)) }
            assertFailsWith<IllegalArgumentException> { store.create("invalid", scope.copy(workspaceFingerprint = "c".repeat(64))) }
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun futureCorruptAndMisdirectedSidecarsRemainUntouched() {
        val root = Files.createTempDirectory("grok-binding-future")
        try {
            val handle = UUID.randomUUID().toString()
            val file = root.resolve("$handle.json")
            val future = """{"version":2,"scope":{"mode":"future-mode"}}"""
            Files.writeString(file, future)
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find(handle, scope))
            assertEquals(future, Files.readString(file))
            Files.writeString(file, "not json")
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find(handle, scope))
            assertEquals("not json", Files.readString(file))
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find("../../other", scope))

            val binding = GrokSessions(root).create("legitimate", scope)
            Files.copy(root.resolve("${binding.handle}.json"), file, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find(handle, scope))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun oversizedAndSymlinkSidecarsAreNotFollowed() {
        val root = Files.createTempDirectory("grok-binding-invalid")
        try {
            val handle = UUID.randomUUID().toString()
            val file = root.resolve("$handle.json")
            Files.writeString(file, "x".repeat(16_385))
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find(handle, scope))
            Files.delete(file)
            val target = root.resolve("other.json")
            Files.writeString(target, "private sentinel")
            Files.createSymbolicLink(file, target)
            assertEquals(GrokSessionLookup.Unavailable, GrokSessions(root).find(handle, scope))
            assertEquals("private sentinel", Files.readString(target))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cleanupRemovesOnlyUnreferencedBindingsOfTheRequestedProfile() {
        val root = Files.createTempDirectory("grok-prune")
        try {
            val store = GrokSessions(root)
            val kept = store.create("saved-or-active", scope)
            val orphan = store.create("orphan", scope)
            val transient = store.create("transient", scope.copy(accountFingerprint = null))
            val foreignScope = scope.copy(profile = companion)
            val foreign = store.create("foreign", foreignScope)

            assertEquals(GrokPruneResult(2, true), store.prune("local", setOf(kept.handle)))

            assertEquals(GrokSessionLookup.Missing, store.find(orphan.handle, scope))
            assertEquals(GrokSessionLookup.Missing, store.find(transient.handle, transient.scope))
            assertEquals(GrokSessionLookup.Found(kept), store.find(kept.handle, scope))
            assertEquals(GrokSessionLookup.Found(foreign), store.find(foreign.handle, foreignScope))
            assertEquals(GrokPruneResult(0, true), store.prune("local", setOf(kept.handle)))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun cleanupPreservesUnreadableFutureAndSymlinkFiles() {
        val root = Files.createTempDirectory("grok-prune-unknown")
        try {
            val future = root.resolve("${UUID.randomUUID()}.json")
            val corrupt = root.resolve("${UUID.randomUUID()}.json")
            val link = root.resolve("${UUID.randomUUID()}.json")
            Files.writeString(future, """{"version":2,"scope":{"mode":"future"}}""")
            Files.writeString(corrupt, "not json")
            Files.createSymbolicLink(link, future)
            val store = GrokSessions(root)
            store.create("known-orphan", scope)

            assertEquals(GrokPruneResult(1, true), store.prune("local", emptySet()))

            assertEquals("""{"version":2,"scope":{"mode":"future"}}""", Files.readString(future))
            assertEquals("not json", Files.readString(corrupt))
            assertTrue(Files.isSymbolicLink(link))
        } finally { root.toFile().deleteRecursively() }
    }

    @Test fun incompleteCleanupSnapshotDeletesNothing() {
        val root = Files.createTempDirectory("grok-prune-bound")
        try {
            val store = GrokSessions(root)
            val one = store.create("one", scope)
            val two = store.create("two", scope)

            assertEquals(GrokPruneResult(0, false), store.prune("local", emptySet(), limit = 1))

            assertEquals(GrokSessionLookup.Found(one), GrokSessions(root).find(one.handle, scope))
            assertEquals(GrokSessionLookup.Found(two), GrokSessions(root).find(two.handle, scope))
            assertFailsWith<IllegalArgumentException> { store.prune("local", setOf("../invalid")) }
        } finally { root.toFile().deleteRecursively() }
    }
}
