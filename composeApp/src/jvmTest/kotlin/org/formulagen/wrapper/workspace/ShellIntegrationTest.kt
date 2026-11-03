package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * The real login shells, through [ShellLaunch], Hovel's startup files and [PtySessionHost], with a
 * throwaway HOME whose files move PATH around the way players' files do. Hovel's files sit in a
 * folder with a space, an apostrophe and a non-ASCII letter, which no shell may trip on. zsh and
 * bash are always on a Mac; fish runs when `HOVEL_TEST_FISH` names its executable.
 */
class ShellIntegrationTest {
    private val root: Path = createTempDirectory("hovel-shells")
    private val home: Path = Files.createDirectories(root.resolve("home"))
    private val folder: Path = Files.createDirectories(root.resolve("project"))
    private val first: Path = Files.createDirectories(root.resolve("hovel bin"))
    private val second: Path = Files.createDirectories(root.resolve("hovel-tools"))
    private val files: Path = ShellFiles.install(root.resolve("Björn's shell"))
    private val hosts = mutableListOf<PtySessionHost>()
    private val hovelFirst = "$first:$second:"

    init {
        // The player's own files. Each moves PATH, as Homebrew, fnm, mise or a hand-written export would.
        home.resolve(".zshenv").writeText("export PLAYER_ZSHENV=1\n")
        home.resolve(".zprofile").writeText("export PATH=\"\$PATH:/player/zprofile\"\n")
        home.resolve(".zshrc").writeText(
            "export PATH=\"/player/zshrc:\$PATH\"\n" +
                "typeset -A player_map; player_map[ok]=yes\n" +
                "player_precmd() { PATH=\"/player/precmd:\$PATH\" }\nprecmd_functions+=(player_precmd)\n",
        )
        home.resolve(".bash_profile").writeText("export PATH=\"/player/bash_profile:\$PATH\"\nPROMPT_COMMAND='PATH=\"/player/prompt:\$PATH\"'\n")
        Files.createDirectories(home.resolve(".config/fish"))
        home.resolve(".config/fish/config.fish").writeText("set -gx PATH /player/fish \$PATH\n")
    }

    @AfterTest fun cleanUp() {
        hosts.forEach { it.close() }
        root.toFile().deleteRecursively()
    }

    private class Tab(val session: Session, val viewer: CollectingViewer, val keys: Attachment) {
        fun type(line: String) = keys.input("$line\r".toByteArray())

        /** The PATH the shell reports after [before] at a prompt. The marker reaches the screen only through `%s`. */
        fun path(marker: String, before: String = ""): String {
            type("$before printf '%s=[%s]\\n' $marker \"\$PATH\"")
            return viewer.await(Regex("$marker=\\[([^\\]\\r\\n]*)\\]")).groupValues[1]
        }
    }

    private fun start(shell: String, launch: Launch = Launch.Shell): Tab {
        assumeTrue("Shell tests run on macOS", System.getProperty("os.name").contains("Mac"))
        assumeTrue("$shell is installed", Files.isExecutable(Path.of(shell)))
        val parent = mapOf("HOME" to home.toString(), "USER" to "player", "LOGNAME" to "player", "TMPDIR" to root.toString())
        val shellLaunch = ShellLaunch(shell, parent, files)
        val host = PtySessionHost { spec -> shellLaunch.command(spec.launch, listOf(first.toString(), second.toString())) }.also(hosts::add)
        val session = host.start(SessionSpec("p", "Shell", folder, launch, TermSize(200, 50)))
        val viewer = CollectingViewer()
        val keys = session.attach(viewer)
        session.attach(TerminalReplies(keys))
        return Tab(session, viewer, keys)
    }

    /**
     * Answers the queries a terminal answers, as JediTerm does in the app: primary device attributes
     * and the cursor position. fish 4 waits for them at startup and drops what is typed meanwhile.
     */
    private class TerminalReplies(private val keys: Attachment) : SessionViewer {
        override fun output(bytes: ByteArray, length: Int) {
            val text = String(bytes, 0, length, Charsets.ISO_8859_1)
            repeat(Regex("\u001b\\[0?c").findAll(text).count()) { keys.input("\u001b[?62;22c".toByteArray()) }
            repeat(Regex("\u001b\\[6n").findAll(text).count()) { keys.input("\u001b[1;1R".toByteArray()) }
        }
        override fun exited(code: Int) {}
    }

