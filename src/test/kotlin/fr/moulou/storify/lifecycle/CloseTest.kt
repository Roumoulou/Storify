package fr.moulou.storify.lifecycle

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `close()` (C-01) : idempotent, lisible après fermeture, fermé aux écritures, la sauvegarde
 * d'adieu seulement si dirty, et les interrupteurs d'auto-save rendus inertes.
 */
class CloseTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `un store fermé reste lisible, refuse les écritures, et close est idempotent`() {
        val path = newStorePath("closed.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)

        store.set(PlainData::name, "avant fermeture")
        store.close()
        store.close() // idempotent : silencieux

        assertTrue(store.isClosed)
        assertEquals("avant fermeture", store.data.name)              // la lecture reste permise
        assertTrue(path.readText().contains("avant fermeture"))       // la sauvegarde d'adieu a écrit le dirty

        assertThrows(IllegalStateException::class.java) { store.set(PlainData::name, "trop tard") }
        assertThrows(IllegalStateException::class.java) { store.saveImmediate() }
        assertThrows(IllegalStateException::class.java) { store.reloadFromFile() }
    }

    @Test
    fun `la sauvegarde d'adieu ne part que si le store est dirty`() {
        val path = newStorePath("clean-close.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)
        store.set(PlainData::name, "sauvé")
        store.saveImmediate() // le store est propre

        path.writeText(path.readText().replace("sauvé", "édité à la main"))
        store.close()

        assertTrue(path.readText().contains("édité à la main")) // rien à sauver : close n'a pas réécrit
    }

    @Test
    fun `use ferme le store en sortant du bloc`() {
        val store = StoreFactory.create<PlainData>(newStorePath("use.json").toString(), config = noAutoSave)
        store.use { }
        assertTrue(store.isClosed)
    }

    @Test
    fun `pause et resume deviennent inertes après close`() {
        val store = StoreFactory.create<PlainData>(newStorePath("inert.json").toString(), config = noAutoSave)
        store.close()

        store.pauseAutoSave()
        assertFalse(store.isAutoSavePaused()) // l'interrupteur n'a pas bougé

        store.resumeAutoSave() // et la reprise ne lève rien non plus
        assertFalse(store.isAutoSavePaused())
    }
}
