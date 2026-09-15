package fr.moulou.storify.updates

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * La résolution des policies : l'annotation par propriété (jusque dans les classes imbriquées,
 * par le scan récursif), le défaut de la config, et le réglage au runtime.
 */
class UpdatePolicyTest {

    private val skipConfig = StoreConfig(withAutoSave = false)

    @Test
    fun `l'annotation StoreUpdatePolicy s'applique sur une propriété de la racine`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        StoreFactory.create<AnnotatedData>().use { store ->
            assertEquals(UpdatePolicy.SHALLOW, store.getUpdatePolicy(AnnotatedData::uses))     // l'annotation de la propriété
            assertEquals(UpdatePolicy.SNAPSHOT, store.getUpdatePolicy(AnnotatedData::greeting)) // le défaut de la config annotée
        }
    }

    @Test
    fun `le scan récursif trouve la policy annotée d'une classe imbriquée`() {
        StoreFactory.create<OuterData>(newStorePath("nestedpolicy.json").toString(), config = skipConfig).use { store ->
            assertEquals(UpdatePolicy.SNAPSHOT, store.getUpdatePolicy(InnerLeaf::hits))

            // Et elle agit : le défaut du store est SKIP, mais la propriété imbriquée annotée parle.
            val operations = mutableListOf<Operation<OuterData>>()
            store.registerOnUpdate { operations.add(it) }
            store.mutateIn(InnerLeaf::hits, { leaf }) { it.add(1) }
            assertEquals(1, operations.size)
        }
    }

    @Test
    fun `le defaultUpdatePolicy annoté allume les callbacks sans config explicite`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        StoreFactory.create<AnnotatedData>().use { store ->
            val operations = mutableListOf<Operation<AnnotatedData>>()
            store.registerOnUpdate { operations.add(it) }
            store.set(AnnotatedData::greeting, "salut")
            assertEquals(1, operations.size)
        }
    }

    @Test
    fun `setUpdatePolicy change le comportement au runtime`() {
        StoreFactory.create<PlainData>(newStorePath("runtime.json").toString(), config = skipConfig).use { store ->
            val operations = mutableListOf<Operation<PlainData>>()
            store.registerOnUpdate { operations.add(it) }

            store.set(PlainData::name, "muet")
            assertTrue(operations.isEmpty()) // SKIP par défaut

            store.setUpdatePolicy(PlainData::name, UpdatePolicy.SNAPSHOT)
            store.set(PlainData::name, "bavard")
            assertEquals(1, operations.size) // la policy posée au runtime parle
        }
    }

    @Test
    fun `belongsToDataTree connaît l'arbre, racine et imbriquées, et rejette l'étranger`() {
        StoreFactory.create<OuterData>(newStorePath("tree.json").toString(), config = skipConfig).use { store ->
            assertTrue(store.belongsToDataTree(OuterData::title))
            assertTrue(store.belongsToDataTree(InnerLeaf::hits))  // imbriquée, trouvée par le scan récursif
            assertFalse(store.belongsToDataTree(PlainData::name)) // l'arbre d'un autre store
            assertFalse(store.belongsToDataTree(String::length))  // kotlin_* n'est l'arbre de personne
        }
    }

    @Test
    fun `getUpdatePolicy rend l'entrée posée, sinon le défaut de la config`() {
        val shallowDefault = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SHALLOW)
        StoreFactory.create<PlainData>(newStorePath("getpolicy.json").toString(), config = shallowDefault).use { store ->
            assertEquals(UpdatePolicy.SHALLOW, store.getUpdatePolicy(PlainData::name))
            store.setUpdatePolicy(PlainData::name, UpdatePolicy.SKIP)
            assertEquals(UpdatePolicy.SKIP, store.getUpdatePolicy(PlainData::name))
        }
    }
}
