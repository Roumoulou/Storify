// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.formats

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.RandomData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * `JsonFormat` : l'aller-retour de torture (primitives signées et non signées, dates, collections,
 * imbrications), et les réglages observables de son instance Json (le strict par défaut, `lenient()`, les flottants spéciaux).
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
    fun `par défaut, un commentaire ou une clé sans guillemets sont refusés`() {
        val commented = newStorePath("comments.json").also { it.writeText("{\n  // un commentaire\n  \"name\": \"c\",\n  \"count\": 1,\n  \"tags\": []\n}") }
        val bare = newStorePath("bare.json").also { it.writeText("{\n  name: c,\n  count: 1,\n  tags: []\n}") }

        assertThrows(SerializationException::class.java) { format.decodeFromPath(PlainData.serializer(), commented) }
        assertThrows(SerializationException::class.java) { format.decodeFromPath(PlainData.serializer(), bare) }
    }

    @Test
    fun `lenient() tolère les commentaires et les clés et chaînes sans guillemets`() {
        val lenient = JsonFormat.lenient()
        val commented = newStorePath("comments.json").also { it.writeText("{\n  // un commentaire\n  \"name\": \"c\",\n  \"count\": 1,\n  \"tags\": []\n}") }
        val bare = newStorePath("bare.json").also { it.writeText("{\n  name: c,\n  count: 1,\n  tags: []\n}") }

        assertEquals("c", lenient.decodeFromPath(PlainData.serializer(), commented).name)
        assertEquals("c", lenient.decodeFromPath(PlainData.serializer(), bare).name)
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

    @Test
    fun `un fichier enregistré avec un BOM UTF-8 se décode`() {
        val path = newStorePath("bom.json")
        path.writeText("\uFEFF{\n  \"name\": \"bom\",\n  \"count\": 1,\n  \"tags\": []\n}")

        assertEquals("bom", format.decodeFromPath(PlainData.serializer(), path).name)
    }
}
