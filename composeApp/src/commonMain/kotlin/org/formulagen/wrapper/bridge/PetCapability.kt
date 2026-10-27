package org.formulagen.wrapper.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The desktop pet. A page picks an action, never a project, executable or path, so only
 * [configure] takes arguments: it saves the companion the pet shows and does not start a hidden
 * pet. Every action answers the pet's status.
 */
interface PetCapability {
    suspend fun status(): PetStatus
    suspend fun show(): PetStatus
    suspend fun hide(): PetStatus
    suspend fun configure(companion: PetCompanion): PetStatus
}

/** Bounds of the pet's display state. */
object PetLimits { // mirrors PET_LIMITS
    const val MESSAGE = 300
}

@Serializable
enum class PetState {
    @SerialName("running") RUNNING,
    @SerialName("stopped") STOPPED,
    @SerialName("unavailable") UNAVAILABLE,
    @SerialName("error") ERROR,
}

/** The pet's colors as `#rrggbb`. The snake_case names are the native appearance file's own. */
@Serializable
data class PetAppearance(
    @SerialName("eye_color") val eyeColor: String,
    @SerialName("face_color") val faceColor: String,
    @SerialName("frame_color") val frameColor: String,
    @SerialName("shell_color") val shellColor: String,
) {
    internal fun colors() = listOf(eyeColor, faceColor, frameColor, shellColor)
}

/** The companion the desktop pet shows. Native code saves it to a fixed file of its own. */
@Serializable
data class PetCompanion(val appearance: PetAppearance, val id: String, val name: String)

@Serializable
data class PetConfigureArgs(val companion: PetCompanion)

/** [companionId] and [name] belong to the saved companion, when one is saved. */
@Serializable
data class PetStatus(
    val state: PetState,
    val companionId: String? = null,
    val name: String? = null,
    val message: String? = null,
)

/** Mirrors event-contracts/host-bridge/pet.ts. */
object PetMethods {
    private val COLOR = Regex("^#[0-9a-fA-F]{6}$")

    /** The contract's petCompanion: any-case UUID, a companion name, and four `#rrggbb` colors. */
    internal fun isValid(companion: PetCompanion) = BridgeProtocol.UUID_PATTERN.matches(companion.id) &&
        SharedRules.isCompanionName(companion.name) && companion.appearance.colors().all(COLOR::matches)

    val STATUS = BridgeMethod("pet.status", NoArgs.serializer(), PetStatus.serializer())
    val SHOW = BridgeMethod("pet.show", NoArgs.serializer(), PetStatus.serializer())
    val HIDE = BridgeMethod("pet.hide", NoArgs.serializer(), PetStatus.serializer())
    val CONFIGURE = BridgeMethod("pet.configure", PetConfigureArgs.serializer(), PetStatus.serializer()) { isValid(it.companion) }

    fun pack(pet: Capability<PetCapability>): CapabilityPack = binder.pack(pet)

    internal val binder = CapabilityBinder("pet", listOf<CapabilityHandler<PetCapability>>(
        STATUS.handle { pet, _ -> pet.status() },
        SHOW.handle { pet, _ -> pet.show() },
        HIDE.handle { pet, _ -> pet.hide() },
        CONFIGURE.handle { pet, args -> pet.configure(args.companion) },
    ))
}
