@file:Suppress("unused")

package fr.moulou.storify.utils

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.TomlFormat
import java.nio.file.Path
import kotlin.io.path.extension

object StoreFormats {

    private val formatRegistry = mutableMapOf<String, StoreFormat>("json" to JsonFormat(), "toml" to TomlFormat())

    /** Enregistre un format pour une extension (sans le point) : il devient résolvable par les chemins, comme les formats fournis. */
    fun registerFormat(extension: String, format: StoreFormat) {
        formatRegistry[extension.lowercase()] = format
    }

    fun getFormatForPath(path: Path): StoreFormat = getFormat(path.extension)

    fun getFormatForStringPath(str: String): StoreFormat {
        val dotIndex = str.lastIndexOf('.')
        require(dotIndex in 0 until str.length - 1) { "[Storify] No file extension in '$str': pass a format explicitly, or use a registered extension (${formatRegistry.keys.sorted()})" }
        return getFormat(str.substring(dotIndex + 1))
    }

    /** Le format enregistré pour une extension ; une extension inconnue est refusée net plutôt que devinée (C-09). */
    fun getFormat(extension: String): StoreFormat = formatRegistry[extension.lowercase()]
        ?: throw IllegalArgumentException("[Storify] No format registered for extension '$extension' (registered: ${formatRegistry.keys.sorted()})")
}
