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

/** The synced limits. A fixture test fails when these differ from `WORKSPACE_LIMITS`. */
internal object WorkspaceSettingsLimits {
    const val TABS = 8
    val THREAD_FILES = LimitSetting(default = 500, min = 50, max = 2_000)
    val PROJECT_THREADS = LimitSetting(default = 200, min = 20, max = 1_000)
}

/** The history limits in force for one account, resolved from its profile. */
internal data class WorkspaceLimits(val threadFiles: Int, val projectThreads: Int) {
    companion object {
        /** The limits of an account with no history settings, which is every account until profile sync exists. */
        val DEFAULTS = WorkspaceLimits(WorkspaceSettingsLimits.THREAD_FILES.default, WorkspaceSettingsLimits.PROJECT_THREADS.default)
    }
}

/** Fixed guards against oversized input. Not settings. */
internal object WorkspaceGuards {
    const val TOOL_FILE_BYTES = 64 * 1024            // one mise.toml or .tool-versions
    const val GIT_OUTPUT_BYTES = 4 * 1024 * 1024     // one git status for the changes view
    const val EDIT_RECORD_BYTES = 1024 * 1024        // one thread's record, read back
    const val SCROLLBACK_LINES = 10_000              // per terminal tab
}
