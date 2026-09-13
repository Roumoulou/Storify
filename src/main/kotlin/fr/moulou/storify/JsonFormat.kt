package fr.moulou.storify

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

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.inputStream().use { stream -> json.decodeFromStream(deserializer, stream) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.outputStream().use { stream -> json.encodeToStream(serializer, data, stream) }
    }

    override fun fileExtension(): String = "json"

    fun underlyingJson(): Json = json
}