    @Test fun zshKeepsHovelsEntriesFirstAndThePlayersOwnSetup() {
        val tab = start("/bin/zsh")
        val path = tab.path("P1")
        assertTrue(path.startsWith(hovelFirst), path)
        assertTrue("/player/zshrc" in path && "/player/zprofile" in path && "/player/precmd" in path, path)
        // A command that puts something first wins only until the next prompt.
        assertTrue(tab.path("P2", "PATH=/late:\$PATH;").startsWith("/late:$hovelFirst"))
        assertTrue(tab.path("P3").startsWith(hovelFirst))
        tab.type("printf '%s=[%s] H=[%s] M=[%s] E=[%s] V=[%s]\\n' Z \"\${ZDOTDIR-unset}\" \"\$HISTFILE\" \"\${player_map[ok]}\" \"\$PLAYER_ZSHENV\" \"\${HOVEL_PATH_PREPEND-unset}\"")
        // ZDOTDIR is handed back, history stays the player's, their typeset survives, and Hovel's variable is gone.
        tab.viewer.await("Z=[unset] H=[$home/.zsh_history] M=[yes] E=[1] V=[unset]")
    }

    @Test fun zshLoadsThePlayersXdgFolderWhenTheirZshenvChoosesIt() {
        // The XDG idiom: ~/.zshenv picks ZDOTDIR only when it is unset, as it is in Terminal.
        home.resolve(".zshenv").writeText("export ZDOTDIR=\${ZDOTDIR:-\$HOME/.config/zsh}\n")
        home.resolve(".zshrc").writeText("export PLAYER_HOME_ZSHRC=1\n")
        val xdg = Files.createDirectories(home.resolve(".config/zsh"))
        xdg.resolve(".zprofile").writeText("export PLAYER_XDG_ZPROFILE=\"\$ZDOTDIR\"\n")
        xdg.resolve(".zshrc").writeText("export PATH=\"/player/xdg:\$PATH\"\ntypeset -A player_map; player_map[ok]=yes\n")
        xdg.resolve(".zlogin").writeText("export PLAYER_XDG_ZLOGIN=1\n")
        val tab = start("/bin/zsh")
        val path = tab.path("P1")
        assertTrue(path.startsWith(hovelFirst) && "/player/xdg" in path, path)
        tab.type(
            "printf '%s=[%s] H=[%s] P=[%s] L=[%s] M=[%s] R=[%s]\\n' Z \"\${ZDOTDIR-unset}\" \"\$HISTFILE\" \"\$PLAYER_XDG_ZPROFILE\" " +
                "\"\$PLAYER_XDG_ZLOGIN\" \"\${player_map[ok]}\" \"\${PLAYER_HOME_ZSHRC-unset}\"",
        )
        // Every later file comes from their folder, with ZDOTDIR set as they set it; ~/.zshrc is not read.
        tab.viewer.await("Z=[$xdg] H=[$xdg/.zsh_history] P=[$xdg] L=[1] M=[yes] R=[unset]")
    }

    @Test fun aToolTabRunsTheWrapperWithTheSameOrder() {
        val fake = first.resolve("nvim")
        fake.writeText("#!/bin/sh\nprintf '%s=[%s] ARG0=[%s]\\n' TOOL \"\$PATH\" \"\$0\"\n")
        Files.setPosixFilePermissions(fake, PosixFilePermissions.fromString("rwx------"))
        for (shell in listOf("/bin/zsh", "/bin/bash") + listOfNotNull(System.getenv("HOVEL_TEST_FISH"))) {
            val tab = start(shell, Launch.Tool(WorkspaceTool.NEOVIM))
            val match = tab.viewer.await(Regex("TOOL=\\[([^\\]]*)\\] ARG0=\\[([^\\]]*)\\]"))
            assertTrue(match.groupValues[1].startsWith(hovelFirst), "$shell: ${match.groupValues[1]}")
            assertEquals(fake.toString(), match.groupValues[2], shell)
        }
    }

    @Test fun bashEmulatesALoginShellAndKeepsHovelsEntriesFirst() {
        val tab = start("/bin/bash")
        val path = tab.path("P1")
        assertTrue(path.startsWith(hovelFirst), path)
        assertTrue("/player/bash_profile" in path && "/player/prompt" in path, path)
        assertTrue(tab.path("P2", "PATH=/late:\$PATH;").startsWith("/late:$hovelFirst"))
        assertTrue(tab.path("P3").startsWith(hovelFirst))
        tab.type("printf '%s=[%s]\\n' V \"\${HOVEL_PATH_PREPEND-unset}\"")
        tab.viewer.await("V=[unset]")
    }

    @Test fun fishKeepsHovelsEntriesFirst() {
        val fish = System.getenv("HOVEL_TEST_FISH")
        assumeTrue("HOVEL_TEST_FISH names a fish executable", fish != null)
        val tab = start(fish!!)
        tab.viewer.await("\u001b]133;B") // the end of fish's first prompt: its line editor is ready
        val path = tab.path("P1")
        assertTrue(path.startsWith(hovelFirst), path)
        assertTrue("/player/fish" in path, path)
        assertTrue(tab.path("P2", "set -gx PATH /late \$PATH;").startsWith("/late:$hovelFirst"))
        assertTrue(tab.path("P3").startsWith(hovelFirst))
    }
}
