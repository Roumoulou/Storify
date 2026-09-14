package fr.moulou.storify.formats

import fr.moulou.storify.TomlFormat
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `TomlFormat` : l'aller-retour sur une fixture aux types que TOML représente sans détour,
 * et le réglage `ignoreUnknownKeys` de son instance.
 */
class TomlFormatTest {

    private val format = TomlFormat()

    @Test
    fun `l'aller-retour TOML préserve la fixture entière`() {
        val path = newStorePath("roundtrip.toml")
        val original = TomlishData(title = "aller-retour", level = 9, tags = mutableListOf("a", "b"), limits = mutableMapOf("x" to 4))

        format.encodeToPath(TomlishData.serializer(), original, path)
        val decoded = format.decodeFromPath(TomlishData.serializer(), path)

        assertEquals(original, decoded)
    }

    @Test
    fun `ignoreUnknownKeys tolère une clé inconnue dans le fichier`() {
        val path = newStorePath("unknown.toml")
        path.writeText("unknown_key = \"extra\"\ntitle = \"y\"\n")

        val decoded = format.decodeFromPath(TomlishData.serializer(), path)

        assertEquals("y", decoded.title) // la clé en trop est ignorée, le reste suit les défauts
        assertEquals(3, decoded.level)
    }

    @Test
    fun `les dossiers parents naissent à l'écriture et l'extension se déclare`() {
        val path = newStoreDir().resolve("sub").resolve("deep.toml")
        format.encodeToPath(TomlishData.serializer(), TomlishData(), path)
        assertTrue(Files.exists(path))
        assertEquals("toml", format.fileExtension())
    }
}
