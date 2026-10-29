package org.formulagen.wrapper.bridge

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A value whose key the contract makes `nullable`, not `optional`. The bridge leaves out null fields
 * (`explicitNulls = false`), so a wire class holds such a value in an [ExplicitNull], which writes
 * `null` itself and keeps the key.
 */
@Serializable(with = ExplicitNullSerializer::class)
internal class ExplicitNull<T : Any>(val value: T?)

/** Writes [ExplicitNull.value] with [inner], or `null`. */
internal class ExplicitNullSerializer<T : Any>(private val inner: KSerializer<T>) : KSerializer<ExplicitNull<T>> {
    @OptIn(ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = inner.descriptor.nullable

    @OptIn(ExperimentalSerializationApi::class)
    override fun serialize(encoder: Encoder, value: ExplicitNull<T>) =
        value.value?.let { encoder.encodeSerializableValue(inner, it) } ?: encoder.encodeNull()

    @OptIn(ExperimentalSerializationApi::class)
    override fun deserialize(decoder: Decoder): ExplicitNull<T> =
        ExplicitNull(if (decoder.decodeNotNullMark()) decoder.decodeSerializableValue(inner) else decoder.decodeNull())
}
