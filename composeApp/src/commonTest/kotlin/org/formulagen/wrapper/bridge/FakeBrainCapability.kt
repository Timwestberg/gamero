package org.formulagen.wrapper.bridge

import kotlinx.serialization.json.*

/** Contract dispatch without native provider effects. */
class FakeBrainCapability : BrainCapability {
    override suspend fun call(method: String, args: JsonObject): JsonElement = when (method) {
        "save" -> buildJsonObject { put("status", "saved"); put("document", buildJsonObject { put("revision", 1); put("configuration", args.getValue("configuration")) }); put("issues", JsonArray(emptyList())) }
        "read" -> buildJsonObject { put("status", "absent") }
        "open" -> brainWire(BrainSnapshot(0, null, emptyList(), null, emptyList(), emptyList(), null, ChatVoice(), "0"))
        else -> buildJsonObject {}
    }
}
