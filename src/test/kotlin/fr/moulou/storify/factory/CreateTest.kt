package fr.moulou.storify.factory

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.NoCompanionData
import fr.moulou.storify.support.NotDefaultableCompanionData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * La voie `create` : les données initiales viennent du companion object, qui doit implémenter
 * `Defaultable`. Le path vient de `@StorePath` ou du paramètre explicite, qui gagne toujours.
 */
class CreateTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `sans path explicite ni StorePath, le refus est net`() {
        val exception = assertThrows(IllegalArgumentException::class.java) { StoreFactory.create<PlainData>() }
        assertTrue(exception.message!!.contains("@StorePath"))
    }

    @Test
    fun `par StorePath seul, le store naît au chemin annoté avec les défauts du companion`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        StoreFactory.create<AnnotatedData>().use { store ->
            assertEquals(AnnotatedData.getDefault(), store.data)
            assertTrue(Files.exists(Paths.get("build/tmp/storify-tests/annotated/annotated-data.json")))
        }
    }

    @Test
    fun `par path explicite, sans annotation, les défauts viennent du companion`() {
        val path = newStorePath("plain.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            assertEquals(PlainData.getDefault(), store.data)
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `le path explicite bat StorePath`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        val explicit = newStorePath("elsewhere.json")
        StoreFactory.create<AnnotatedData>(explicit.toString()).use {
            assertTrue(Files.exists(explicit)) // le fichier naît au chemin explicite...
            assertFalse(Files.exists(Paths.get("build/tmp/storify-tests/annotated/annotated-data.json"))) // ... jamais à l'annoté
        }
    }

    @Test
    fun `un fichier existant est décodé plutôt que les défauts`() {
        val path = newStorePath("existing.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            store.set(PlainData::name, "persisté")
            store.saveImmediate()
        }
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { reloaded ->
            assertEquals("persisté", reloaded.data.name) // pas le "default" du companion
        }
    }

    @Test
    fun `sans companion object, le refus est net`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.create<NoCompanionData>(newStorePath("x.json").toString(), config = noAutoSave)
        }
        assertTrue(exception.message!!.contains("companion object"))
    }

    @Test
    fun `un companion qui n'implémente pas Defaultable est refusé`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.create<NotDefaultableCompanionData>(newStorePath("x.json").toString(), config = noAutoSave)
        }
        assertTrue(exception.message!!.contains("Defaultable"))
    }
}
