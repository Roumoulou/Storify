package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `set` et `setIn` : le remplacement typé d'une valeur, et la matrice des captures par policy
 * (SNAPSHOT copie, SHALLOW référence, SKIP silence ; les immuables jamais copiés en profondeur).
 */
class SetTest {

    private val skipConfig = StoreConfig(withAutoSave = false)
    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)
    private val shallowConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SHALLOW)

    @Test
    fun `set remplace la valeur d'une propriété racine et l'opération capture avant et après`() {
        StoreFactory.create<PlainData>(newStorePath("set.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::name, "Hello")

            assertEquals("Hello", store.data.name)
            val operation = assertInstanceOf(SetOperation::class.java, operations.single())
            assertEquals("default", operation.old.valueOrNull)
            assertEquals("Hello", operation.new.valueOrNull)
        }
    }

    @Test
    fun `setIn remplace une valeur sur un objet imbriqué désigné par navigation`() {
        StoreFactory.create<OuterData>(newStorePath("setin.json").toString(), config = skipConfig).use { store ->
            store.setIn(InnerLeaf::label, "renommé") { leaf }
            assertEquals("renommé", store.data.leaf.label)
        }
    }

    @Test
    fun `en SNAPSHOT, une valeur mutable est capturée en copie profonde, figée pour toujours`() {
        StoreFactory.create<PlainData>(newStorePath("snap.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::tags, mutableListOf("x"))

            val operation = assertInstanceOf(SetOperation::class.java, operations.single())
            assertInstanceOf(CapturedValue.DeepCopy::class.java, operation.old)
            assertInstanceOf(CapturedValue.DeepCopy::class.java, operation.new)

            // La capture est indépendante du vivant : une mutation ultérieure ne la touche pas.
            val capturedNew = operation.new.valueOrNull!!
            store.mutate(PlainData::tags) { it.add("poison") }
            assertEquals(listOf("x"), capturedNew)
        }
    }

    @Test
    fun `le raccourci immuable, un String en SNAPSHOT arrive en Shallow, jamais copié en profondeur`() {
        StoreFactory.create<PlainData>(newStorePath("immutable.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::name, "next")

            val operation = assertInstanceOf(SetOperation::class.java, operations.single())
            assertInstanceOf(CapturedValue.Shallow::class.java, operation.old)
            assertInstanceOf(CapturedValue.Shallow::class.java, operation.new)
        }
    }

    @Test
    fun `en SKIP, la valeur s'applique et le dirty se pose, mais aucun callback ne parle`() {
        StoreFactory.create<PlainData>(newStorePath("skip.json").toString(), config = skipConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }
            assertFalse(store.isDirty)

            store.set(PlainData::name, "muet")

            assertEquals("muet", store.data.name)
            assertTrue(operations.isEmpty())
            assertTrue(store.isDirty) // la persistance ne dépend pas de l'observation (C-03)
        }
    }

    @Test
    fun `en SHALLOW, les callbacks parlent sans copie profonde`() {
        StoreFactory.create<PlainData>(newStorePath("shallow.json").toString(), config = shallowConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::tags, mutableListOf("s"))

            val operation = assertInstanceOf(SetOperation::class.java, operations.single())
            assertInstanceOf(CapturedValue.Shallow::class.java, operation.old)
            assertInstanceOf(CapturedValue.Shallow::class.java, operation.new)
        }
    }
}
