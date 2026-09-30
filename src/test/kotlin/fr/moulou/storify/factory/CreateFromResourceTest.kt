// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.factory

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.ResourceData
import fr.moulou.storify.support.ValidatedResourceData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import fr.moulou.storify.validation.ValidationException
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * La voie `createFromResource` : au premier lancement, une ressource du classpath est copiée telle quelle vers le fichier cible, par
 * l'écrivain atomique, puis décodée (C-40). La copie reste sur disque même invalide (le voeu d'origine, C-06).
 */
class CreateFromResourceTest {

    private fun resourceBytes(name: String): ByteArray = javaClass.classLoader.getResourceAsStream(name)!!.use { it.readBytes() }

    @Test
    fun `la voie annotée copie la ressource puis la décode`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/resource-data.json")
        StoreFactory.createFromResource<ResourceData>().use { store ->
            assertEquals("resource", store.data.origin)
            assertEquals(42, store.data.level)
            assertTrue(Files.exists(Paths.get("build/tmp/storify-tests/annotated/resource-data.json")))
        }
    }

    @Test
    fun `la voie explicite copie et décode aussi`() {
        val path = newStorePath("res.json")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_default.json").use { store ->
            assertEquals("resource", store.data.origin)
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `la copie d'une ressource valide est la ressource à l'octet, jamais réencodée`() {
        val path = newStorePath("verbatim.json")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_default.json").use { store ->
            assertEquals(42, store.data.level)
        }
        assertArrayEquals(resourceBytes("resource-data_default.json"), Files.readAllBytes(path)) // indentation et saut de ligne final compris (C-40)
    }

    @Test
    fun `une ressource JSON5 commentée garde ses commentaires au premier lancement, et la première sauvegarde les préserve`() {
        val path = newStorePath("commented.json5")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_commented.json5").use { store ->
            assertArrayEquals(resourceBytes("resource-data_commented.json5"), Files.readAllBytes(path))

            store.set(ResourceData::level, 43)
            store.saveImmediate()
        }
        val text = path.readText()
        assertTrue(text.contains("// Le niveau de départ, de 0 à 100.")) // la sauvegarde préservante (C-26) part de la copie fidèle
        assertTrue(text.contains("level: 43"))
    }

    @Test
    fun `une ressource TOML commentée garde ses commentaires au premier lancement`() {
        val path = newStorePath("commented.toml")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_commented.toml").use { store ->
            assertEquals("resource", store.data.origin)
        }
        assertArrayEquals(resourceBytes("resource-data_commented.toml"), Files.readAllBytes(path))
    }

    @Test
    fun `une copie qui casse en route ne laisse ni fichier tronqué ni temporaire`() {
        val path = newStorePath("broken-copy.json")
        val failingJar = object : ClassLoader() {
            override fun getResourceAsStream(name: String): InputStream = object : InputStream() {
                private var served = 0
                override fun read(): Int = if (served++ < 10) '{'.code else throw IOException("jar unreadable in the middle of the copy")
            }
        }
        assertThrows(IOException::class.java) { StoreFactory.copyResource(failingJar, ResourceData.serializer(), path, "resource-data_default.json", JsonFormat(), "Storify") }

        assertFalse(Files.exists(path)) // la panne a frappé le temporaire, jamais la cible
        assertTrue(Files.list(path.parent).use { stream -> stream.toList() }.isEmpty()) // et le temporaire est parti avec elle
    }

    @Test
    fun `une ressource introuvable est refusée net`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromResource<ResourceData>(newStorePath("r.json").toString(), "missing_resource.json")
        }
        assertTrue(exception.message!!.contains("Resource not found"))
    }

    @Test
    fun `StoreDefaultResource absent sur la voie annotée, refus net`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        val exception = assertThrows(IllegalArgumentException::class.java) { StoreFactory.createFromResource<AnnotatedData>() }
        assertTrue(exception.message!!.contains("@StoreDefaultResource"))
    }

    @Test
    fun `un fichier déjà présent gagne, la ressource n'est pas recopiée`() {
        val path = newStorePath("winner.json")
        path.writeText("""{"origin": "disk", "level": 1}""")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_default.json").use { store ->
            assertEquals("disk", store.data.origin) // le fichier existant est décodé, la ressource ignorée
        }
    }

    @Test
    fun `une ressource invalide reste sur disque telle que livrée, le message dit d'où elle vient et pointe la ligne`() {
        val path = newStorePath("bad.json")
        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromResource<ValidatedResourceData>(path.toString(), "resource-data_invalid.json")
        }
        assertArrayEquals(resourceBytes("resource-data_invalid.json"), Files.readAllBytes(path)) // la copie reste, éditable
        assertTrue(exception.message!!.contains("copied from the default resource")) // le remède est dans ce fichier, le message le dit (C-40)
        assertTrue(exception.message!!.contains("line")) // et les erreurs pointent la ligne dans cette copie
    }
}
