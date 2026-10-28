package org.formulagen.wrapper.providers

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.formulagen.wrapper.bridge.ModelEffort

/** Normalize only known, string-valued effort levels at the runtime boundary. */
internal fun providerEffort(value: JsonElement?): ModelEffort? =
    (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { raw -> ModelEffort.entries.find { it.value == raw } }

/** Null means the runtime supplied no capabilities; an empty list means none are usable. */
internal fun providerEfforts(values: List<JsonElement?>?): List<ModelEffort>? = values?.mapNotNull(::providerEffort)?.distinct()
