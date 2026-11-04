package org.formulagen.wrapper.workspace

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The probe, a development tool: its escapes, and the release check that keeps it out of a release. */
class WorkspaceProbeTest {
    @Test fun probeEscapes() {
        assertEquals("ls\r\u001b[A\t\\x", unescape("ls\\r\\e[A\\t\\\\x"))
        assertEquals("é", unescape("\\xe9"))
        assertEquals("trailing\\", unescape("trailing\\"))
    }

    /** A renamed class would leave the release check looking for a name that no longer exists. */
    @Test fun everyClassTheReleaseCheckLooksForIsInADevelopmentBuild() {
        val entries = File("release-excluded.txt").readLines().map(String::trim).filter { it.isNotEmpty() && !it.startsWith("#") }
        val hovel = entries.filter { it.startsWith("org/formulagen/") }
        assertTrue("org/formulagen/wrapper/workspace/WorkspaceProbe" in hovel, "the probe is listed")
        val loader = javaClass.classLoader
        for (entry in hovel) assertNotNull(loader.getResource("$entry.class"), "$entry.class is not in a development build")
        val libraries = listOf("com/pty4j/PtyProcess.class", "com/jediterm/terminal/ui/TerminalPanel.class", "com/sun/jna/Native.class", "workspace/shell/hovel.bash")
        for (library in libraries) {
            assertTrue(entries.any(library::startsWith), "$library is listed")
            assertNotNull(loader.getResource(library), "$library is in a development build")
        }
    }
}
