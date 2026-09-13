package fr.moulou.storify.utils

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray

@OptIn(ExperimentalSerializationApi::class)
val internalCopyCbor = Cbor { encodeDefaults = true }

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> T.deepCopyViaCbor(): T {
    val encoded = internalCopyCbor.encodeToByteArray(this)
    return internalCopyCbor.decodeFromByteArray(encoded)
}

@OptIn(ExperimentalSerializationApi::class)
inline fun <reified T> deepCopyValue(value: T): T {
    val encoded = internalCopyCbor.encodeToByteArray(value)
    return internalCopyCbor.decodeFromByteArray(encoded)
}
