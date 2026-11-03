package org.formulagen.wrapper.providers

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BuildGatesTest {
    @Test fun projectWorkspacesOpenOnlyInADevelopmentBuild() {
        assertTrue(projectWorkspaceEnabled(release = false, development = true))
        assertFalse(projectWorkspaceEnabled(release = false, development = false), "An unmarked build fails closed")
        assertFalse(projectWorkspaceEnabled(release = true, development = true), "A release build never offers them")
        assertFalse(projectWorkspaceEnabled(release = true, development = false))
    }
}
