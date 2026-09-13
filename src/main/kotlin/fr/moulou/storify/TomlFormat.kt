package fr.moulou.storify

import dev.eav.tomlkt.Toml
import dev.eav.tomlkt.decodeFromNativeReader
import dev.eav.tomlkt.encodeToNativeWriter
import java.nio.file.Path
import kotlin.io.path.bufferedReader
import kotlin.io.path.bufferedWriter
import kotlin.io.path.createDirectories

class TomlFormat(
    private val toml: Toml = Toml { ignoreUnknownKeys = true }
) : StoreFormat<Toml> {

    inline fun <reified DATA> decodeFromPath(path: Path): DATA {
        return path.bufferedReader().use { reader -> underlyingToml().decodeFromNativeReader(reader) }
    }

    inline fun <reified DATA> encodeToPath(data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.bufferedWriter().use { writer -> underlyingToml().encodeToNativeWriter(data, writer) }
    }

    override fun fileExtension(): String = "toml"

    fun underlyingToml(): Toml = toml
}