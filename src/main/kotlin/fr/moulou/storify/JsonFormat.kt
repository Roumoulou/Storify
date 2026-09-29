// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify

import fr.moulou.storify.utils.DeepCopier
import fr.moulou.storify.utils.JsonTreeCopier
import fr.moulou.storify.utils.withoutUtf8Bom
import fr.moulou.storify.validation.ErrorLineLocator
import fr.moulou.storify.validation.JsonLineLocator
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

class JsonFormat(
    private val json: Json = Json {
        prettyPrint = true
        isLenient = true
        encodeDefaults = true
        allowStructuredMapKeys = true
        allowSpecialFloatingPointValues = true
        allowComments = true
    }
) : StoreFormat {

    /** Le copieur profond des stores JSON : l'arbre JSON de ce même `Json` (C-29). */
    private val copier = JsonTreeCopier(json)

    override fun deepCopier(): DeepCopier = copier

    override fun lineLocator(): ErrorLineLocator = JsonLineLocator

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.inputStream().withoutUtf8Bom().use { stream -> json.decodeFromStream(deserializer, stream) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.outputStream().use { stream -> json.encodeToStream(serializer, data, stream) }
    }

    override fun fileExtension(): String = "json"

    fun underlyingJson(): Json = json
}
