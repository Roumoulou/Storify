package fr.moulou.storify.factory

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.NoZeroArgConstructorData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * La voie `createFromConstructor` : les données initiales viennent du constructeur sans argument
 * de DATA (tous les champs ont un défaut), jamais du companion.
 */
class CreateFromConstructorTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `par StorePath seul, le constructeur sans argument fournit les défauts`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        StoreFactory.createFromConstructor<AnnotatedData>().use { store ->
            assertEquals(AnnotatedData(), store.data)
            assertTrue(Files.exists(Paths.get("build/tmp/storify-tests/annotated/annotated-data.json")))
        }
    }

    @Test
    fun `par path explicite, c'est le constructeur qui parle, pas le companion`() {
        val path = newStorePath("ctor.json")
        StoreFactory.createFromConstructor<PlainData>(path.toString(), config = noAutoSave).use { store ->
            assertEquals(PlainData(), store.data)                 // "steve", 0 : le constructeur
            assertNotEquals(PlainData.getDefault(), store.data)   // pas "default", 1 : le companion est ignoré
        }
    }

    @Test
    fun `sans constructeur sans argument, l'échec est net`() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromConstructor<NoZeroArgConstructorData>(newStorePath("x.json").toString(), config = noAutoSave)
        }
    }

    @Test
    fun `un fichier existant est décodé, le constructeur n'est pas sollicité`() {
        val path = newStorePath("existing.json")
        StoreFactory.createFromConstructor<PlainData>(path.toString(), config = noAutoSave).use { store ->
            store.set(PlainData::count, 99)
            store.saveImmediate()
        }
        StoreFactory.createFromConstructor<PlainData>(path.toString(), config = noAutoSave).use { reloaded ->
            assertEquals(99, reloaded.data.count)
        }
    }
}
