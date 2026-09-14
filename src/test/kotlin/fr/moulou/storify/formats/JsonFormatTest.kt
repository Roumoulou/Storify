package fr.moulou.storify.formats

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `JsonFormat` : l'aller-retour de torture (primitives signées et non signées, dates, collections,
 * imbrications), et les réglages observables de son instance Json (commentaires, flottants spéciaux).
 */
class JsonFormatTest {

    private val format = JsonFormat()

    @Test
    fun `l'aller-retour de torture préserve primitives, dates, collections et imbrications`() {
        val path = newStorePath("torture.json")
        val original = RandomData.default()

        format.encodeToPath(RandomData.serializer(), original, path)
        val decoded = format.decodeFromPath(RandomData.serializer(), path)

        // Pièce par pièce : les champs Array cassent l'equals des data classes, on compare le contenu.
        assertEquals(original.primitivesBlockVar, decoded.primitivesBlockVar)
        assertEquals(original.dataBlockVar, decoded.dataBlockVar)
        assertEquals(original.complexObject, decoded.complexObject)
        assertEquals(original.mutableMapOfHomeByIndexVar, decoded.mutableMapOfHomeByIndexVar)
        assertTrue(original.intArrayVar.contentEquals(decoded.intArrayVar))
    }

    @Test
    fun `les commentaires sont tolérés au décodage`() {
        val path = newStorePath("comments.json")
        path.writeText("{\n  // un commentaire, toléré par allowComments\n  \"name\": \"c\",\n  \"count\": 1,\n  \"tags\": []\n}")

        val decoded = format.decodeFromPath(PlainData.serializer(), path)

        assertEquals("c", decoded.name)
    }

    @Test
    fun `les flottants spéciaux font l'aller-retour`() {
        val path = newStorePath("nan.json")
        val original = TomlishData(ratio = Double.NaN)

        format.encodeToPath(TomlishData.serializer(), original, path)
        val decoded = format.decodeFromPath(TomlishData.serializer(), path)

        assertTrue(decoded.ratio.isNaN())
    }

    @Test
    fun `les dossiers parents naissent à l'écriture et l'extension se déclare`() {
        val path = newStoreDir().resolve("sub").resolve("deep.json")
        format.encodeToPath(PlainData.serializer(), PlainData(), path)
        assertTrue(Files.exists(path))
        assertEquals("json", format.fileExtension())
    }
}
