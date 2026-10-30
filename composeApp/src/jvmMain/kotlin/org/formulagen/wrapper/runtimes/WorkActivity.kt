package org.formulagen.wrapper.runtimes

import java.nio.file.Path
import org.formulagen.wrapper.util.shortened
import org.formulagen.wrapper.conversation.Activity
import org.formulagen.wrapper.conversation.ActivityKind
import org.formulagen.wrapper.conversation.ActivityLimits
import org.formulagen.wrapper.conversation.ActivityStatus
import org.formulagen.wrapper.conversation.ActivityStep

/*
 * A working reply's activity uses the contract's types ([Activity], [ActivityStep]): labels are
 * paths relative to the project folder, commands, `server · tool` or a query, and details are
 * shortened results. Nothing in it is a path outside the project folder.
 */

/**
 * Collects a reply's steps within [ActivityLimits]: the first [ActivityLimits.STEPS] are kept, a
 * later one marks the activity truncated, and every label and detail is scrubbed by [paths] (the
 * project folder as `.`, the home folder as `~`) and shortened to fit. This is the one place a
 * step's text is made fit for the page.
 */
internal class ActivityLog(private val paths: WorkPaths) {
    private val steps = mutableListOf<ActivityStep>()
    private var truncated = false

    fun add(kind: ActivityKind, label: String, status: ActivityStatus, detail: String?) {
        if (steps.size >= ActivityLimits.STEPS) { truncated = true; return }
        steps += ActivityStep(kind, paths.scrub(label).ifBlank { kind.name.lowercase() }.shortened(ActivityLimits.LABEL), status,
            detail?.let(paths::scrub)?.trim()?.takeIf { it.isNotEmpty() }?.shortened(ActivityLimits.DETAIL))
    }

    fun activity(): Activity = Activity(steps.toList(), truncated)

    val isEmpty: Boolean get() = steps.isEmpty()
}

/**
 * How native paths appear to the page while a member works in [folder]: a path inside it is
 * relative to it (the folder itself is `.`), a path outside it shows only its own name after `…/`,
 * and in free text (commands, outputs) the folder becomes `.` and the player's home folder `~`.
 */
internal class WorkPaths(folder: Path, home: Path? = System.getProperty("user.home")?.let(Path::of)) {
    /** The folder as given and as the file system resolves it (`/tmp` is `/private/tmp` on a Mac). */
    private val folders = listOfNotNull(folder.toAbsolutePath().normalize(), runCatching { folder.toRealPath() }.getOrNull()).distinct()
    private val homes = listOfNotNull(home?.toAbsolutePath()?.normalize(), home?.let { runCatching { it.toRealPath() }.getOrNull() }).distinct()

    /** [raw] (absolute, or relative to the folder) as the page sees it. */
    fun label(raw: String): String {
        val path = runCatching { Path.of(raw) }.getOrNull() ?: return scrub(raw)
        val absolute = (if (path.isAbsolute) path else folders.first().resolve(path)).normalize()
        for (base in folders) if (absolute.startsWith(base)) return base.relativize(absolute).toString().ifEmpty { "." }
        return "…/" + (absolute.fileName?.toString() ?: absolute.toString())
    }

    /** Free text with the folder as `.` and the home folder as `~`, longest first, and only where a whole path ends. */
    fun scrub(text: String): String {
        var result = text
        for (base in folders.sortedByDescending { it.toString().length }) result = whole(base).replace(result, ".")
        for (base in homes.sortedByDescending { it.toString().length }) if (base.nameCount > 0) result = whole(base).replace(result, "~")
        return result
    }

    private fun whole(base: Path) = wholePath(base.toString())
}

/**
 * [path] where it stands as a whole path in free text: after the start or a delimiter (not inside a longer
 * path) and before the end of a path (not `/game` inside `/game-old`). The one boundary rule for every place
 * that writes a native path in other terms.
 */
internal fun wholePath(path: String): Regex =
    Regex("(?<![^\\s\"'`=(\\[{<:,;])" + Regex.escape(path) + "(?=$|[/\\s'\"`:;,)\\]}>])")
