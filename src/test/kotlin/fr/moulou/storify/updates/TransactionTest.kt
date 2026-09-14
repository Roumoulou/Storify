package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `transaction` : la racine entière modifiée d'un bloc, tout ou rien. Le filet de rollback vient
 * du deep copy ; sans `useDeepCopy`, il n'existe pas, et c'est épinglé.
 */
class TransactionTest {

    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    @Test
    fun `la transaction modifie plusieurs champs d'un bloc et capture l'avant et l'après`() {
        StoreFactory.create<PlainData>(newStorePath("tx.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.transaction {
                name = "tx"
                count = 5
            }

            assertEquals("tx", store.data.name)
            assertEquals(5, store.data.count)
            val operation = assertInstanceOf(TransactionOperation::class.java, operations.single())
            assertTrue(operation.success)
            assertEquals("default", (operation.old.valueOrNull as PlainData).name)
            assertEquals("tx", (operation.new.valueOrNull as PlainData).name)
        }
    }

    @Test
    fun `une exception dans le bloc restaure tout puis relance`() {
        StoreFactory.create<PlainData>(newStorePath("txboom.json").toString(), config = snapshotConfig).use { store ->
            assertThrows(IllegalStateException::class.java) {
                store.transaction {
                    name = "cassé"
                    throw IllegalStateException("boum")
                }
            }
            assertEquals("default", store.data.name) // le rollback a tout restauré
        }
    }

    @Test
    fun `sans useDeepCopy, la transaction n'a pas de filet, comportement épinglé`() {
        val config = StoreConfig(withAutoSave = false, useDeepCopy = false)
        StoreFactory.create<PlainData>(newStorePath("txnonet.json").toString(), config = config).use { store ->
            assertThrows(IllegalStateException::class.java) {
                store.transaction {
                    name = "cassé"
                    throw IllegalStateException("boum")
                }
            }
            assertEquals("cassé", store.data.name) // pas de copie de secours : les changements restent
        }
    }

    @Test
    fun `la transaction pose le dirty`() {
        StoreFactory.create<PlainData>(newStorePath("txdirty.json").toString(), config = snapshotConfig).use { store ->
            assertFalse(store.isDirty)
            store.transaction { count = 2 }
            assertTrue(store.isDirty)
        }
    }
}
