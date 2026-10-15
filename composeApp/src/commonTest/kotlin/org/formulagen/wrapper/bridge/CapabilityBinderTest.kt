package org.formulagen.wrapper.bridge

import kotlin.test.Test
import kotlin.test.assertFailsWith

class CapabilityBinderTest {
    @Test
    fun handlersThatDoNotMatchTheDeclaredMethodsFailAtStartUp() {
        val binder = CapabilityBinder<Haptics>(
            methods = listOf(HapticMethods.PLAY),
            handlers = { emptyList() },
        )
        assertFailsWith<IllegalStateException> { binder.bind(Capability.Supported(Haptics { })) }
    }
}
