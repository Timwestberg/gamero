package org.formulagen.wrapper.workspace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import org.formulagen.wrapper.bridge.HostBridgeFixtures

/** The Kotlin mirror of event-contracts `WORKSPACE_LIMITS`, read from the synced host-bridge limits fixture. */
class WorkspaceLimitsTest {
    private val limits = Json.parseToJsonElement(HostBridgeFixtures.LIMITS).jsonObject.getValue("WORKSPACE_LIMITS").jsonObject

    private fun setting(name: String): LimitSetting {
        val value = limits.getValue(name).jsonObject
        fun field(key: String) = value.getValue(key).jsonPrimitive.int
        return LimitSetting(default = field("default"), min = field("min"), max = field("max"))
    }

    @Test
    fun theMirrorMatchesTheServersLimits() {
        assertEquals(limits.getValue("tabs").jsonPrimitive.int, WorkspaceSettingsLimits.TABS)
        assertEquals(setting("threadFiles"), WorkspaceSettingsLimits.THREAD_FILES)
        assertEquals(setting("projectThreads"), WorkspaceSettingsLimits.PROJECT_THREADS)
    }

    @Test
    fun everySettingTheServerNamesIsMirrored() {
        // A new setting in WORKSPACE_LIMITS fails here until it is mirrored above.
        assertEquals(setOf("projectThreads", "tabs", "threadFiles"), limits.keys)
    }

    @Test
    fun aMissingOrOutOfRangeSettingIsTheDefault() {
        val files = WorkspaceSettingsLimits.THREAD_FILES
        assertEquals(files.default, files.resolve(null))
        assertEquals(files.default, files.resolve(files.min - 1))
        assertEquals(files.default, files.resolve(files.max + 1))
        assertEquals(files.min, files.resolve(files.min))
        assertEquals(files.max, files.resolve(files.max))
        assertEquals(WorkspaceLimits.DEFAULTS, WorkspaceLimits.of(threadFiles = 0, projectThreads = 0))
    }
}
