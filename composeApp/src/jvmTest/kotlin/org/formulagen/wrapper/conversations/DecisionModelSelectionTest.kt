package org.formulagen.wrapper.conversations

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.formulagen.wrapper.bridge.BrainConfiguration
import org.formulagen.wrapper.bridge.DecisionModelSelection

class DecisionModelSelectionTest {
    private val legacy = """{"version":1,"coordinator":{"id":"00000000-0000-4000-8000-000000000001","binding":null,"instructions":""},"specialists":[],"workflow":null}"""

    @Test fun decisionChoiceSurvivesNativeRoundTrip() {
        val old = Json.decodeFromString<BrainConfiguration>(legacy)
        assertNull(old.decisionModel)
        for (selection in DecisionModelSelection.entries) {
            val selected = old.copy(decisionModel = selection)
            assertEquals(selected, Json.decodeFromString<BrainConfiguration>(Json.encodeToString(selected)))
        }
    }
}
