package fr.moulou.storify.persistence

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.awaitTrue
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.readText

/**
 * L'auto-save : le tick persiste le dirty (policy SKIP comprise, C-03), la pause le rend muet,
 * la reprise rattrape le dirty en attente. Toutes les attentes sont bornées.
 */
class AutoSaveTest {

    @Test
    fun `le tick d'auto-save persiste un update SKIP`() {
        val path = newStorePath("autosave.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = true, autoSaveIntervalMs = 100)).use { store ->
            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) }

            store.set(PlainData::name, "autosaved") // SKIP par défaut : muet pour les callbacks, dirty quand même

            assertTrue(awaitTrue { saves.any { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE } })
            assertTrue(path.readText().contains("autosaved"))
        }
    }

    @Test
    fun `la pause rend le tick muet, la reprise sauve le dirty en attente`() {
        val path = newStorePath("pause.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = true, autoSaveIntervalMs = 100)).use { store ->
            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) }

            store.pauseAutoSave()
            assertTrue(store.isAutoSavePaused())
            store.set(PlainData::count, 7)

            // Plusieurs ticks passent (100 ms d'intervalle, 400 ms d'observation) : aucun ne doit sauver.
            assertFalse(awaitTrue(timeoutMs = 400) { saves.isNotEmpty() })

            store.resumeAutoSave()
            assertFalse(store.isAutoSavePaused())

            // La reprise n'attend pas un nouvel update : le dirty en attente part au tick suivant.
            assertTrue(awaitTrue { saves.any { it is SaveOperation<*> && it.trigger == SaveTrigger.AUTO_SAVE } })
            assertTrue(path.readText().contains("\"count\": 7"))
        }
    }
}
