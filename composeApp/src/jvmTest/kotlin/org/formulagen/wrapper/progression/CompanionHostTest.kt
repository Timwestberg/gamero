package org.formulagen.wrapper.progression

import java.nio.file.Files
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlin.test.*
import org.formulagen.wrapper.bridge.GameId
import org.formulagen.wrapper.progression.embedded.desktopGame
import org.junit.Assume.assumeTrue

class CompanionHostTest {
    /** Closing never waits on the caller's thread, and a game that ignores SIGTERM is still killed. */
    @Test fun closeStopsAGameThatIgnoresTermination() = runBlocking {
        assumeTrue(!System.getProperty("os.name").contains("Windows"))
        val project = Files.createTempDirectory("hovel-stubborn-game")
        Files.createDirectories(project.resolve("scripts"))
        project.resolve("scripts/godot.sh").toFile().apply {
            writeText("#!/bin/sh\ntrap '' TERM\necho \$\$ > \"\$HOVEL_PRESENTATION_SESSION.pid\"\nexec sleep 60\n")
            setExecutable(true)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = DesktopCompanionHost(GodotGame.companions(project), scope, PresentationController { "stop" }, emptyMap())
        try {
            host.start()
            val pid = withTimeout(5000) {
                while (Files.list(project).use { files -> files.noneMatch { it.toString().endsWith(".pid") } }) delay(20)
                Files.list(project).use { files -> files.filter { it.toString().endsWith(".pid") }.findFirst().get() }
            }
            delay(100) // let the TERM disposition install before close signals it
            val game = ProcessHandle.of(Files.readString(pid).trim().toLong()).get()
            val started = System.nanoTime()
            host.close()
            assertTrue(System.nanoTime() - started < 1_000_000_000L, "close() must not wait for the game")
            withTimeout(5000) { while (game.isAlive) delay(20) }
        } finally { host.close(); scope.cancel(); project.toFile().deleteRecursively() }
    }

    /**
     * A game without companion screens runs through its engine script in its own project and scene, and gets no
     * presentation socket, so it can never connect.
     */
    @Test fun aGameWithoutPresentationGetsNoSocket() = runBlocking {
        assumeTrue(!System.getProperty("os.name").contains("Windows"))
        val root = Files.createTempDirectory("hovel-plain-game")
        val engine = root.resolve("engine.sh")
        val record = root.resolve("record.txt")
        engine.toFile().apply {
            writeText("#!/bin/sh\n{ echo \"args=\$*\"; echo \"port=\${HOVEL_PRESENTATION_PORT:-none}\"; echo \"session=\${HOVEL_PRESENTATION_SESSION:-none}\"; echo \"pwd=\$(pwd -P)\"; } > \"$record.tmp\"\nmv \"$record.tmp\" \"$record\"\n")
            setExecutable(true)
        }
        val project = Files.createDirectories(root.resolve("ember-overclock"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val host = DesktopCompanionHost(GodotGame(engine, project, "res://game/main.tscn", presentation = false), scope,
            PresentationController { "plain" }, emptyMap(), listOf("--embedded"))
        try {
            host.start()
            assertEquals(0, withTimeout(5000) { host.exitCode.first { it != null } })
            assertEquals(listOf("args=--embedded --path $project res://game/main.tscn", "port=none", "session=none",
                "pwd=${project.toRealPath()}"), Files.readAllLines(record))
            assertFalse(host.connected.value)
        } finally { host.close(); scope.cancel(); root.toFile().deleteRecursively() }
    }

    /** Earn XP in the real scene, confirm in Kotlin and verify the saved receipt. */
    @Test fun realGodotRoundTrip() = roundTrip(false)

    /** A failed save followed by socket loss can retry the identical request safely. */
    @Test fun saveFailureAndReconnect() = roundTrip(true)

    /** Share the real launch flow while varying the injected storage failure. */
    @Test fun reviewAllocationReturnsToSameReceipt() = roundTrip(false, true)

    /** Exercise the desktop adapter with real saved offers and receipts. */
    private fun roundTrip(recover: Boolean, reviewFirst: Boolean = false) = runBlocking {
        val game = desktopGame(System.getenv(), GameId.HOVEL_COMPANIONS)
        assumeTrue("Set HOVEL_GAMES_ROOT to run the actual Godot integration", game != null)
        val gameProject = requireNotNull(game)
        val save = Files.createTempDirectory("hovel-roundtrip-").resolve("save.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val controller = PresentationController { "roundtrip-${System.nanoTime()}" }
        val host = DesktopCompanionHost(gameProject, scope, controller, System.getenv() + mapOf(
            "HOVEL_PRESENTATION_RECOVERY" to if (recover) "1" else "0",
            "HOVEL_PRESENTATION_REVIEW_FIRST" to if (reviewFirst) "1" else "0",
            "HOVEL_COMPANION_SAVE" to save.toString(), "HOVEL_PRESENTATION_HEADLESS" to "1",
            "HOVEL_PRESENTATION_SCENARIO" to "res://companions/tests/presentation_scenario.gd"))
        try {
            withContext(Dispatchers.Main) { host.start() }
            if (reviewFirst) {
                val initial = withTimeout(25000) { controller.state.first { it.snapshot?.view == "review" } }
                assertEquals("run-1", initial.snapshot?.review?.id)
                assertEquals(1, initial.snapshot?.pending)
                withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("upgrades"))) }
            }
            val growth = withTimeout(25000) { controller.state.first { it.snapshot?.view == "growth" && it.pending == null } }
            assertEquals("60", growth.snapshot?.offer?.options?.find { it.id == "energy" }?.before)
            withContext(Dispatchers.Main) {
                controller.select("energy")
                val command = assertNotNull(controller.begin("choose"))
                host.send(command)
                if (!recover) host.send(command)
            }
            if (recover) {
                withTimeout(10000) { controller.state.first { it.error != null } }
                withTimeout(10000) { host.connected.first { !it } }
                withTimeout(10000) { host.connected.first { it } }
                withContext(Dispatchers.Main) { host.send(assertNotNull(controller.retry())) }
            }
            val saved = withTimeout(10000) { controller.state.first { it.snapshot?.view == "saved" && it.pending == null } }
            assertEquals("63", saved.snapshot?.appliedOption?.after)
            withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("continue"))) }
            val review = withTimeout(15000) { controller.state.first { it.snapshot?.view == "review" && it.pending == null } }
            assertEquals(100L, review.snapshot?.review?.xp)
            assertEquals("run-1", review.snapshot?.review?.id)
            assertEquals(0, review.snapshot?.pending)
            assertEquals(listOf("FOUNDRY LANCE"), review.snapshot?.review?.lost)
            assertEquals(listOf("ARC RIFLE"), review.snapshot?.review?.retained)
            withContext(Dispatchers.Main) { host.send(assertNotNull(controller.begin("close"))) }
            assertEquals(0, withTimeout(10000) { host.exitCode.first { it != null } })
            val persisted = Files.readString(save)
            assertTrue(persisted.contains("energy"))
            assertTrue(persisted.contains("FOUNDRY LANCE"))
        } finally { host.close(); scope.cancel() }
    }
}
