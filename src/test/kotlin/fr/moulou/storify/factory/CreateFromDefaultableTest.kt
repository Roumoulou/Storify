package fr.moulou.storify.factory

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.BrokenExternalDefaults
import fr.moulou.storify.support.ExternalAnnotatedDefaults
import fr.moulou.storify.support.ExternalPlainDefaults
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * La voie `createFromDefaultable` : les données initiales viennent d'une classe `Defaultable`
 * externe, instanciée par constructeur sans argument.
 */
class CreateFromDefaultableTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `la classe Defaultable externe fournit les défauts`() {
        val path = newStorePath("ext.json")
        StoreFactory.createFromDefaultable<PlainData, ExternalPlainDefaults>(path.toString(), config = noAutoSave).use { store ->
            assertEquals("external", store.data.name)
            assertEquals(7, store.data.count)
        }
    }

    @Test
    fun `par StorePath seul, la classe externe parle aussi`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        StoreFactory.createFromDefaultable<AnnotatedData, ExternalAnnotatedDefaults>().use { store ->
            assertEquals("externe", store.data.greeting)
            assertEquals(9, store.data.uses)
        }
    }

    @Test
    fun `une classe externe sans constructeur sans argument échoue net`() {
        assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromDefaultable<PlainData, BrokenExternalDefaults>(newStorePath("x.json").toString(), config = noAutoSave)
        }
    }
}
