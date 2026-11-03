package org.formulagen.wrapper.companion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopEventScriptTest {
    /** Event contents cannot become executable code, including quotes, newlines and closing markup. */
    @Test fun eventArgumentsRoundTripAsStrings() {
        val context = "context-'\"\\\n"
        val event = "{\"text\":\"');window.unwanted=true;//</script>\"}\n\u2028"
        val script = desktopEventScript(context, event)
        val arguments = script.removePrefix("window.gameroDesktopEvent?.(").removeSuffix(");")
        val values = Json.parseToJsonElement("[$arguments]").jsonArray
        assertEquals(listOf(context, event), values.map { it.jsonPrimitive.content })
    }
}
