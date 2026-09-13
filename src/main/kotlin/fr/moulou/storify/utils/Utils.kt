@file:Suppress("unused")

package fr.moulou.storify.utils

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.TomlFormat
import java.nio.file.Path
import kotlin.io.path.extension

object Utils {

    private val defaultFormat: StoreFormat<*> = JsonFormat()

    private val formatRegistry = mutableMapOf<String, StoreFormat<*>>("json" to JsonFormat(), "toml" to TomlFormat())

    fun registerFormat(extension: String, format: StoreFormat<*>) { formatRegistry[extension.lowercase()] = format }

    fun getFormatForPath(path: Path): StoreFormat<*> = formatRegistry[path.extension.lowercase()] ?: defaultFormat

     fun getFormatForStringPath(str: String): StoreFormat<*> {
        val dotIndex = str.lastIndexOf('.')
        if (dotIndex == -1) return defaultFormat
        val extension = str.substring(dotIndex + 1).lowercase()
        return formatRegistry[extension] ?: defaultFormat
    }

    fun getFormat(extension: String): StoreFormat<*> = formatRegistry[extension.lowercase()] ?: defaultFormat

}