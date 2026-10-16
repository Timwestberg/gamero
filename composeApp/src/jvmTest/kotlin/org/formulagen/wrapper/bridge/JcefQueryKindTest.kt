package org.formulagen.wrapper.bridge

import org.junit.Test
import kotlin.test.assertEquals

class JcefQueryKindTest {
    private val context = "6f1c2d3e-4a5b-4c6d-8e7f-901234567890"
    private val watch2 = """{"version":2,"method":"watchContext","args":{}}"""
    private val call2 = """{"version":2,"contextId":"$context","method":"host.info","args":{}}"""

    @Test fun versionTwoWatchMustBePersistent() {
        assertEquals(JcefQueryKind.WATCH, classifyJcefQuery(watch2, persistent = true))
        assertEquals(JcefQueryKind.INVALID, classifyJcefQuery(watch2, persistent = false))
    }

    @Test fun versionTwoCallsMustNotBePersistent() {
        assertEquals(JcefQueryKind.CALL, classifyJcefQuery(call2, persistent = false))
        assertEquals(JcefQueryKind.INVALID, classifyJcefQuery(call2, persistent = true))
    }

    @Test fun everythingElseIsInvalid() {
        for (request in listOf(
            """{"version":1,"method":"watchContext","args":{}}""",
            """{"version":1,"method":"capabilities","args":{},"contextId":"$context"}""",
            """{"version":"2","method":"watchContext","args":{}}""",
            """{"method":"watchContext","args":{}}""",
            "not json",
            "[2]",
            """{"version":2,"args":{"pad":"${"a".repeat(70_000)}"}}""",
        )) for (persistent in listOf(true, false)) {
            assertEquals(JcefQueryKind.INVALID, classifyJcefQuery(request, persistent), request.take(40))
        }
    }
}
