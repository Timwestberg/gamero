package org.formulagen.wrapper.runtimes

import java.nio.file.Files
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
    /** The folder as the file system resolves it; null when it does not. */
    private val real = runCatching { folder.toRealPath() }.getOrNull()
    /** The folder as given and as the file system resolves it (`/tmp` is `/private/tmp` on a Mac). */
    private val folders = listOfNotNull(folder.toAbsolutePath().normalize(), real).distinct()
    private val homes = listOfNotNull(home?.toAbsolutePath()?.normalize(), home?.let { runCatching { it.toRealPath() }.getOrNull() }).distinct()

    /** [raw] (absolute, or relative to the folder) as the page sees it. */
    fun label(raw: String): String {
        val path = runCatching { Path.of(raw) }.getOrNull() ?: return scrub(raw)
        val absolute = given(path).normalize()
        return asGiven(absolute) ?: ("…/" + (absolute.fileName?.toString() ?: absolute.toString()))
    }

    /**
     * [path] (absolute, or relative to the folder) relative to the folder, the folder itself as `.`, or null when it is
     * outside: the one rule for a path being inside the folder. What is on disk decides. The path's real path, or for a
     * deleted file its nearest existing parent's with the rest added, must be under the folder's real path. Links are
     * resolved before `..`, so `link/../a` is beside the link's target. Only when nothing on disk resolves does the
     * path as given decide, as for [label].
     */
    fun inside(path: Path): String? {
        val given = given(path)
        val resolved = onDisk(given) ?: return asGiven(given.normalize())
        val base = real ?: return null
        return if (resolved.startsWith(base)) base.relativize(resolved).toString().ifEmpty { "." } else null
    }

    /** [path] made absolute against the folder as given. */
    private fun given(path: Path): Path = if (path.isAbsolute) path else folders.first().resolve(path)

    /** Normalized [absolute] relative to the folder as given or as it resolves, the folder itself as `.`; null outside both. */
    private fun asGiven(absolute: Path): String? {
        for (base in folders) if (absolute.startsWith(base)) return base.relativize(absolute).toString().ifEmpty { "." }
        return null
    }

    /** Free text with the folder as `.` and the home folder as `~`, longest first, and only where a whole path ends. */
    fun scrub(text: String): String {
        var result = text
        for (base in folders.sortedByDescending { it.toString().length }) result = whole(base).replace(result, ".")
        for (base in homes.sortedByDescending { it.toString().length }) if (base.nameCount > 0) result = whole(base).replace(result, "~")
        return result
    }

    private fun whole(base: Path) = wholePath(base.toString())

    /**
     * Absolute [path] as the file system resolves it, each link before the `..` after it: its real path, or its nearest
     * existing parent's with the rest of [path] added. That rest exists nowhere, so its `..` apply by name, and what they
     * lead back to is resolved again. Null when no part of [path] resolves.
     */
    private fun onDisk(path: Path): Path? {
        for (existing in generateSequence(path) { it.parent }) {
            if (!Files.exists(existing)) continue
            val resolved = runCatching { existing.toRealPath() }.getOrNull() ?: continue
            if (existing.nameCount == path.nameCount) return resolved
            val rest = path.subpath(existing.nameCount, path.nameCount)
            val joined = resolved.resolve(rest).normalize()
            return if (rest.normalize() == rest) joined else onDisk(joined)
        }
        return null
    }
}

/**
 * [path] where it stands as a whole path in free text: after the start or a delimiter (not inside a longer
 * path) and before the end of a path (not `/game` inside `/game-old`). The one boundary rule for every place
 * that writes a native path in other terms.
 */
internal fun wholePath(path: String): Regex =
    Regex("(?<![^\\s\"'`=(\\[{<:,;])" + Regex.escape(path) + "(?=$|[/\\s'\"`:;,)\\]}>])")
