package org.formulagen.wrapper.bridge

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.test.assertFailsWith

class CapabilityBinderTest {
    @Test
    fun aProductThatRedeclaresAMethodFailsAtStartUp() {
        val copy = CapabilityBinder(listOf(LinkMethods.OPEN.handle { _: LinkOpener, _ -> NoResult }))
        val capabilities = HostCapabilities(HostPlatform.DESKTOP, "1.0.0", Capability.Unsupported, Capability.Unsupported, Capability.Unsupported)
        val scope = CoroutineScope(Job())
        assertFailsWith<IllegalStateException> {
            BridgeRouter(capabilities, listOf(copy.pack(Capability.Supported(LinkOpener { }))), scope, transport = {})
        }
        scope.coroutineContext[Job]!!.cancel()
    }
}
