package org.formulagen.wrapper.bridge

import kotlinx.serialization.Serializable

/** Device-local capture, independent of any conversation or list. */
interface VoiceInputCapability {
    suspend fun availability(): VoiceInputAvailability
    suspend fun start(args: VoiceInputStart): VoiceInputSnapshot
    suspend fun stop(args: VoiceInputStop): VoiceInputSnapshot
    suspend fun status(args: VoiceInputScope): VoiceInputSnapshot
}

@Serializable data class VoiceInputScope(val sessionId: String)
@Serializable data class VoiceInputStart(val sessionId: String, val locale: String? = null)
@Serializable data class VoiceInputStop(val sessionId: String, val disposition: String)
@Serializable data class VoiceInputAvailability(val state: String, val offline: Boolean, val reason: String? = null)
@Serializable data class VoiceInputSnapshot(
    val sessionId: String,
    val sequence: Int = 0,
    val state: String,
    val partial: String? = null,
    val final: String? = null,
    val reason: String? = null,
)

/** Additive methods; older hosts continue serving text and legacy conversation voice. */
object VoiceInputMethods {
    val AVAILABILITY = BridgeMethod("voiceInput.availability", NoArgs.serializer(), VoiceInputAvailability.serializer())
    val START = BridgeMethod("voiceInput.start", VoiceInputStart.serializer(), VoiceInputSnapshot.serializer()) {
        SharedRules.isRecordId(it.sessionId) && (it.locale == null || it.locale.length in 2..35 && it.locale.matches(Regex("[A-Za-z0-9-]+")))
    }
    val STOP = BridgeMethod("voiceInput.stop", VoiceInputStop.serializer(), VoiceInputSnapshot.serializer()) {
        SharedRules.isRecordId(it.sessionId) && it.disposition in setOf("keep", "discard")
    }
    val STATUS = BridgeMethod("voiceInput.status", VoiceInputScope.serializer(), VoiceInputSnapshot.serializer()) { SharedRules.isRecordId(it.sessionId) }
    fun pack(input: Capability<VoiceInputCapability>): CapabilityPack = binder.pack(input)
    internal val binder = CapabilityBinder("voiceInput", listOf<CapabilityHandler<VoiceInputCapability>>(
        AVAILABILITY.handle { input, _ -> input.availability() }, START.handle { input, args -> input.start(args) },
        STOP.handle { input, args -> input.stop(args) }, STATUS.handle { input, args -> input.status(args) },
    ))
}

/** Bounded snapshots retain stop-before-start tombstones; callers serialize access. */
class VoiceInputSessions {
    private val entries = linkedMapOf<String, VoiceInputSnapshot>()
    var owner: String? = null
        private set

    fun contains(id: String): Boolean = entries.containsKey(id)
    fun read(id: String): VoiceInputSnapshot = entries[id] ?: VoiceInputSnapshot(id, state = "cancelled")
    fun begin(id: String): VoiceInputSnapshot {
        entries[id]?.let { return it }
        if (owner != null) return update(id, "error", reason = "busy")
        owner = id
        return update(id, "starting")
    }
    fun owns(id: String): Boolean = owner == id && read(id).state in setOf("starting", "listening", "stopping")
    fun update(id: String, state: String, partial: String? = null, final: String? = null, reason: String? = null): VoiceInputSnapshot {
        val old = entries[id]
        if (old != null && old.state in setOf("done", "cancelled", "error")) return old
        val next = VoiceInputSnapshot(id, (old?.sequence ?: 0) + 1, state,
            partial?.take(CompanionLimits.MESSAGE) ?: old?.partial,
            final?.take(CompanionLimits.MESSAGE), reason)
        entries[id] = next
        if (state in setOf("done", "cancelled", "error") && owner == id) owner = null
        while (entries.size > 32) entries.keys.firstOrNull { it != owner }?.let(entries::remove)
        return next
    }
    fun cancelAll() { owner?.let { update(it, "cancelled") } }
}
