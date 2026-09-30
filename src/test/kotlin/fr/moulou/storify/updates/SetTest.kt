// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.ScalarData
import fr.moulou.storify.support.ScalarMood
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `set` et `setIn` : le remplacement typé d'une valeur, et la matrice des captures par policy
 * (SNAPSHOT copie, SHALLOW référence, SKIP silence ; les valeurs immuables jamais copiées en
 * profondeur, de l'Int à l'enum, et l'avant d'un set toujours disponible, C-45).
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
    fun `le raccourci immuable vaut pour chaque scalaire, de l'Int à l'enum, null compris`() {
        val updates = listOf<(BaseStore<ScalarData>) -> Unit>(
            { it.set(ScalarData::label, "b") },
            { it.set(ScalarData::count, 2) },
            { it.set(ScalarData::total, 2L) },
            { it.set(ScalarData::ratio, 2.0) },
            { it.set(ScalarData::enabled, false) },
            { it.set(ScalarData::initial, 'b') },
            { it.set(ScalarData::mood, ScalarMood.ANGRY) },
            { it.set(ScalarData::note, "écrite") }, // de null à une chaîne
        )
        StoreFactory.createFromConstructor<ScalarData>(newStorePath("scalars.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<ScalarData>>()
            store.registerOnUpdate { operations.add(it) }

            updates.forEach { update -> update(store) }

            assertEquals(updates.size, operations.size)
            operations.forEach { operation ->
                val set = assertInstanceOf(SetOperation::class.java, operation)
                assertInstanceOf(CapturedValue.Shallow::class.java, set.old) // jamais copié en profondeur : une référence suffit à figer un immuable
                assertInstanceOf(CapturedValue.Shallow::class.java, set.new)
            }
        }
    }

    @Test
    fun `en SNAPSHOT, entre null et une valeur mutable, seule la valeur mutable est copiée`() {
        StoreFactory.createFromConstructor<ScalarData>(newStorePath("nullable.json").toString(), config = snapshotConfig).use { store ->
            val operations = mutableListOf<Operation<ScalarData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(ScalarData::extras, mutableListOf("x"))
            store.set(ScalarData::extras, null)

            val filled = assertInstanceOf(SetOperation::class.java, operations[0])
            assertInstanceOf(CapturedValue.Shallow::class.java, filled.old)    // null : rien à copier
            assertInstanceOf(CapturedValue.DeepCopy::class.java, filled.new)
            val emptied = assertInstanceOf(SetOperation::class.java, operations[1])
            assertInstanceOf(CapturedValue.DeepCopy::class.java, emptied.old)
            assertEquals(listOf("x"), emptied.old.valueOrNull)
            assertInstanceOf(CapturedValue.Shallow::class.java, emptied.new)
        }
    }

    @Test
    fun `reposer la valeur déjà en place garde un avant disponible, quelle que soit la valeur`() {
        StoreFactory.createFromConstructor<ScalarData>(newStorePath("same.json").toString(), config = shallowConfig).use { store ->
            store.set(ScalarData::count, 5)
            val operations = mutableListOf<Operation<ScalarData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(ScalarData::count, 5)    // une petite valeur, que la JVM sert d'un cache : la même boîte avant et après
            store.set(ScalarData::count, 1000)
            store.set(ScalarData::count, 1000) // une grande valeur : deux boîtes distinctes

            val olds = operations.map { operation -> assertInstanceOf(SetOperation::class.java, operation).old }
            olds.forEach { old -> assertInstanceOf(CapturedValue.Shallow::class.java, old) } // un set remplace la valeur : l'ancienne est toujours l'avant
            assertEquals(listOf<Any?>(5, 5, 1000), olds.map { it.valueOrNull })
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
