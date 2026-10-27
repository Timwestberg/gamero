package org.formulagen.wrapper.progression

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Versioned, bounded wire messages shared by every presentation host. */
object PresentationProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 262144
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Reject malformed identities, views and offers at the transport boundary. */
    fun decode(text: String, session: String): GameMessage {
        require(text.length <= MAX_MESSAGE_BYTES) { "Presentation message is too large" }
        val value = json.decodeFromString<GameMessage>(text)
        require(value.version == VERSION && value.session == session) { "Presentation session changed" }
        require(value.kind in setOf("hello", "pong", "state", "input")) { "Unknown presentation message" }
        value.snapshot?.let { snapshot ->
            require(snapshot.revision >= 0 && snapshot.pending >= 0)
            require(snapshot.view in setOf("closed", "growth", "review", "saved"))
            require(snapshot.companion.id.isNotBlank() && snapshot.companion.level > 0)
            snapshot.offer?.let { offer ->
                require(offer.id.isNotBlank() && offer.level > 1 && offer.options.size == 3)
                require(offer.options.map { it.id }.distinct().size == 3)
                require(offer.options.all { it.id.isNotBlank() && it.name.isNotBlank() })
            }
            require(snapshot.view != "review" || snapshot.review != null)
            snapshot.review?.let { require(it.outcome in setOf("success", "failed") && it.xp >= 0) }
        }
        require(value.kind != "state" || value.snapshot != null)
        return value
    }
}

/** [color] is an optional `rrggbb` accent the game chooses for this companion, like [UpgradeOption.color]. */
@Serializable
data class CompanionIdentity(val id: String, val name: String, val level: Int, val xp: Long, val color: String? = null)
@Serializable
data class UpgradeOption(val id: String, val name: String, val description: String, val increase: String, val color: String, val before: String, val after: String)
@Serializable
data class UpgradeOffer(val id: String, val level: Int, val options: List<UpgradeOption>)
@Serializable
data class ExpeditionReview(val id: String, val outcome: String, val source: String, val highlight: String, val xp: Long, val retained: List<String>, val banked: List<String>, val lost: List<String>, val broughtLost: List<String> = emptyList(), val temporaryExpired: Boolean)
@Serializable
data class PresentationSnapshot(val revision: Long, val view: String, val companion: CompanionIdentity, val pending: Int, val canRespec: Boolean, val offer: UpgradeOffer? = null, val review: ExpeditionReview? = null, val appliedOption: UpgradeOption? = null)
@Serializable
data class PresentationCommand(val requestId: String, val revision: Long, val action: String, val offerId: String = "", val optionId: String = "")
@Serializable
data class CommandResult(val requestId: String = "", val status: String = "", val message: String = "")
@Serializable
data class GameMessage(val kind: String, val session: String, val version: Int = PresentationProtocol.VERSION, val snapshot: PresentationSnapshot? = null, val result: CommandResult? = null, val input: String = "")
@Serializable
data class HostMessage(val kind: String, val session: String, val version: Int = PresentationProtocol.VERSION, val command: PresentationCommand? = null)

data class PresentationState(val snapshot: PresentationSnapshot? = null, val selectedId: String? = null, val pending: PresentationCommand? = null, val error: String? = null, val saved: String? = null)
