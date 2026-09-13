@file:Suppress("unused")

package fr.moulou.storify.serializers

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

object JsonPrimitiveAsStringSerializer : KSerializer<JsonPrimitive> {
    override val descriptor = PrimitiveSerialDescriptor("JsonPrimitive", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: JsonPrimitive) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): JsonPrimitive =
        Json.parseToJsonElement(decoder.decodeString()) as JsonPrimitive
}
