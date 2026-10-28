package org.formulagen.wrapper.chat

import java.nio.file.Path
import org.formulagen.wrapper.approvals.shortened

/**
 * What a working reply did, step by step, as the page will show it (the contract's `activity`):
 * labels are paths relative to the project folder, commands, `server · tool` or a query, and
 * details are shortened results. Nothing in it is a path outside the project folder.
 */
internal enum class ActivityKind { READ, SEARCH, EDIT, COMMAND, CONNECTOR, WEB, OTHER }

internal enum class ActivityStatus { DONE, DENIED, FAILED }

internal data class ActivityStep(val kind: ActivityKind, val label: String, val status: ActivityStatus, val detail: String? = null) {
    init { require(label.length <= ActivityLimits.LABEL && (detail?.length ?: 0) <= ActivityLimits.DETAIL) }
}

/** At most [ActivityLimits.STEPS] steps; [truncated] when later ones were left out. */
internal data class Activity(val steps: List<ActivityStep>, val truncated: Boolean) {
    init { require(steps.size <= ActivityLimits.STEPS) }
}

/** Mirrors the contract's `ACTIVITY_LIMITS`. */
internal object ActivityLimits {
    const val DETAIL = 400
    const val LABEL = 240
    const val STEPS = 50
}

/**
 * Collects a reply's steps within [ActivityLimits]: the first [ActivityLimits.STEPS] are kept, a
 * later one marks the activity truncated, and every label and detail is shortened to fit.
 */
internal class ActivityLog {
    private val steps = mutableListOf<ActivityStep>()
    private var truncated = false

    fun add(kind: ActivityKind, label: String, status: ActivityStatus, detail: String?) {
        if (steps.size >= ActivityLimits.STEPS) { truncated = true; return }
        steps += ActivityStep(kind, label.ifBlank { kind.name.lowercase() }.shortened(ActivityLimits.LABEL), status,
            detail?.trim()?.takeIf { it.isNotEmpty() }?.shortened(ActivityLimits.DETAIL))
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

    /** [base] followed by the end of a path: not `/game` inside `/game-old`. */
    private fun whole(base: Path) = Regex(Regex.escape(base.toString()) + "(?=$|[/\\s'\"`:;,)\\]}>])")
}
