package fr.moulou.storify.persistence

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `saveImmediate` et les captures du save : l'Initial au premier save, la copie du précédent
 * ensuite, l'Unavailable quand le deep copy est coupé ; et la vie du drapeau dirty.
 */
class SaveTest {

    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    @Test
    fun `le premier save capture l'Initial, les suivants la copie du save précédent`() {
        StoreFactory.create<PlainData>(newStorePath("saves.json").toString(), config = snapshotConfig).use { store ->
            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) }

            store.set(PlainData::name, "un")
            store.saveImmediate()
            store.set(PlainData::name, "deux")
            store.saveImmediate()

            assertEquals(2, saves.size)
            val first = assertInstanceOf(SaveOperation::class.java, saves[0])
            assertInstanceOf(CapturedValue.Initial::class.java, first.old)
            assertEquals(SaveTrigger.IMMEDIATE, first.trigger)
            val second = assertInstanceOf(SaveOperation::class.java, saves[1])
            assertInstanceOf(CapturedValue.DeepCopy::class.java, second.old)
        }
    }

    @Test
    fun `sans useDeepCopy, les captures du save deviennent indisponibles`() {
        val config = StoreConfig(withAutoSave = false, useDeepCopy = false)
        StoreFactory.create<PlainData>(newStorePath("nodeep.json").toString(), config = config).use { store ->
            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) }

            store.set(PlainData::name, "un")
            store.saveImmediate()
            store.set(PlainData::name, "deux")
            store.saveImmediate()

            val first = assertInstanceOf(SaveOperation::class.java, saves[0])
            assertFalse(first.new.isAvailable)                              // plus de snapshot d'après
            val second = assertInstanceOf(SaveOperation::class.java, saves[1])
            assertFalse(second.old.isAvailable)                             // et plus d'avant au save suivant
        }
    }

    @Test
    fun `le dirty se remet à zéro après une écriture réussie`() {
        StoreFactory.create<PlainData>(newStorePath("dirty.json").toString(), config = snapshotConfig).use { store ->
            assertFalse(store.isDirty)
            store.set(PlainData::count, 2)
            assertTrue(store.isDirty)
            store.saveImmediate()
            assertFalse(store.isDirty)
            store.set(PlainData::count, 3)
            assertTrue(store.isDirty) // et le cycle repart
        }
    }
}
