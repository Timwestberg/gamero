package org.formulagen.wrapper.util

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/* `shortened` and `takeWhole`, which never split a surrogate pair, are in commonMain's `util/WholeText.kt`. */

/** The string value at [key], or null when it is missing or not a string. */
internal fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
