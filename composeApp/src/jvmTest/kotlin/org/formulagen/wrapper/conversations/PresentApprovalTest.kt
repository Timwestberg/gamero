package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*
import org.formulagen.wrapper.approvals.ApprovalRequest
import org.formulagen.wrapper.approvals.ApprovalSubject
import org.formulagen.wrapper.approvals.startOfWorkDialog
import org.formulagen.wrapper.bridge.*

class PresentApprovalTest {
    private val root = folder("present")
    private val working = Files.createDirectory(root.resolve("hovel-notes"))
    private val docs = Files.createDirectory(root.resolve("design-docs"))
    private val folders = listOf(LocalFolder(id(), "hovel-notes", working, FolderRole.WORKING), LocalFolder(id(), "design-docs", docs, FolderRole.CONTEXT))
    private val member = TeamMember(RuntimeId.CLAUDE_CODE, "claude-sonnet")

    private fun request(subject: ApprovalSubject, name: String = "Ember") = ApprovalRequest(name, member, working, subject, null, "lease")
    private fun lines(presentation: ApprovalPresentation) = (presentation as ApprovalPresentation.Anywhere).lines

    @Test fun pathsInsideTheFoldersAreRelativeToThem() {
        val edit = request(ApprovalSubject.Edit("Edit", listOf(working.resolve("src/app.ts"), docs.resolve("plan.md")), "- old\n+ /etc/new"))
        assertEquals(listOf("Edit ./src/app.ts, [design-docs]/plan.md"), lines(presentApproval(edit, folders)), "the diff stays on the Mac")
        val folderItself = request(ApprovalSubject.Edit("Write", listOf(working), ""))
        assertEquals(listOf("Write ."), lines(presentApproval(folderItself, folders)))
    }

    @Test fun aCommandShowsItsFolderRelativeFormWhereItRunsAndWhatItNeeds() {
        val command = request(ApprovalSubject.Command("cat $docs/plan.md > $working/notes.md\nnpm test",
            working.resolve("src"), docs.resolve("assets"), "Copy the plan"))
        assertEquals(listOf("cat [design-docs]/plan.md > ./notes.md", "npm test", "In ./src", "Needs access to [design-docs]/assets", "Copy the plan"),
            lines(presentApproval(command, folders)))
    }

    @Test fun aHomeRelativeFormOfAFolderIsRecognized() {
        val home = root
        val labels = FolderLabels(folders, home)
        assertEquals("cat ./a.txt [design-docs]/b.md", labels.scrub("cat ~/hovel-notes/a.txt ~/design-docs/b.md"))
        assertEquals("ls ~/hovel-notes-old", labels.scrub("ls ~/hovel-notes-old"), "only where a whole path ends")
    }

    @Test fun anythingOutsideTheFoldersIsMacOnly() {
        val outside = listOf(
            ApprovalSubject.Edit("Edit", listOf(root.resolve("elsewhere/secret.txt")), ""),
            ApprovalSubject.Command("rm -rf /usr/local/thing", null, null, null),
            ApprovalSubject.Command("ls", root, null, null),
            ApprovalSubject.Command("ls", null, Path.of("/etc/hosts"), null),
            ApprovalSubject.Command("echo \$HOME", null, null, null),
            ApprovalSubject.Command("open file:///tmp/x", null, null, null),
            ApprovalSubject.Command("cat ~/.ssh/id_rsa", null, null, null),
            ApprovalSubject.Command("cd $working-old && ls", null, null, null),
            ApprovalSubject.Connector("files", "read", """{"path":"/etc/passwd"}"""),
            ApprovalSubject.Other("Fetch", "Reads ‮evil"),
        )
        for (subject in outside) {
            val presentation = presentApproval(request(subject), folders)
            assertIs<ApprovalPresentation.MacOnly>(presentation, subject.toString())
            assertTrue(ShareableText.isShareable(presentation.summary, CompanionLimits.APPROVAL_SUMMARY), presentation.summary)
        }
        assertEquals("Ember wants to run a command. Answer it on the Mac.",
            (presentApproval(request(ApprovalSubject.Command("ls /", null, null, null)), folders) as ApprovalPresentation.MacOnly).summary)
    }

