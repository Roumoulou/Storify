package fr.moulou.storify

import kotlinx.serialization.ExperimentalSerializationApi
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
) : StoreFormat<Json> {

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified DATA> decodeFromPath(path: Path): DATA {
        return underlyingJson().decodeFromStream(path.inputStream())
    }

    @OptIn(ExperimentalSerializationApi::class)
    inline fun <reified DATA> encodeToPath(data: DATA, path: Path) {
        path.parent?.createDirectories()
        underlyingJson().encodeToStream(data, path.outputStream())
    }

    override fun fileExtension(): String = "json"

    fun underlyingJson(): Json = json
}