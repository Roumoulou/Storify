// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.CapturedValue
import fr.moulou.storify.SetOperation
import fr.moulou.storify.UpdatePolicy
import fr.moulou.storify.core.BaseStore
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.support.ScalarData
import fr.moulou.storify.support.ScalarMood
import fr.moulou.storify.support.classFilesOf
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.referencedMembers
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

/*
 * La démo de C-45 : le pipeline d'update vu de l'appelant, ce qu'un mod embarque quand il écrit store.set(...), et ce que le pipeline capture.
 *
 * Avant C-45, le pipeline était inline de bout en bout, donc compilé chez chaque appelant : PipelineCaller.class pesait 15 897 octets pour ses
 * trois appels et référençait douze membres internes de BaseStore (get_data, getDataLock, getUpdatePolicies, markDirty...). Un correctif du
 * pipeline n'atteignait un mod qu'à sa recompilation, et un interne renommé aurait cassé un mod déjà compilé. Depuis : les extensions ne
 * compilent chez l'appelant que la matérialisation du sérialiseur de la valeur, le pipeline est une fonction ordinaire de BaseStore.
 *
 * Deux défauts du pipeline tombent avec lui. Le raccourci immuable ne reconnaissait ni Int, ni Long, ni Double, ni Boolean, qu'il copiait en
 * profondeur en SNAPSHOT (un type réifié Int est vu sous sa forme boxée, qu'isPrimitive ignore) : l'immuabilité se juge maintenant sur la
 * valeur. Et l'avant d'un set reposant la valeur déjà en place valait Unavailable ou Shallow selon le cache de boîtes de la JVM (Unavailable
 * pour 5, Shallow pour 1000) : un set montre maintenant toujours l'ancienne valeur.
 *
 *   1. le fichier .class d'un appelant de trois lignes : sa taille, et les membres de BaseStore qu'il référence ;
 *   2. le raccourci immuable en SNAPSHOT, type par type : seule une valeur mutable est copiée ;
 *   3. reposer la valeur déjà en place : l'avant est là, quelle que soit la valeur.
 */

/** L'appelant type : un mod qui pose deux valeurs et en mute une autre, rien de plus. */
class PipelineCaller {
    fun relabel(store: BaseStore<ScalarData>) = store.set(ScalarData::label, "b")
    fun recount(store: BaseStore<ScalarData>) = store.set(ScalarData::count, 2)
    fun tag(store: BaseStore<ScalarData>) = store.mutate(ScalarData::tags) { it.add("t") }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class UpdatePipelineDemoTest {

    private fun kind(captured: CapturedValue<*>): String = captured::class.simpleName ?: "?"

    /** Ouvre un store observé, joue [update], et rend l'opération reçue par le callback. */
    private fun observed(policy: UpdatePolicy, prepare: (BaseStore<ScalarData>) -> Unit = {}, update: (BaseStore<ScalarData>) -> Unit): SetOperation<*, *, *> =
        StoreFactory.createFromConstructor<ScalarData>(newStorePath("scalars.json").toString(), config = StoreConfig(withAutoSave = false, defaultUpdatePolicy = policy)).use { store ->
            prepare(store)
            var received: SetOperation<*, *, *>? = null
            store.registerOnUpdate { received = it as SetOperation<*, *, *> }
            update(store)
            checkNotNull(received)
        }

    @Test
    fun `étape 1, depuis C-45, un appelant de trois lignes n'embarque que deux points d'entrée`() {
        val classFiles = classFilesOf(PipelineCaller::class.java)
        val main = classFiles.getValue("PipelineCaller.class")
        val members = classFiles.values.flatMap { referencedMembers(it, "fr/moulou/storify/core/BaseStore") }.toSortedSet()
        println("    PipelineCaller.class : ${main.size} octets pour trois appels (deux set, un mutate) ; avec ses ${classFiles.size - 1} lambdas : ${classFiles.values.sumOf { it.size }} octets")
        println("    membres de BaseStore référencés (${members.size}) : ${members.joinToString(", ")}")
        check(members == sortedSetOf("mutateValue", "setValue"))
    }

    @Test
    fun `étape 2, depuis C-45, le raccourci immuable en SNAPSHOT vaut pour chaque scalaire`() {
        val cases = listOf<Pair<String, (BaseStore<ScalarData>) -> Unit>>(
            "String " to { it.set(ScalarData::label, "b") },
            "Int    " to { it.set(ScalarData::count, 2) },
            "Long   " to { it.set(ScalarData::total, 2L) },
            "Double " to { it.set(ScalarData::ratio, 2.0) },
            "Boolean" to { it.set(ScalarData::enabled, false) },
            "Char   " to { it.set(ScalarData::initial, 'b') },
            "enum   " to { it.set(ScalarData::mood, ScalarMood.ANGRY) },
            "liste  " to { it.set(ScalarData::tags, mutableListOf("x")) },
        )
        for ((label, update) in cases) {
            val operation = observed(UpdatePolicy.SNAPSHOT, update = update)
            println("    $label : old = ${kind(operation.old)}, new = ${kind(operation.new)}")
            val expected = if (label.startsWith("liste")) CapturedValue.DeepCopy::class else CapturedValue.Shallow::class
            check(operation.old::class == expected && operation.new::class == expected)
        }
    }

    @Test
    fun `étape 3, depuis C-45, reposer la valeur déjà en place garde son avant`() {
        val cases = listOf<Triple<String, (BaseStore<ScalarData>) -> Unit, (BaseStore<ScalarData>) -> Unit>>(
            Triple("Int, 5 sur 5                    ", { it.set(ScalarData::count, 5) }, { it.set(ScalarData::count, 5) }),
            Triple("Int, 1000 sur 1000              ", { it.set(ScalarData::count, 1000) }, { it.set(ScalarData::count, 1000) }),
            Triple("String, le même littéral        ", { it.set(ScalarData::label, "same") }, { it.set(ScalarData::label, "same") }),
            Triple("String, une autre instance égale", { it.set(ScalarData::label, "same") }, { it.set(ScalarData::label, String("same".toCharArray())) }),
        )
        for ((label, prepare, update) in cases) {
            val operation = observed(UpdatePolicy.SHALLOW, prepare, update)
            println("    $label : old = ${kind(operation.old)} (${operation.old.valueOrNull}), new = ${kind(operation.new)} (${operation.new.valueOrNull})")
            check(operation.old is CapturedValue.Shallow<*> && operation.old.valueOrNull == operation.new.valueOrNull)
        }
    }
}
