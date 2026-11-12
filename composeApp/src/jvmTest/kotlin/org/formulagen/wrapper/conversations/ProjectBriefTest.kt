package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.formulagen.wrapper.runtimes.ProjectFolders

class ProjectBriefTest {
    private val root: Path = folder("brief")

    private fun file(path: String, text: String = "x") {
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
    }

    private fun brief() = checkNotNull(projectBrief(ProjectFolders(root)))

    @Test fun aBriefSaysWhatTheProjectIsAndWhereThingsAre() {
        file("package.json", """{"name":"my-shopping-list","scripts":{"dev":"next dev","build":"next build"},"dependencies":{"react":"19","next":"15"},"devDependencies":{"typescript":"5"}}""")
        file("README.md", "# My shopping list\n\nA list you share with your household.")
        file("src/app/page.tsx")
        file("src/app/layout.tsx")
        file("public/logo.svg")
        val brief = brief()
        assertTrue("Build files: package.json" in brief, brief)
        assertTrue("package.json: name my-shopping-list; scripts dev, build; depends on react, next, typescript" in brief, brief)
        assertTrue("README.md, in full:\n# My shopping list\n\nA list you share with your household." in brief, brief)
        assertTrue("  ./ (2 files): README.md, package.json" in brief, brief)
        assertTrue("  src/app/ (2 files): layout.tsx, page.tsx" in brief, brief)
        // Nearest the top first: both top folders are listed before what is inside them.
        assertTrue(brief.indexOf("  public/") < brief.indexOf("  src/app/"), brief)
    }

    @Test fun secretsAndGeneratedFoldersAreNeitherReadNorNamed() {
        file(".env", "TOKEN=abc")
        file(".env.local", "TOKEN=def")
        file("deploy.pem", "key")
        file("id_rsa", "key")
        file("node_modules/react/index.js")
        file(".git/config")
        file("build/out.js")
        file("src/main.kt")
        file(".github/workflows/ci.yml")
        val brief = brief()
        for (hidden in listOf(".env", "deploy.pem", "id_rsa", "node_modules", ".git/", "build/", "TOKEN")) assertFalse(hidden in brief, "$hidden is in the brief: $brief")
        assertTrue("  src/ (1 file): main.kt" in brief && ".github/workflows/" in brief, brief)
    }

    @Test fun aLinkedFolderIsNotFollowed() {
        val outside = folder("outside")
        Files.writeString(outside.resolve("secret.txt"), "not this project's")
        file("src/main.kt")
        Files.createSymbolicLink(root.resolve("linked"), outside)
        assertFalse("secret.txt" in brief() || "linked" in brief(), brief())
    }

    @Test fun aLargeProjectStaysWithinTheBriefsBounds() {
        for (folder in 1..(BriefLimits.FOLDERS + 20)) file("pkg$folder/a.kt")
        for (name in 1..40) file("many/f$name.kt")
        file("README.md", "r".repeat(BriefLimits.README_CHARS * 3))
        val brief = brief()
        assertTrue(brief.length <= BriefLimits.CHARS, "${brief.length}")
        assertTrue("README.md, its first ${BriefLimits.README_CHARS} characters:" in brief, brief.take(200))
        assertTrue("many/ (40 files): " in brief && ", …" in brief, "a long folder shows its first files and that there are more")
        assertTrue("… and at least" in brief, "folders past the limit are counted, not listed")
        assertEquals(BriefLimits.FOLDERS, brief.lines().count { it.startsWith("  ") && it.contains("/ (") })
    }

    @Test fun aProjectOfAnotherKindNamesItsBuildFiles() {
        file("settings.gradle.kts")
        file("build.gradle.kts")
        file("Makefile")
        assertTrue("Build files: settings.gradle.kts, build.gradle.kts, Makefile" in brief(), brief())
        assertFalse("package.json:" in brief())
    }

    @Test fun aProjectWithNoFolderHasNoBrief() {
        assertNull(projectBrief(ProjectFolders(null)))
        assertNull(projectBrief(ProjectFolders(root.resolve("gone"))))
    }

    @Test fun aManifestThatCannotBeReadIsNamedAndNotQuoted() {
        file("package.json", "{ not json")
        assertTrue("Build files: package.json" in brief() && "package.json:" !in brief(), brief())
    }
}
