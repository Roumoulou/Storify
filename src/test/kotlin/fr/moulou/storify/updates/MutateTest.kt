package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * `mutate` et `mutateIn` : la modification en place d'un objet mutable, et ce que chaque policy
 * peut capturer d'une mutation en place (l'avant n'existe qu'en SNAPSHOT).
 */
class MutateTest {

    private val skipConfig = StoreConfig(withAutoSave = false)
    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
    private val shallowConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SHALLOW)

    @Test
    fun `mutate modifie un objet mutable de la racine en place`() {
        StoreFactory.create<PlainData>(newStorePath("mutate.json").toString(), config = skipConfig).use { store ->
            store.mutate(PlainData::tags) { it.add("b") }
            assertEquals(listOf("a", "b"), store.data.tags)
        }
    }

    @Test
    fun `mutateIn navigue jusqu'à l'objet imbriqué`() {
        StoreFactory.create<OuterData>(newStorePath("mutatein.json").toString(), config = skipConfig).use { store ->
            store.mutateIn(InnerLeaf::hits, { leaf }) { it.add(3) }
            assertEquals(listOf(3), store.data.leaf.hits)
        }
    }

    @Test
    fun `en SNAPSHOT, l'avant d'une mutation diffère de l'après, tous deux en copie profonde`() {
        StoreFactory.create<PlainData>(newStorePath("mutsnap.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.mutate(PlainData::tags) { it.add("z") }

            val operation = assertInstanceOf(MutateOperation::class.java, operations.single())
            assertInstanceOf(CapturedValue.DeepCopy::class.java, operation.old)
            assertInstanceOf(CapturedValue.DeepCopy::class.java, operation.new)
            assertEquals(listOf("a"), operation.old.valueOrNull)
            assertEquals(listOf("a", "z"), operation.new.valueOrNull)
        }
    }

    @Test
    fun `en SHALLOW, l'avant d'une mutation en place est indisponible et l'après reste vivant`() {
        StoreFactory.create<PlainData>(newStorePath("mutshallow.json").toString(), config = shallowConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.mutate(PlainData::tags) { it.add("v") }

            val operation = assertInstanceOf(MutateOperation::class.java, operations.single())
            assertFalse(operation.old.isAvailable) // même référence avant et après : rien à montrer
            assertInstanceOf(CapturedValue.Shallow::class.java, operation.new)
            assertSame(store.data.tags, operation.new.valueOrNull) // l'après est l'objet vivant, pas une copie
        }
    }
}
