package fr.moulou.storify.formats

import fr.moulou.storify.Json5Format
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedJson5Data
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.utils.StoreFormats
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `Json5Format` (C-21) : le JSON des configs éditées à la main, par le pont `JsonElement`.
 * Le confort se décode, la sortie est idiomatique et se relit, la résolution passe par le
 * registre et par l'annotation ; et la limite assumée est épinglée : les commentaires du fichier
 * ne survivent pas à une sauvegarde (la préservation est le chantier C-26).
 */
class Json5FormatTest {

    private val format = Json5Format()
    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `l'aller-retour préserve la fixture entière`() {
        val path = newStorePath("roundtrip.json5")
        val original = TomlishData(title = "aller-retour", level = 9, tags = mutableListOf("a", "b"), limits = mutableMapOf("x" to 4))

        format.encodeToPath(TomlishData.serializer(), original, path)
        val decoded = format.decodeFromPath(TomlishData.serializer(), path)

        assertEquals(original, decoded)
    }

    @Test
    fun `le confort JSON5 se décode, commentaires, virgules traînantes, clés nues et apostrophes`() {
        val path = newStorePath("handwritten.json5")
        path.writeText(
            """
            {
              // le confort d'édition, tout en un
              title: 'écrit à la main',
              level: 7,
              tags: ['a', 'b',],
              limits: {a: 1,},
            }
            """.trimIndent()
        )

        val decoded = format.decodeFromPath(TomlishData.serializer(), path)

        assertEquals("écrit à la main", decoded.title)
        assertEquals(7, decoded.level)
        assertEquals(listOf("a", "b"), decoded.tags)
    }

    @Test
    fun `la sortie est du JSON5 idiomatique et se relit elle-même`() {
        val path = newStorePath("output.json5")
        val original = PlainData(name = "steve", count = 3)

        format.encodeToPath(PlainData.serializer(), original, path)
        val text = path.readText()

        assertTrue(text.contains("name:"))    // clé nue, sans guillemets
        assertTrue(text.contains("'steve'"))  // apostrophes simples
        assertTrue(text.contains("\n"))       // sortie indentée, pas compacte
        assertEquals(original, format.decodeFromPath(PlainData.serializer(), path))
    }

    @Test
    fun `les dossiers parents naissent à l'écriture et l'extension se déclare`() {
        val path = newStoreDir().resolve("sub").resolve("deep.json5")
        format.encodeToPath(PlainData.serializer(), PlainData(), path)
        assertTrue(Files.exists(path))
        assertEquals("json5", format.fileExtension())
    }

    @Test
    fun `un chemin json5 se résout tout seul et porte un store de bout en bout`() {
        assertInstanceOf(Json5Format::class.java, StoreFormats.getFormat("json5"))

        val path = newStorePath("store.json5")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            store.set(PlainData::count, 42)
            store.saveImmediate()
        }
        assertTrue(path.readText().contains("count:")) // le fichier est bien du JSON5, clés nues

        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { reloaded ->
            assertEquals(42, reloaded.data.count)
        }
    }

    @Test
    fun `l'annotation StoreFileFormat JSON5 bat l'extension du chemin`() {
        val path = newStorePath("data.json")
        StoreFactory.createFromConstructor<AnnotatedJson5Data>(path.toString()).use { }
        assertTrue(path.readText().contains("motto:")) // du JSON5 dans un .json : l'annotation gagne
    }

    @Test
    fun `les commentaires meurent au save, la limite documentée`() {
        val path = newStorePath("commented.json5")
        path.writeText(
            """
            {
              // le précieux commentaire de l'admin
              name: 'manuel',
              count: 5,
              tags: [],
            }
            """.trimIndent()
        )

        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            assertEquals("manuel", store.data.name) // le fichier commenté se décode très bien...
            store.set(PlainData::count, 6)
            store.saveImmediate()
        }

        val rewritten = path.readText()
        assertFalse(rewritten.contains("//"))    // ... mais la sauvegarde réencode à neuf : le commentaire est mort (C-26 le sauvera)
        assertTrue(rewritten.contains("6"))
        assertEquals(6, format.decodeFromPath(PlainData.serializer(), path).count)
    }
}
