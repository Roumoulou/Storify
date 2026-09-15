package fr.moulou.storify.formats

import fr.moulou.storify.Json5Format
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * La sauvegarde préservante de C-26, au ras du format : la réconciliation ne réécrit que ce qui
 * change, l'idempotence est à l'octet, et les replis sont nets.
 */
class Json5PreservingSaveTest {

    private val format = Json5Format()

    private fun preserve(data: PlainData, path: Path, previousText: String?) =
        format.encodeToPathPreserving(PlainData.serializer(), data, path, previousText)

    @Test
    fun `une valeur changée se retouche, commentaires et style restent`() {
        val path = newStorePath("value.json5")
        val previous = "{\n  // garde-moi\n  name: 'manuel',\n  count: 5,\n  tags: ['a'],\n}"

        preserve(PlainData(name = "manuel", count = 6, tags = mutableListOf("a")), path, previous)

        val text = path.readText()
        assertTrue(text.contains("// garde-moi"))
        assertTrue(text.contains("name: 'manuel'")) // le style d'origine, intact
        assertTrue(text.contains("count: 6"))
        assertFalse(text.contains("count: 5"))
    }

    @Test
    fun `un save sans changement laisse le fichier identique à l'octet`() {
        val path = newStorePath("idempotent.json5")
        val previous = "{\n  // stable\n  name: 'x',\n  count: 1,\n  tags: [],\n}"

        preserve(PlainData(name = "x", count = 1, tags = mutableListOf()), path, previous)

        assertEquals(previous, path.readText())
    }

    @Test
    fun `une clé disparue s'en va avec son commentaire, une nouvelle s'ajoute`() {
        val path = newStorePath("keys.json5")
        val previous = "{\n  name: 'x',\n  count: 1,\n  // obsolète, et son commentaire avec\n  legacyKey: 42,\n}"

        preserve(PlainData(name = "x", count = 1, tags = mutableListOf("t")), path, previous)

        val text = path.readText()
        assertFalse(text.contains("legacyKey"))
        assertFalse(text.contains("obsolète")) // le commentaire est parti avec sa clé
        assertTrue(text.contains("tags"))
        assertTrue(text.contains("'t'"))
    }

    @Test
    fun `un tableau modifié se remplace entier, le commentaire intérieur meurt, l'extérieur survit`() {
        val path = newStorePath("array.json5")
        val previous = "{\n  name: 'x',\n  count: 1,\n  // dehors, je survis\n  tags: [\n    'a', // dedans, je meurs\n  ],\n}"

        preserve(PlainData(name = "x", count = 1, tags = mutableListOf("a", "b")), path, previous)

        val text = path.readText()
        assertTrue(text.contains("// dehors, je survis"))
        assertFalse(text.contains("dedans"))
        assertTrue(text.contains("'b'"))
    }

    @Test
    fun `un existant invalide vaut encode à neuf`() {
        val path = newStorePath("broken.json5")

        preserve(PlainData(name = "neuf", count = 3), path, "{ cassé, pas du JSON5")

        assertEquals("neuf", format.decodeFromPath(PlainData.serializer(), path).name)
    }
}
