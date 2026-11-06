package org.formulagen.wrapper.workspace

/*
 * Every project-workspace limit, in one place. Synced limits mirror event-contracts `WORKSPACE_LIMITS`, because
 * the server validates the profile that sets them. Guards are native only: they protect the Mac from oversized
 * input, and players never change them. To make a guard a player setting, move it into `WORKSPACE_LIMITS` and
 * `workspaceLimitSettings`; a missing setting means the default, so older profiles stay valid.
 */

/** One limit the player may set: its default and allowed range. Mirrors `WORKSPACE_LIMITS`. */
internal data class LimitSetting(val default: Int, val min: Int, val max: Int) {
    /** The value in force: a valid setting, or the default. */
    fun resolve(value: Int?): Int = value?.takeIf { it in min..max } ?: default
}

/** The synced limits, mirroring `WORKSPACE_LIMITS`. `WorkspaceLimitsTest` fails when they differ from its fixture. */
internal object WorkspaceSettingsLimits {
    const val TABS = 8
    val THREAD_FILES = LimitSetting(default = 500, min = 50, max = 2_000)
    val PROJECT_THREADS = LimitSetting(default = 200, min = 20, max = 1_000)
}

/** The history limits in force for one account, resolved from its profile. Only [of] makes them, so each is in range. */
@ConsistentCopyVisibility
internal data class WorkspaceLimits private constructor(val threadFiles: Int, val projectThreads: Int) {
    companion object {
        /** The limits in force for an account's raw settings, each through [LimitSetting.resolve]: missing or out of range is the default. */
        fun of(threadFiles: Int?, projectThreads: Int?) = WorkspaceLimits(
            WorkspaceSettingsLimits.THREAD_FILES.resolve(threadFiles), WorkspaceSettingsLimits.PROJECT_THREADS.resolve(projectThreads))

        /** The limits of an account with no history settings, which is every account until profile sync exists. */
        val DEFAULTS = of(threadFiles = null, projectThreads = null)
    }
}

/** Fixed guards against oversized input. Not settings. */
internal object WorkspaceGuards {
    const val TOOL_FILE_BYTES = 64 * 1024            // one mise.toml or .tool-versions
    const val GIT_OUTPUT_BYTES = 4 * 1024 * 1024     // one git status for the changes view
    const val EDIT_RECORD_BYTES = 1024 * 1024        // one thread's record, read back
    const val SCROLLBACK_LINES = 10_000              // per terminal tab
}
