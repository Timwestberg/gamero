package org.formulagen.wrapper.conversations

import java.nio.file.Files
import java.nio.file.Path
import org.formulagen.wrapper.runtimes.ProjectFolders

/*
 * What may be read for the coordinator. The design is in docs/companion-folders/companion-folders-step2.ARCH.md.
 */

/**
 * Says whether a path the coordinator asked for may be read, and is the one place that decides. A path is relative to
 * the project's working folder (`src/app.ts`), or names a read-only folder first (`[design-docs]/plan.md`), as an
 * approval shows it. Nothing outside those folders is reachable: not by `..`, not by an absolute path, and not through
 * a link, since the real path is what is checked. What [SkipList] names is never read, whatever asks.
 */
internal class PathGuard(folders: ProjectFolders) {
    /** A folder that may be read, as the coordinator names it: null for the working folder. */
    internal class Root(val label: String?, val path: Path, val real: Path)

    val roots: List<Root> = buildList {
        folders.working?.let { root(null, it)?.let(::add) }
        folders.context.forEach { folder -> root((folder.fileName ?: folder).toString(), folder)?.let(::add) }
    }

    sealed interface Checked {
        /** [path] is real and inside a root; [shown] is how the coordinator and the person see it, never absolute. */
        data class Allowed(val path: Path, val shown: String) : Checked
        data class Refused(val shown: String, val reason: Refusal) : Checked
    }

    enum class Refusal(val said: String) {
        OUTSIDE("outside the project's folders"),
        SKIPPED("never read: a key, an environment file or a build folder"),
        MISSING("not found"),
    }

    /** [asked] as a real path inside a root and not skipped, or why not. The empty path and `.` are the working folder. */
    fun check(asked: String): Checked {
        val shown = asked.trim().take(SHOWN_LIMIT)
        val refused = { reason: Refusal -> Checked.Refused(shown.ifEmpty { "." }, reason) }
        if (asked.length > PATH_LIMIT || asked.any { it.code < 0x20 || it.code == 0x7F }) return refused(Refusal.OUTSIDE)
        val trimmed = asked.trim().removePrefix("./")
        if (trimmed.startsWith("/") || trimmed.startsWith("~") || trimmed.startsWith("\\")) return refused(Refusal.OUTSIDE)
        val labelled = LABELLED.matchEntire(trimmed)
        val root = (if (labelled == null) roots.firstOrNull { it.label == null }
            else roots.firstOrNull { it.label == labelled.groupValues[1] }) ?: return refused(Refusal.OUTSIDE)
        val relative = (labelled?.groupValues?.get(2) ?: trimmed).trim('/')
        val segments = relative.split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.any { it == ".." }) return refused(Refusal.OUTSIDE)
        if (skipped(segments)) return refused(Refusal.SKIPPED)
        val real = try {
            segments.fold(root.path) { path, segment -> path.resolve(segment) }.toRealPath()
        } catch (_: Exception) {
            return refused(Refusal.MISSING)
        }
        // A link is followed only to somewhere a root already holds, and never into what is skipped.
        if (!real.startsWith(root.real)) return refused(Refusal.OUTSIDE)
        val inside = root.real.relativize(real).map { it.toString() }.filter { it.isNotEmpty() }
        if (skipped(inside)) return refused(Refusal.SKIPPED)
        return Checked.Allowed(real, shown(root, inside))
    }

    /** [path], which is inside [root], as the coordinator names it. */
    fun shown(root: Root, path: Path): String = shown(root, root.real.relativize(path).map { it.toString() }.filter { it.isNotEmpty() })

    private fun shown(root: Root, inside: List<String>): String {
        val relative = inside.joinToString("/")
        return if (root.label == null) relative.ifEmpty { "." } else "[${root.label}]" + if (relative.isEmpty()) "" else "/$relative"
    }

    private fun skipped(segments: List<String>): Boolean =
        segments.dropLast(1).any(SkipList::folder) || segments.lastOrNull()?.let { SkipList.folder(it) || SkipList.file(it) } == true

    private fun root(label: String?, path: Path): Root? =
        runCatching { path.toRealPath() }.getOrNull()?.takeIf { Files.isDirectory(it) }?.let { Root(label, path, it) }

    private companion object {
        const val PATH_LIMIT = 400
        const val SHOWN_LIMIT = 120
        val LABELLED = Regex("^\\[([^\\]/]+)](?:/(.*))?$")
    }
}
