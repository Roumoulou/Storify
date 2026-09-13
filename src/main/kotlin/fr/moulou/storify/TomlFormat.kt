package fr.moulou.storify

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.decodeFromNativeReader
import dev.eav.tomlkt.encodeToNativeWriter
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories

class TomlFormat(
    private val toml: Toml = Toml { ignoreUnknownKeys = true }
) : StoreFormat {

    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.bufferedReader().use { reader -> toml.decodeFromNativeReader(deserializer, reader) }
    }

    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.bufferedWriter().use { writer -> toml.encodeToNativeWriter(serializer, data, writer) }
    }

    override fun fileExtension(): String = "toml"

    fun underlyingToml(): Toml = toml
}