    @Test fun aNameThatCannotLeaveTheMacIsLeftOutOfTheSummary() {
        val presentation = presentApproval(request(ApprovalSubject.Command("ls /", null, null, null), name = "~root"), folders)
        assertEquals("An agent wants to run a command. Answer it on the Mac.", (presentation as ApprovalPresentation.MacOnly).summary)
    }

    @Test fun aRequestTooLongToShowIsMacOnly() {
        val many = request(ApprovalSubject.Command((1..70).joinToString("\n") { "echo $it" }, null, null, null))
        assertIs<ApprovalPresentation.MacOnly>(presentApproval(many, folders))
        val wide = request(ApprovalSubject.Command("echo " + "x".repeat(CompanionLimits.APPROVAL_LINE), null, null, null))
        assertIs<ApprovalPresentation.MacOnly>(presentApproval(wide, folders))
    }

    @Test fun aTalkOnlyProjectHasNoFolderToNameAPathBy() {
        assertIs<ApprovalPresentation.MacOnly>(presentApproval(request(ApprovalSubject.Edit("Edit", listOf(working.resolve("a")), "")), emptyList()))
        assertEquals(listOf("server · tool", "{}"), lines(presentApproval(request(ApprovalSubject.Connector("server", "tool", "{}")), emptyList())))
    }

    @Test fun theEnginesOwnDialogsAreShownFolderRelative() {
        val dialog = startOfWorkDialog("Ember", working, member)
        val shown = lines(presentDialog(dialog, folders, "Ember"))
        assertEquals("Project folder: .", shown[1])
        assertTrue(shown.all { ShareableText.isShareable(it, CompanionLimits.APPROVAL_LINE) })
        assertIs<ApprovalPresentation.MacOnly>(presentDialog(startOfWorkDialog("Ember", root.resolve("other"), member), folders, "Ember"))
    }

    @Test fun shareableTextMirrorsTheContract() {
        for (ok in listOf("./src/app.ts", "[docs]/plan.md", "a/b", "AC/DC", "x=1", "rate 3/4")) assertTrue(ShareableText.isShareable(ok, 100), ok)
        for (bad in listOf("/etc", " /etc", "\"/etc\"", "x=/etc", "~", "a ~/b", "\$HOME/x", "\${HOME}", "FILE://x", "a\u0000b", "a‮b",
            "a⁦b", "a\u0085b", "")) assertFalse(ShareableText.isShareable(bad, 100), bad)
        assertTrue(ShareableText.isShareable("", 100, min = 0))
        assertFalse(ShareableText.isShareable("abc", 2))
    }

    @Test fun activityLinesLeaveTheMacOnlyAsShareableText() {
        val activity = Activity(listOf(
            ActivityStep(ActivityKind.EDIT, "src/app.ts", ActivityStatus.DONE, "+1 -0"),
            ActivityStep(ActivityKind.READ, "$docs/plan.md", ActivityStatus.DONE),
            ActivityStep(ActivityKind.COMMAND, "cat /etc/hosts", ActivityStatus.DONE, "/etc/hosts: 3 lines"),
        ) + (1..45).map { ActivityStep(ActivityKind.READ, "file-$it", ActivityStatus.DONE) }, truncated = false)
        val shared = shareableActivity(activity, folders)
        assertEquals(CompanionLimits.ACTIVITY_SUMMARY, shared.size, "only the latest lines")
        val all = shareableActivity(Activity(activity.steps.take(3), false), folders)
        assertEquals(listOf("src/app.ts", "[design-docs]/plan.md", OUTSIDE_FOLDERS), all.map { it.label })
        assertEquals(listOf("+1 -0", null, null), all.map { it.detail })
        assertEquals(emptyList(), shareableActivity(null, folders))
    }
}
