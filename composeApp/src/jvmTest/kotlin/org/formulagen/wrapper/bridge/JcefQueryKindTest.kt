package org.formulagen.wrapper.bridge

import org.junit.Test
import kotlin.test.assertEquals

class JcefQueryKindTest {
    private val context = "6f1c2d3e-4a5b-4c6d-8e7f-901234567890"
    private val watch2 = """{"version":2,"method":"watchContext","args":{}}"""
    private val call2 = """{"version":2,"contextId":"$context","method":"host.info","args":{}}"""

    @Test fun versionTwoWatchMustBePersistent() {
        assertEquals(JcefQueryKind.V2_WATCH, classifyJcefQuery(watch2, persistent = true))
        assertEquals(JcefQueryKind.V2_MISUSE, classifyJcefQuery(watch2, persistent = false))
    }

    @Test fun versionTwoCallsMustNotBePersistent() {
        assertEquals(JcefQueryKind.V2_CALL, classifyJcefQuery(call2, persistent = false))
        assertEquals(JcefQueryKind.V2_MISUSE, classifyJcefQuery(call2, persistent = true))
    }

    @Test fun everythingElseIsNotVersionTwo() {
        for (request in listOf(
            """{"version":1,"method":"watchContext","args":{}}""",
            """{"version":"2","method":"watchContext","args":{}}""",
            """{"method":"watchContext","args":{}}""",
            "not json",
            "[2]",
            """{"version":2,"args":{"pad":"${"a".repeat(70_000)}"}}""",
        )) assertEquals(JcefQueryKind.NOT_V2, classifyJcefQuery(request, persistent = true), request.take(40))
    }
}
