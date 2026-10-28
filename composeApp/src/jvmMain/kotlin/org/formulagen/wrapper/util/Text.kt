package org.formulagen.wrapper.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** At most [limit] characters, an ellipsis marking a cut. */
internal fun String.shortened(limit: Int): String = if (length <= limit) this else take(limit - 1).trimEnd() + "…"

/** The string value at [key], or null when it is missing or not a string. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
