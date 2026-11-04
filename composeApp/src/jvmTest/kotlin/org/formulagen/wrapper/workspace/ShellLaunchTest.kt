package org.formulagen.wrapper.workspace

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShellLaunchTest {
    private val root: Path = createTempDirectory("hovel-shell-launch")
    private val files: Path = root.resolve("shell")
    private val bin: Path = Files.createDirectories(root.resolve("hovel bin"))
    private val parent = mapOf(
        "HOME" to "/Users/player",
        "USER" to "player",
        "LOGNAME" to "player",
        "SHELL" to "/bin/zsh",
        "TMPDIR" to "/var/folders/xy/T/",
        "LANG" to "sv_SE.UTF-8",
        "PATH" to "/app/only/bin:/usr/bin",
        "JAVA_HOME" to "/Library/Java/Home",
        "GAMERO_WEB_URL" to "https://hovel.example",
        "OPENAI_API_KEY" to "secret",
        "XPC_SERVICE_NAME" to "application.org.formulagen.wrapper",
    )

    @AfterTest fun cleanUp() { root.toFile().deleteRecursively() }

    private val pathFirst = listOf(bin.toString(), "/hovel/second")

    private fun launch(shell: String, parent: Map<String, String> = this.parent) =
        ShellLaunch(shell, parent, files, userName = "fallback", userHome = "/Users/fallback")

    private fun ShellLaunch.command(launch: Launch) = command(launch, pathFirst)

    @Test fun theLoginShellComesFromTheDirectoryThenShellThenZsh() {
        val usable = setOf("/bin/bash", "/opt/homebrew/bin/fish")::contains
        assertEquals("/bin/bash", loginShell("dsAttrTypeNative:UserShell\nUserShell: /bin/bash\n", mapOf("SHELL" to "/opt/homebrew/bin/fish"), usable))
        assertEquals("/opt/homebrew/bin/fish", loginShell(null, mapOf("SHELL" to "/opt/homebrew/bin/fish"), usable))
        assertEquals("/opt/homebrew/bin/fish", loginShell("UserShell: /usr/local/bin/missing", mapOf("SHELL" to "/opt/homebrew/bin/fish"), usable))
        assertEquals("/bin/zsh", loginShell(null, mapOf("SHELL" to "bash"), usable))
        assertEquals("/bin/zsh", loginShell("garbage", emptyMap(), usable))
    }

    @Test fun parsesUserShellOnItsLineOrTheNext() {
        assertEquals("/bin/zsh", parseUserShell("UserShell: /bin/zsh"))
        assertEquals("/usr/local/bin/fish", parseUserShell("UserShell:\n /usr/local/bin/fish\n"))
        assertNull(parseUserShell("No such key: UserShell"))
        assertNull(parseUserShell("UserShell:\n"))
    }

    @Test fun shellKindIsTheExecutablesName() {
        assertEquals(ShellKind.ZSH, ShellKind.of("/bin/zsh"))
        assertEquals(ShellKind.BASH, ShellKind.of("/opt/homebrew/bin/bash"))
        assertEquals(ShellKind.FISH, ShellKind.of("/usr/local/bin/fish"))
        assertEquals(ShellKind.OTHER, ShellKind.of("/bin/sh"))
        assertEquals(ShellKind.OTHER, ShellKind.of("/bin/zsh-5.9"))
    }

    @Test fun aTabGetsOnlyTheMinimalEnvironment() {
        val environment = launch("/bin/sh").environment(pathFirst)
        assertEquals(
            mapOf(
                "HOME" to "/Users/player", "USER" to "player", "LOGNAME" to "player", "SHELL" to "/bin/sh",
                "TMPDIR" to "/var/folders/xy/T/", "LANG" to "sv_SE.UTF-8",
                "PATH" to "$bin:/hovel/second:/usr/bin:/bin:/usr/sbin:/sbin",
                "TERM" to "xterm-256color", "COLORTERM" to "truecolor", "TERM_PROGRAM" to "Hovel", "HOVEL_WORKSPACE" to "1",
            ),
            environment,
        )
    }

    @Test fun missingValuesFallBack() {
        val environment = launch("/bin/sh", mapOf("HOME" to "relative", "LANG" to " ", "TMPDIR" to "tmp")).environment(pathFirst)
        assertEquals("/Users/fallback", environment["HOME"])
        assertEquals("fallback", environment["USER"])
        assertEquals("fallback", environment["LOGNAME"])
        assertEquals("en_US.UTF-8", environment["LANG"])
        assertFalse("TMPDIR" in environment)
    }

    @Test fun theSshAgentSocketPassesThroughWhenItIsAnAbsolutePath() {
        val socket = "/private/tmp/com.apple.launchd.abc/Listeners"
        assertEquals(socket, launch("/bin/zsh", parent + ("SSH_AUTH_SOCK" to socket)).environment(pathFirst)["SSH_AUTH_SOCK"])
        assertFalse("SSH_AUTH_SOCK" in launch("/bin/zsh", parent + ("SSH_AUTH_SOCK" to "relative/socket")).environment(pathFirst))
        assertFalse("SSH_AUTH_SOCK" in launch("/bin/zsh").environment(pathFirst))
    }

    @Test fun eachCommandTakesItsProjectsPathEntries() {
        val shell = launch("/bin/zsh")
        val one = shell.command(Launch.Shell, listOf("/projects/one/bin")).environment
        val two = shell.command(Launch.Shell, listOf("/projects/two/bin", "/hovel/bin")).environment
        assertEquals("/projects/one/bin:/usr/bin:/bin:/usr/sbin:/sbin", one["PATH"])
        assertEquals("/projects/two/bin:/hovel/bin", two[PATH_PREPEND_VARIABLE])
    }

    @Test fun zshStartsAsALoginShellThroughHovelsZdotdir() {
        val command = launch("/bin/zsh").command(Launch.Shell)
        assertEquals(listOf("/bin/zsh", "-l"), command.argv)
        assertEquals(files.resolve("zsh").toString(), command.environment["ZDOTDIR"])
        assertEquals("$bin:/hovel/second", command.environment[PATH_PREPEND_VARIABLE])
        assertFalse(USER_ZDOTDIR_VARIABLE in command.environment)
        assertEquals("/bin/zsh", command.environment["SHELL"])
    }

    @Test fun zshKeepsThePlayersOwnZdotdir() {
        val command = launch("/bin/zsh", parent + ("ZDOTDIR" to "/Users/player/.config/zsh")).command(Launch.Shell)
        assertEquals("/Users/player/.config/zsh", command.environment[USER_ZDOTDIR_VARIABLE])
        assertEquals(files.resolve("zsh").toString(), command.environment["ZDOTDIR"])
    }

    @Test fun bashEmulatesALoginShellFromHovelsInitFile() {
        val command = launch("/opt/homebrew/bin/bash").command(Launch.Shell)
        assertEquals(listOf("/opt/homebrew/bin/bash", "--init-file", files.resolve("hovel.bash").toString(), "-i"), command.argv)
        assertEquals("$bin:/hovel/second", command.environment[PATH_PREPEND_VARIABLE])
        assertFalse("ZDOTDIR" in command.environment)
    }

    @Test fun fishSourcesHovelsFileAfterThePlayersConfig() {
        val command = launch("/usr/local/bin/fish").command(Launch.Shell)
        // The file's path is a variable, never part of the command string.
        assertEquals(listOf("/usr/local/bin/fish", "--login", "--init-command", "source \"\$HOVEL_FISH_INIT\""), command.argv)
        assertEquals(files.resolve("hovel.fish").toString(), command.environment[FISH_INIT_VARIABLE])
        assertEquals("$bin:/hovel/second", command.environment[PATH_PREPEND_VARIABLE])
        assertFalse("ZDOTDIR" in command.environment)
    }

    @Test fun anyOtherShellIsAPlainLoginShellWithHovelFirstInItsBasePath() {
        val command = launch("/bin/ksh").command(Launch.Shell)
        assertEquals(listOf("/bin/ksh", "-l"), command.argv)
        assertFalse(PATH_PREPEND_VARIABLE in command.environment)
        assertFalse(FISH_INIT_VARIABLE in command.environment)
        assertTrue(command.environment.getValue("PATH").startsWith("$bin:/hovel/second:"))
    }

    @Test fun aToolRunsThroughTheLoginShellWithItsPathAsAnArgumentNeverInTheScript() {
        val nvim = bin.resolve("nvim").also { it.writeText("#!/bin/sh\n"); Files.setPosixFilePermissions(it, PosixFilePermissions.fromString("rwx------")) }
        val tool = Launch.Tool(WorkspaceTool.NEOVIM)
        assertEquals(listOf("/bin/zsh", "-l", "-i", "-c", "exec \"\$@\"", "hovel-tool", nvim.toString()), launch("/bin/zsh").command(tool).argv)
        assertEquals(
            listOf("/bin/bash", "--init-file", files.resolve("hovel.bash").toString(), "-i", "-c", "exec \"\$@\"", "hovel-tool", nvim.toString()),
            launch("/bin/bash").command(tool).argv,
        )
        assertEquals(
            listOf("/usr/local/bin/fish", "--login", "--interactive", "--init-command", "source \"\$HOVEL_FISH_INIT\"", "-c", "exec \$argv", "--", nvim.toString()),
            launch("/usr/local/bin/fish").command(tool).argv,
        )
        // Another shell's -c syntax is unknown, so the same script runs in /bin/sh, with the tab's environment.
        val other = launch("/bin/tcsh").command(tool)
        assertEquals(listOf("/bin/sh", "-c", "exec \"\$@\"", "hovel-tool", nvim.toString()), other.argv)
        assertEquals(launch("/bin/tcsh").command(Launch.Shell).environment, other.environment)
    }

    @Test fun theHostsToolArgumentsFollowTheToolAsSeparateArguments() {
        val socket = "/Users/p/Library/Caches/org.formulagen.wrapper/workspace/nvim/ab12.sock"
        val argv = launch("/bin/zsh").command(Launch.Tool(WorkspaceTool.NEOVIM), pathFirst, listOf("--listen", socket)).argv
        assertEquals(listOf("nvim", "--listen", socket), argv.takeLast(3))
        assertEquals("exec \"\$@\"", argv[argv.indexOf("-c") + 1], "the script stays fixed")
        assertEquals(listOf("--", "nvim", "--listen", socket), launch("/usr/local/bin/fish").command(Launch.Tool(WorkspaceTool.NEOVIM), pathFirst, listOf("--listen", socket)).argv.takeLast(4))
        // A Shell tab takes none.
        assertEquals(launch("/bin/zsh").command(Launch.Shell).argv, launch("/bin/zsh").command(Launch.Shell, pathFirst, listOf("--listen", socket)).argv)
    }

    @Test fun aToolWithoutAWrapperIsFoundOnThePlayersPath() {
        assertEquals("lazygit", launch("/bin/zsh").command(Launch.Tool(WorkspaceTool.LAZYGIT)).argv.last())
        // A file that is not executable is not a wrapper.
        bin.resolve("yazi").writeText("#!/bin/sh\n")
        assertEquals("yazi", launch("/bin/zsh").command(Launch.Tool(WorkspaceTool.YAZI)).argv.last())
    }

    @Test fun aToolGetsTheSameEnvironmentAsAShell() {
        assertEquals(launch("/bin/zsh").command(Launch.Shell).environment, launch("/bin/zsh").command(Launch.Tool(WorkspaceTool.LAZYGIT)).environment)
    }

    @Test fun diffTabsAreNotPartOfTheSpike() {
        assertFailsWith<UnsupportedOperationException> { launch("/bin/zsh").command(Launch.Diff("README.md", history = false)) }
    }

    @Test fun refusesPathsItCannotJoin() {
        assertFailsWith<IllegalArgumentException> { ShellLaunch("zsh", parent, files) }
        assertFailsWith<IllegalArgumentException> { launch("/bin/zsh").command(Launch.Shell, listOf("/a:/b")) }
        assertFailsWith<IllegalArgumentException> { launch("/bin/zsh").command(Launch.Shell, listOf("relative/bin")) }
        assertFailsWith<IllegalArgumentException> { launch("/bin/zsh").command(Launch.Shell, listOf("")) }
    }

    @Test fun anyShellFolderWorksSinceNoPathIsPartOfACommandString() {
        val odd = root.resolve("Björn's shell files")
        for (shell in listOf("/bin/zsh", "/bin/bash", "/usr/local/bin/fish")) {
            val command = ShellLaunch(shell, parent, odd).command(Launch.Tool(WorkspaceTool.LAZYGIT), pathFirst)
            val script = command.argv[command.argv.indexOf("-c") + 1]
            assertFalse(odd.toString() in script, "$shell: $script")
            assertTrue(command.argv.any { odd.toString() in it } || command.environment.values.any { odd.toString() in it }, shell)
        }
    }

    @Test fun installsEachStartupFileAndRestoresAChangedOne() {
        ShellFiles.install(files)
        val installed = listOf("zsh/.zshenv", "zsh/.zprofile", "zsh/.zshrc", "zsh/.zlogin", "hovel.bash", "hovel.fish", "visible-controls.pl").map(files::resolve)
        installed.forEach { assertTrue(Files.isRegularFile(it), "$it") }
        assertTrue(installed[2].readText().contains("add-zsh-hook precmd __hovel_prepend_path"))
        val modified = Files.getLastModifiedTime(installed[4])
        Thread.sleep(20)
        ShellFiles.install(files)
        assertEquals(modified, Files.getLastModifiedTime(installed[4]), "an unchanged file is left alone")
        installed[5].writeText("tampered")
        ShellFiles.install(files)
        assertTrue(installed[5].readText().contains("--on-event fish_prompt"))
    }

    @Test fun theDefaultFolderIsUnderCachesWithNoSpaces() {
        assertEquals(Path.of("/Users/player/Library/Caches/org.formulagen.wrapper/workspace/shell"), ShellFiles.defaultFolder("/Users/player"))
    }

    @Test fun probeEscapes() {
        assertEquals("ls\r\u001b[A\t\\x", unescape("ls\\r\\e[A\\t\\\\x"))
        assertEquals("é", unescape("\\xe9"))
        assertEquals("trailing\\", unescape("trailing\\"))
    }
}
