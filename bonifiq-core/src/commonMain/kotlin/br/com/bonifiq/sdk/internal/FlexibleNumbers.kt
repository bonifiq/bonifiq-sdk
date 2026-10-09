package br.com.bonifiq.sdk.internal

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Inteiros que às vezes chegam como decimal (`150.0`) ou string. Evita quebrar o parse por causa disso
 * (a API OMS da VTEX, por exemplo, faz exatamente isso).
 */
internal object FlexibleLongSerializer : KSerializer<Long> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("br.com.bonifiq.sdk.FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Long) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        val json = decoder as? JsonDecoder ?: return decoder.decodeLong()
        val primitive = json.decodeJsonElement() as? JsonPrimitive ?: return 0
        return primitive.longOrNull
            ?: primitive.doubleOrNull?.toLong()
            ?: primitive.content.replace(',', '.').toDoubleOrNull()?.toLong()
            ?: 0
    }
}
