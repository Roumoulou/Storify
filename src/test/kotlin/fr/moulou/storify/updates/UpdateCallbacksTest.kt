package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.TwinData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlin.reflect.KProperty1

/**
 * Les callbacks d'update : le global entend tout ce qui s'observe, le ciblé n'entend que sa
 * propriété, les références de propriété se retrouvent d'un site à l'autre (le mécanisme sur
 * lequel tout repose), et un callback peut relire le store sans interblocage.
 */
class UpdateCallbacksTest {

    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    /** Une référence de propriété fabriquée AILLEURS qu'au site d'enregistrement et d'update. */
    private fun countFromElsewhere(): KProperty1<PlainData, Int> = PlainData::count

    @Test
    fun `le callback global reçoit chaque opération observée, du set à la transaction`() {
        StoreFactory.create<PlainData>(newStorePath("global.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::name, "un")
            store.mutate(PlainData::tags) { it.add("deux") }
            store.transaction { count = 3 }

            assertEquals(3, operations.size)
            assertInstanceOf(SetOperation::class.java, operations[0])
            assertInstanceOf(MutateOperation::class.java, operations[1])
            assertInstanceOf(TransactionOperation::class.java, operations[2])
        }
    }

    @Test
    fun `le callback ciblé n'entend que sa propriété`() {
        StoreFactory.create<PlainData>(newStorePath("targeted.json").toString(), config = snapshotConfig).use { store ->
            val global = mutableListOf<Operation<PlainData>>()
            val targeted = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { global.add(it) }
            store.registerOnUpdateOn(PlainData::count) { targeted.add(it) }

            store.set(PlainData::name, "ailleurs") // pas pour le ciblé
            store.set(PlainData::count, 4)

            assertEquals(2, global.size)
            assertEquals(1, targeted.size)
        }
    }

    @Test
    fun `les références de propriété de sites différents se retrouvent`() {
        StoreFactory.create<PlainData>(newStorePath("refs.json").toString(), config = snapshotConfig).use { store ->
            val targeted = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdateOn(countFromElsewhere()) { targeted.add(it) } // enregistré via une référence d'un autre site

            store.set(PlainData::count, 8) // mis à jour via la référence locale

            assertEquals(1, targeted.size) // l'égalité des KProperty1 fait le lien : le mécanisme épinglé (voir C-10)
        }
    }

    @Test
    fun `registerOnUpdateOnIn ne réveille que l'instance visée`() {
        StoreFactory.create<TwinData>(newStorePath("twins.json").toString(), config = snapshotConfig).use { store ->
            var leftHeard = 0
            var rightHeard = 0
            store.registerOnUpdateOnIn(InnerLeaf::label, { left }) { leftHeard++ }
            store.registerOnUpdateOnIn(InnerLeaf::label, { right }) { rightHeard++ }

            store.setIn(InnerLeaf::label, "gauche") { left }

            assertEquals(1, leftHeard)
            assertEquals(0, rightHeard) // la jumelle n'a rien entendu : le ciblage est à l'instance
        }
    }

    @Test
    fun `l'écouteur à navigation survit au rechargement`() {
        StoreFactory.create<TwinData>(newStorePath("twins-reload.json").toString(), config = snapshotConfig).use { store ->
            var heard = 0
            store.registerOnUpdateOnIn(InnerLeaf::label, { left }) { heard++ }

            store.saveImmediate()
            store.reloadFromFile() // la racine est remplacée : toutes les instances sont neuves

            store.setIn(InnerLeaf::label, "après reload") { left }
            assertEquals(1, heard) // la navigation, réévaluée au dispatch, a retrouvé la nouvelle instance
        }
    }

    @Test
    fun `une navigation qui échoue vaut silence, sans casser le dispatch`() {
        StoreFactory.create<TwinData>(newStorePath("twins-broken.json").toString(), config = snapshotConfig).use { store ->
            var brokenHeard = 0
            var healthyHeard = 0
            store.registerOnUpdateOnIn(InnerLeaf::label, { error("navigation cassée") }) { brokenHeard++ }
            store.registerOnUpdateOnIn(InnerLeaf::label, { left }) { healthyHeard++ }

            store.setIn(InnerLeaf::label, "x") { left } // aucune exception ne fuit du dispatch

            assertEquals(0, brokenHeard)
            assertEquals(1, healthyHeard)
        }
    }

    @Test
    fun `un callback peut en enregistrer un autre pendant le dispatch`() {
        StoreFactory.create<PlainData>(newStorePath("during-dispatch.json").toString(), config = snapshotConfig).use { store ->
            var lateCallbackHeard = 0
            var registered = false
            store.registerOnUpdate {
                if (!registered) {
                    registered = true
                    store.registerOnUpdate { lateCallbackHeard++ } // s'enregistrer PENDANT un dispatch : légal depuis C-08
                }
            }

            store.set(PlainData::name, "premier")  // déclenche l'enregistrement tardif, sans ConcurrentModificationException
            store.set(PlainData::name, "second")   // le tardif entend celui-ci

            assertEquals(1, lateCallbackHeard)
        }
    }

    @Test
    fun `un callback peut relire le store sans interblocage`() {
        StoreFactory.create<PlainData>(newStorePath("reentrant.json").toString(), config = snapshotConfig).use { store ->
            var seenFromCallback = -1
            store.registerOnUpdate { seenFromCallback = store.data.count } // relecture sous read lock, hors du write lock

            store.set(PlainData::count, 5)

            assertEquals(5, seenFromCallback)
        }
    }
}
