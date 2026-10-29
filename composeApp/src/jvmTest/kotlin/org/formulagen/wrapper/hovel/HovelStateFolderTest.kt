package org.formulagen.wrapper.hovel

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class HovelStateFolderTest {
    private fun support(): Path = Files.createTempDirectory("hovel-support")

    /** A state folder as it was named before, by the page's full URL, holding a registration. */
    private fun legacyFolder(support: Path, url: String, registration: String): Path =
        support.resolve("hovel-${HovelCompanion.sha256(url)}").also {
            Files.createDirectories(it)
            Files.writeString(it.resolve("registration.json"), registration)
        }

    @Test fun oneSiteHasOneFolderWhateverItsPage() {
        val support = support()
        val folder = hovelStateFolder(support, "http://127.0.0.1:8080/hovel")
        assertEquals(support.resolve("hovel-${HovelCompanion.sha256("http://127.0.0.1:8080")}"), folder)
        assertEquals(folder, hovelStateFolder(support, "http://127.0.0.1:8080/hovel.html"))
        assertEquals(folder, hovelStateFolder(support, "http://127.0.0.1:8080/hovel?from=old-link"))
        assertNotEquals(folder, hovelStateFolder(support, "http://127.0.0.1:8081/hovel"))
        assertNotEquals(folder, hovelStateFolder(support, "https://127.0.0.1:8080/hovel"))
    }

    @Test fun theOldDefaultPagesFolderMovesOnceAndKeepsItsRegistration() {
        val support = support()
        val legacy = legacyFolder(support, "http://127.0.0.1:8080/hovel.html", """{"id":"old-host"}""")
        val folder = hovelStateFolder(support, "http://127.0.0.1:8080/hovel")
        assertFalse(Files.exists(legacy))
        assertEquals("""{"id":"old-host"}""", Files.readString(folder.resolve("registration.json")))
        assertEquals(folder, hovelStateFolder(support, "http://127.0.0.1:8080/hovel.html"))
    }

    @Test fun aConfiguredPagesOwnFolderMovesFirst() {
        val support = support()
        legacyFolder(support, "https://dev.example/hovel.html", """{"id":"default-page"}""")
        val configured = legacyFolder(support, "https://dev.example/play/hovel.html", """{"id":"configured-page"}""")
        val folder = hovelStateFolder(support, "https://dev.example/play/hovel.html")
        assertFalse(Files.exists(configured))
        assertEquals("""{"id":"configured-page"}""", Files.readString(folder.resolve("registration.json")))
    }

    @Test fun anExistingOriginFolderWinsAndTheOldOneStays() {
        val support = support()
        val legacy = legacyFolder(support, "http://127.0.0.1:8080/hovel.html", """{"id":"old-host"}""")
        val folder = support.resolve("hovel-${HovelCompanion.sha256("http://127.0.0.1:8080")}")
        Files.createDirectories(folder)
        assertEquals(folder, hovelStateFolder(support, "http://127.0.0.1:8080/hovel"))
        assertTrue(Files.exists(legacy))
        assertFalse(Files.exists(folder.resolve("registration.json")))
    }

    @Test fun withNothingToMoveTheFolderIsOnlyNamed() {
        val support = support()
        val folder = hovelStateFolder(support, "http://127.0.0.1:8080/hovel")
        assertFalse(Files.exists(folder))
    }
}
