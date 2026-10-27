package org.formulagen.wrapper.progression

import kotlin.test.*
import kotlinx.serialization.encodeToString

/** Representative three-choice offer using the current prototype tuning. */
internal fun growthFixture(revision: Long = 1, offerId: String = "ember:0") = PresentationSnapshot(
    revision, "growth", CompanionIdentity("ember", "Ember", 2, 100), 1, false,
    offer = UpgradeOffer(offerId, 2, listOf(
        UpgradeOption("attack", "Attack", "Increase weapon damage by 3% of base damage.", "+3%", "ffbd79", "100%", "103%"),
        UpgradeOption("defense", "Defense", "Add 2 armor. Incoming damage is divided by 1 + armor / 100.", "+2", "78dbcc", "0", "2"),
        UpgradeOption("energy", "Energy", "Add 3 maximum Energy for any equipment ability.", "+3", "b9a0ff", "60", "63"),
    )),
)

class PresentationControllerTest {
    /** A preview requires a separate confirmation and locks duplicate submits. */
    @Test fun previewAndConfirm() {
        val model = PresentationController { "request-1" }
        model.receive(GameMessage("state", "s", snapshot = growthFixture()))
        assertNull(model.state.value.selectedId)
        assertNull(model.begin("choose"))
        model.select("energy")
        assertNull(model.state.value.pending)
        assertEquals("energy", model.state.value.selectedId)
        val command = assertNotNull(model.begin("choose"))
        assertEquals("ember:0", command.offerId)
        assertNull(model.begin("choose"))
        model.select("attack")
        assertEquals("energy", model.state.value.selectedId)
        model.receive(GameMessage("state", "s", snapshot = growthFixture(2, "ember:1"), result = CommandResult(command.requestId, "ok")))
        assertNull(model.state.value.pending)
        assertNull(model.state.value.selectedId)
        assertEquals("Upgrade saved", model.state.value.saved)
    }

    /** Failed saves retry the original id; stale revisions require a new preview. */
    @Test fun failuresAndStaleCommands() {
        var id = 0
        val model = PresentationController { (++id).toString() }
        model.receive(GameMessage("state", "s", snapshot = growthFixture()))
        model.select("defense")
        val command = assertNotNull(model.begin("choose"))
        model.receive(GameMessage("state", "s", snapshot = growthFixture(), result = CommandResult(command.requestId, "save_failed", "Retry save")))
        assertEquals(command, model.retry())
        model.disconnected()
        assertEquals(command, model.retry())
        model.receive(GameMessage("state", "s", snapshot = growthFixture(3), result = CommandResult(command.requestId, "stale", "Changed")))
        assertNull(model.state.value.pending)
        assertNull(model.state.value.selectedId)
        assertNull(model.begin("choose"))
    }

    /** Concurrent XP refreshes exact previews without choosing on the user's behalf. */
    @Test fun backgroundRefreshAndOutOfOrderDelivery() {
        val model = PresentationController { "x" }
        model.receive(GameMessage("state", "s", snapshot = growthFixture()))
        model.select("attack")
        model.receive(GameMessage("state", "s", snapshot = growthFixture(3)))
        assertEquals("attack", model.state.value.selectedId)
        model.receive(GameMessage("state", "s", snapshot = growthFixture(2, "old")))
        assertEquals(3L, model.state.value.snapshot?.revision)
    }

    /** Session and shape validation rejects data before it reaches Compose. */
    @Test fun protocolValidation() {
        val message = GameMessage("state", "session", snapshot = growthFixture())
        val wire = PresentationProtocol.json.encodeToString(message)
        assertEquals(message, PresentationProtocol.decode(wire, "session"))
        assertFails { PresentationProtocol.decode(wire, "other") }
        assertFails { PresentationProtocol.decode(wire.replace("\"growth\"", "\"unknown\""), "session") }
        val invalid = message.copy(snapshot = growthFixture().copy(offer = growthFixture().offer?.copy(options = emptyList())))
        assertFails { PresentationProtocol.decode(PresentationProtocol.json.encodeToString(invalid), "session") }
    }
}
