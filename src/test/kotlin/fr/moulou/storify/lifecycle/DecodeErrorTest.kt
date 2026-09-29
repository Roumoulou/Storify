// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.lifecycle

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.StorifyException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

/**
 * Les fautes du fichier (C-33) : un fichier qui ne se décode pas lève `StoreDecodeException`, chemin et format dans le message, la ligne au
 * mieux, la cause du parseur conservée ; elle et `ValidationException` descendent de `StorifyException`, un seul catch pour tout ce qui vient
 * du fichier.
 */
class DecodeErrorTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `un JSON mal formé à l'ouverture nomme le fichier, le format, la ligne, et garde la cause`() {
        val path = newStorePath("broken.json")
        path.writeText("{\n  \"name\": \"s\",\n  \"count\": 1,\n  \"tags\": [],\n}") // la virgule de trop

        val failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave) }

        assertEquals(path.toAbsolutePath().normalize(), failure.path)
        assertTrue(failure.message!!.contains("broken.json"))
        assertTrue(failure.message!!.contains("JsonFormat"))
        assertNotNull(failure.line) // l'offset de kotlinx, converti en ligne
        assertInstanceOf(SerializationException::class.java, failure.cause)
    }

    @Test
    fun `une clé inconnue pointe sa ligne`() {
        val path = newStorePath("unknown.json")
        path.writeText("{\n  \"name\": \"s\",\n  \"colour\": \"red\",\n  \"count\": 1,\n  \"tags\": []\n}")

        val failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave) }

        assertEquals(3, failure.line)
        assertTrue(failure.message!!.contains("'colour'"))
    }

    @Test
    fun `un commentaire dans un fichier JSON est une faute du fichier, avec sa ligne`() {
        val path = newStorePath("commented.json")
        path.writeText("{\n  \"name\": \"s\",\n  // le compteur\n  \"count\": 1,\n  \"tags\": []\n}")

        val failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave) }

        assertEquals(3, failure.line) // le JSON standard n'a pas de commentaires (C-36) : JSON5 est fait pour lui
        assertEquals("s", StoreFactory.create<PlainData>(path.toString(), format = JsonFormat.lenient(), config = noAutoSave).use { it.data.name })
    }

    @Test
    fun `un TOML tronqué donne sa ligne, un JSON5 tronqué aussi`() {
        val toml = newStorePath("broken.toml").also { it.writeText("name = \"s\"\ncount = \n") }
        val json5 = newStorePath("broken.json5").also { it.writeText("{\n  name: 's',\n  count: \n") }

        val tomlFailure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(toml.toString(), config = noAutoSave) }
        assertEquals(2, tomlFailure.line) // le (L2) de tomlkt
        assertTrue(tomlFailure.message!!.contains("TomlFormat"))

        val json5Failure = assertThrows(StoreDecodeException::class.java) { StoreFactory.create<PlainData>(json5.toString(), config = noAutoSave) }
        assertNotNull(json5Failure.line) // l'index de json5, converti en ligne
    }

    @Test
    fun `StorifyException couvre d'un seul catch le fichier mal formé et le fichier invalide`() {
        val malformed = newStorePath("a.json").also { it.writeText("{ cassé") }
        val invalid = newStorePath("b.json").also { it.writeText("{\n  \"name\": \"\"\n}") }

        assertThrows(StorifyException::class.java) { StoreFactory.create<PlainData>(malformed.toString(), config = noAutoSave) }
        assertThrows(StorifyException::class.java) { StoreFactory.createFromConstructor<AnnotatedValidatedData>(invalid.toString()) }
    }

    @Test
    fun `validateFile sur un fichier devenu illisible lève sans casser la mémoire`() {
        val path = newStorePath("later-broken.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            path.writeText("{ cassé")

            assertThrows(StoreDecodeException::class.java) { store.validateFile() }
            assertEquals("default", store.data.name)
        }
    }
}
