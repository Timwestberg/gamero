package org.formulagen.wrapper.bridge

import kotlin.test.*
import kotlinx.serialization.json.*

class ProviderUsageWireTest {
    @Test fun usageReadsTheSharedContractFixtureAndOmitsUnknownWindows() {
        val fixture = Json.parseToJsonElement(HostBridgeFixtures.ENVELOPES.getValue("success-providers-usage.json"))
            .jsonObject.getValue("value").jsonObject.getValue("result")
        assertEquals(ProviderUsage(RuntimeId.CODEX, 1793200000000, ProviderUsageWindow(0.0, 1793203600), ProviderUsageWindow(76.5)),
            bridgeJson.decodeFromJsonElement(ProviderUsage.serializer(), fixture))
        val missing = bridgeJson.encodeToJsonElement(ProviderUsage.serializer(), ProviderUsage(RuntimeId.CLAUDE_CODE, 1793200000000)).jsonObject
        assertFalse(missing.containsKey("session")); assertFalse(missing.containsKey("weekly"))
        assertFails { ProviderUsageWindow(Double.NaN) }
        assertFails { ProviderUsageWindow(-1.0) }
        assertFails { ProviderUsageWindow(101.0) }
        assertFails { ProviderUsageWindow(0.0, -1) }
    }
}
