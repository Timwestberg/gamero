package org.formulagen.wrapper.progression.embedded

import kotlinx.serialization.json.*
import kotlin.test.*

class DesktopGameLauncherTest {
    /** Only the installed ID crosses the bridge; paths and command fields are rejected. */
    @Test fun validatesInstalledGameSelection() {
        assertTrue(DesktopGameLauncher.accepts(buildJsonObject { put("gameId", "hovel-companions") }))
        for (raw in listOf("{}", "{\"gameId\":\"../../other\"}", "{\"gameId\":1}",
            "{\"gameId\":\"hovel-companions\",\"path\":\"/tmp/game\"}")) {
            assertFalse(DesktopGameLauncher.accepts(Json.parseToJsonElement(raw).jsonObject))
        }
    }
}
