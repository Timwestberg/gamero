package org.formulagen.wrapper.hovel

import kotlinx.serialization.json.JsonObject

/** Native bridge surface shared by live composition and the process-free smoke companion. */
interface HovelNativeCompanion : AutoCloseable {
    val status: String
    val approval: ConnectionApproval?
    suspend fun dispatch(method: String, args: JsonObject): JsonObject
    fun invalidatePending()
}
