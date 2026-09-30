// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

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
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `Json5Format` (C-21) : le JSON des configs éditées à la main, par le pont `JsonElement`.
 * Le confort se décode, la sortie est idiomatique et se relit, la résolution passe par le
 * registre et par l'annotation ; les commentaires du fichier survivent à une sauvegarde (C-26) ;
 * et `NaN` comme les infinis s'écrivent et se relisent, comme en JSON (C-42).
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
    fun `les commentaires survivent au save, seule la valeur changée se réécrit`() {
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
        assertTrue(rewritten.contains("// le précieux commentaire de l'admin")) // ... et la sauvegarde le préserve (C-26)
        assertTrue(rewritten.contains("name: 'manuel'"))                        // le style d'origine, intact
        assertTrue(rewritten.contains("count: 6"))
        assertEquals(6, format.decodeFromPath(PlainData.serializer(), path).count)
    }

    // ─── NaN et les infinis (C-42) : du JSON5 valide, qui ne doit pas faire échouer la sauvegarde d'un store ───

    @Test
    fun `NaN et les infinis se sauvent et se relisent par un store, comme en JSON`() {
        for (value in listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)) {
            val path = newStorePath("special.json5")
            StoreFactory.create<TomlishData>(path.toString(), config = noAutoSave).use { store ->
                store.set(TomlishData::ratio, value)
                store.saveImmediate() // un refus ferait échouer chaque sauvegarde du store, loin du code qui a produit la valeur
            }
            StoreFactory.create<TomlishData>(path.toString(), config = noAutoSave).use { reloaded ->
                assertEquals(value, reloaded.data.ratio)
            }
        }
    }

    @Test
    fun `NaN et les infinis écrits à la main se décodent, le signe plus compris`() {
        val handWritten = listOf("NaN" to Double.NaN, "Infinity" to Double.POSITIVE_INFINITY, "+Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY)
        for ((literal, expected) in handWritten) {
            val path = newStorePath("handwritten-special.json5").also { it.writeText("{\n  title: 'x',\n  ratio: $literal,\n}") }
            assertEquals(expected, format.decodeFromPath(TomlishData.serializer(), path).ratio)
        }
    }

    @Test
    fun `un save sans changement laisse un fichier à valeur spéciale identique à l'octet`() {
        val path = newStorePath("special-idempotent.json5")
        StoreFactory.create<TomlishData>(path.toString(), config = noAutoSave).use { store ->
            store.set(TomlishData::ratio, Double.NaN)
            store.saveImmediate()
            val first = path.readText()

            store.saveImmediate() // la réconciliation (C-26) ne voit pas de changement entre NaN et NaN

            assertTrue(first.contains("ratio: NaN"))
            assertEquals(first, path.readText())
        }
    }
}
