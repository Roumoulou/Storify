// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.updates

import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.classFilesOf
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.referencedMembers
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** L'appelant témoin : ce que le compilateur range dans cette classe, il le rangerait dans le jar d'un mod. */
class UpdateCaller {
    fun retitle(store: BaseStore<OuterData>) = store.set(OuterData::title, "set")
    fun relabel(store: BaseStore<OuterData>) = store.setIn(InnerLeaf::label, "setIn") { leaf }
    fun register(store: BaseStore<OuterData>) = store.mutate(OuterData::registry) { it["mutate"] = "fait" }
    fun hit(store: BaseStore<OuterData>) = store.mutateIn(InnerLeaf::hits, { leaf }) { it.add(1) }
    fun rewrite(store: BaseStore<OuterData>) = store.transaction { title = "transaction" }
}

/**
 * Le garde-fou de C-45 : les extensions d'update sont inline pour matérialiser un sérialiseur, rien de plus. Le fichier .class d'un
 * appelant ne doit référencer de `BaseStore` que leurs deux points d'entrée. Si le pipeline fuyait de nouveau chez l'appelant, un
 * correctif de la lib n'atteindrait un mod qu'à sa recompilation, et un membre interne renommé casserait un mod déjà compilé.
 */
class CallerBytecodeTest {

    @Test
    fun `un appelant de set, setIn, mutate, mutateIn et transaction ne référence de BaseStore que setValue et mutateValue`() {
        val classFiles = classFilesOf(UpdateCaller::class.java)

        val members = classFiles.values.flatMap { referencedMembers(it, "fr/moulou/storify/core/BaseStore") }.toSortedSet()

        assertEquals(sortedSetOf("mutateValue", "setValue"), members) // transaction passe par sa fonction d'extension, qui n'est pas inline
    }

    @Test
    fun `l'appelant témoin met bien le store à jour par les cinq gestes`() {
        StoreFactory.create<OuterData>(newStorePath("caller.json").toString(), config = StoreConfig(withAutoSave = false)).use { store ->
            val caller = UpdateCaller()

            caller.retitle(store)
            caller.relabel(store)
            caller.register(store)
            caller.hit(store)
            assertEquals("set", store.data.title)
            assertEquals("setIn", store.data.leaf.label)
            assertEquals(mapOf("mutate" to "fait"), store.data.registry)
            assertEquals(listOf(1), store.data.leaf.hits)

            caller.rewrite(store)
            assertEquals("transaction", store.data.title)
        }
    }
}
