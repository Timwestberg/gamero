package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.conversations.PathGuard.Checked
import org.formulagen.wrapper.conversations.PathGuard.Refusal
import org.formulagen.wrapper.runtimes.ProjectFolders

class PathGuardTest {
    private val base: Path = folder("guard")
    private val working: Path = Files.createDirectories(base.resolve("shop"))
    private val docs: Path = Files.createDirectories(base.resolve("brand"))
    private val private: Path = Files.createDirectories(base.resolve("private"))

    init {
        Files.createDirectories(working.resolve("src"))
        Files.writeString(working.resolve("README.md"), "# Shop")
        Files.writeString(working.resolve("src/app.ts"), "export {}")
        Files.writeString(working.resolve(".env"), "TOKEN=abc")
        Files.writeString(docs.resolve("logo.md"), "# Logo")
        Files.writeString(private.resolve("secret.txt"), "secret")
    }

    private val guard = PathGuard(ProjectFolders(working, listOf(docs)))

    private fun shown(asked: String) = (guard.check(asked) as? Checked.Allowed)?.shown
    private fun refused(asked: String) = (guard.check(asked) as? Checked.Refused)?.reason

    @Test fun aPathIsNamedRelativeToItsFolderAndNeverAbsolute() {
        assertEquals("src/app.ts", shown("src/app.ts"))
        assertEquals("README.md", shown("./README.md"))
        assertEquals("src", shown("src/"))
        assertEquals(".", shown(""))
        assertEquals(".", shown("."))
        // A read-only folder goes by its name in brackets, as an approval shows it.
        assertEquals("[brand]/logo.md", shown("[brand]/logo.md"))
        assertEquals("[brand]", shown("[brand]"))
        assertEquals(working.resolve("src/app.ts").toRealPath(), (guard.check("src/app.ts") as Checked.Allowed).path)
    }

    @Test fun nothingOutsideTheProjectsFoldersIsReachable() {
        assertEquals(Refusal.OUTSIDE, refused("../private/secret.txt"))
        assertEquals(Refusal.OUTSIDE, refused("src/../../private/secret.txt"))
        assertEquals(Refusal.OUTSIDE, refused("/etc/passwd"))
        assertEquals(Refusal.OUTSIDE, refused("~/.ssh/config"))
        assertEquals(Refusal.OUTSIDE, refused("[private]/secret.txt"), "only the project's own read-only folders have a name")
        // Even a file of the project is not reached by its absolute path: the coordinator never learns one.
        assertEquals(Refusal.OUTSIDE, refused(working.resolve("README.md").toString()))
        assertEquals(Refusal.OUTSIDE, refused("src/\u0000app.ts"))
        assertEquals(Refusal.MISSING, refused("src/missing.ts"))
    }

    @Test fun aLinkIsFollowedOnlyToWhereTheFolderAlreadyReaches() {
        Files.createSymbolicLink(working.resolve("leak"), private)
        Files.createSymbolicLink(working.resolve("settings.txt"), working.resolve(".env"))
        Files.createSymbolicLink(working.resolve("alias.md"), working.resolve("README.md"))
        assertEquals(Refusal.OUTSIDE, refused("leak/secret.txt"))
        assertEquals(Refusal.OUTSIDE, refused("leak"))
        assertEquals(Refusal.SKIPPED, refused("settings.txt"), "a link to a secret is the secret")
        assertEquals("README.md", shown("alias.md"), "it is named as what it really is")
    }

    @Test fun secretsAndBuildFoldersAreNeverReadWhateverAsks() {
        Files.createDirectories(working.resolve("node_modules/left-pad"))
        Files.writeString(working.resolve("node_modules/left-pad/index.js"), "module.exports = 1")
        Files.createDirectories(working.resolve("config"))
        Files.writeString(working.resolve("config/server.pem"), "-----BEGIN")
        Files.writeString(working.resolve(".env.local"), "TOKEN=abc")
        for (asked in listOf(".env", ".env.local", "config/server.pem", "node_modules/left-pad/index.js", "node_modules", ".git/config", "id_rsa")) {
            assertEquals(Refusal.SKIPPED, refused(asked), asked)
        }
    }

    @Test fun aProjectWithNoFolderHasNothingToRead() {
        val none = PathGuard(ProjectFolders(null))
        assertEquals(emptyList(), none.roots)
        assertEquals(Refusal.OUTSIDE, (none.check("README.md") as Checked.Refused).reason)
    }
}
