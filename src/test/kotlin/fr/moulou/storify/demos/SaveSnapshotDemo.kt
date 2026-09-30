// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.CapturedValue
import fr.moulou.storify.SaveOperation
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.set
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.updates.CountedBox
import fr.moulou.storify.updates.CountedBoxSerializer
import fr.moulou.storify.updates.CountedData
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import kotlin.system.measureNanoTime

/*
 * La démo de C-41 : ce qu'un store copie pour ses callbacks de save, et quand. Un callback de save reçoit l'avant et l'après de la racine
 * (SaveOperation.old et new), deux copies profondes ; pour les lui fournir, le store garde en mémoire un exemplaire de la racine telle qu'au
 * dernier save. La fixture CountedData compte les sérialisations de la racine : une par écriture de fichier, une par copie profonde.
 *
 * Avant C-41, cette copie se prenait à l'ouverture et à chaque sauvegarde, qu'un callback de save existe ou non : 2 sérialisations à
 * l'ouverture et 2 par sauvegarde là où le fichier en demande 1, et un second exemplaire de la racine gardé toute la vie du store. Depuis :
 * elle ne se prend que devant un auditeur de save, comme les captures des updates depuis C-25.
 *
 *   1. l'ouverture ne copie plus la racine, avec ou sans useDeepCopy ;
 *   2. une sauvegarde sans auditeur n'écrit que le fichier ;
 *   3. devant un auditeur, la copie sert : la référence prise à l'enregistrement, l'avant et l'après à chaque save, l'Initial au premier ;
 *   4. le bord : un callback enregistré sur un store déjà modifié n'a pas d'avant à son premier save ;
 *   5. le prix de cette copie sur une racine de cinq mille joueurs, que seul un store écouté paie encore.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class SaveSnapshotDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)
    private val noCopies = StoreConfig(withAutoSave = false, useDeepCopy = false)

    private fun countedStore(config: StoreConfig) = StoreFactory.create<CountedData>(newStorePath("counted.json").toString(), config = config)

    /** Le nombre de sérialisations de la racine pendant [block] : une par écriture de fichier, une par copie profonde. */
    private fun serializationsDuring(block: () -> Unit): Int {
        val before = CountedBoxSerializer.serializations
        block()
        return CountedBoxSerializer.serializations - before
    }

    private fun describe(captured: CapturedValue<CountedData>): String = captured.valueOrNull?.let { "${captured::class.simpleName}(box = ${it.box.value})" } ?: "Unavailable"

    private fun show(saves: List<SaveOperation<CountedData>>) = saves.forEachIndexed { index, save -> println("    save ${index + 1} : old = ${describe(save.old)}, new = ${describe(save.new)}") }

    @Test
    fun `étape 1, depuis C-41, l'ouverture ne copie plus la racine`() {
        val withCopies = serializationsDuring { countedStore(noAutoSave).close() }
        val withoutCopies = serializationsDuring { countedStore(noCopies).close() }
        println("    ouverture, useDeepCopy = true (le défaut) : $withCopies sérialisation de la racine, le fichier initial")
        println("    ouverture, useDeepCopy = false            : $withoutCopies sérialisation, le fichier initial")
        check(withCopies == 1 && withoutCopies == 1)
    }

    @Test
    fun `étape 2, depuis C-41, une sauvegarde sans auditeur n'écrit que le fichier`() {
        val perSave = countedStore(noAutoSave).use { store ->
            (1..3).map { value -> serializationsDuring { store.set(CountedData::box, CountedBox(value)); store.saveImmediate() } }
        }
        println("    trois sauvegardes sans auditeur, useDeepCopy = true (le défaut) : $perSave sérialisations, l'écriture du fichier et rien d'autre")
        check(perSave.all { it == 1 })
    }

    @Test
    fun `étape 3, devant un auditeur, la copie sert, l'avant et l'après du callback de save`() {
        countedStore(noAutoSave).use { store ->
            val saves = mutableListOf<SaveOperation<CountedData>>()
            val atRegistration = serializationsDuring { store.registerOnSave { saves.add(it as SaveOperation<CountedData>) } }
            println("    l'enregistrement du callback, sur un store propre : $atRegistration copie, la référence du premier avant")

            val perSave = (1..2).map { value -> serializationsDuring { store.set(CountedData::box, CountedBox(value)); store.saveImmediate() } }
            println("    deux sauvegardes devant cet auditeur : $perSave sérialisations, l'écriture du fichier plus la copie qu'il reçoit")
            show(saves)
            check(atRegistration == 1 && perSave.all { it == 2 })
            check(saves[0].old is CapturedValue.Initial && saves[1].old is CapturedValue.DeepCopy)
        }
    }

    @Test
    fun `étape 4, le bord, un callback enregistré sur un store déjà modifié n'a pas d'avant à son premier save`() {
        countedStore(noAutoSave).use { store ->
            store.set(CountedData::box, CountedBox(1)) // modifié avant l'écoute : la mémoire n'est plus l'état du dernier save
            val saves = mutableListOf<SaveOperation<CountedData>>()
            store.registerOnSave { saves.add(it as SaveOperation<CountedData>) }

            store.saveImmediate()
            store.set(CountedData::box, CountedBox(2))
            store.saveImmediate()

            show(saves)
            check(saves[0].old is CapturedValue.Unavailable && saves[1].old is CapturedValue.DeepCopy)
        }
    }

    @Test
    fun `étape 5, le prix de la copie sur une racine de cinq mille joueurs, que seul un store écouté paie`() {
        fun filledStore() = StoreFactory.createFromConstructor<Roster>(newStorePath("roster.json").toString(), config = noAutoSave).also { store ->
            store.mutate(Roster::players) { players -> players.addAll((1..5_000).map { Player("player$it", mutableListOf("base", "mine", "farm")) }) }
            store.saveImmediate()
        }

        fun averageSaveMs(listened: Boolean): Double = filledStore().use { store ->
            if (listened) store.registerOnSave { }
            repeat(10) { store.saveImmediate() } // la chauffe du JIT
            measureNanoTime { repeat(20) { store.saveImmediate() } } / 20 / 1_000_000.0
        }

        filledStore().use { store ->
            val root = store.data
            repeat(200) { store.copier.copy(Roster.serializer(), root) } // la chauffe du JIT
            val copyMs = measureNanoTime { repeat(50) { store.copier.copy(Roster.serializer(), root) } } / 50 / 1_000_000.0
            println("    la racine : 5 000 joueurs, ${Files.size(store.path) / 1024} Ko sur le disque")
            println("    une copie profonde de cette racine : %.2f ms".format(copyMs))
        }
        println("    une sauvegarde sans auditeur      : %.2f ms en moyenne, l'écriture seule".format(averageSaveMs(listened = false)))
        println("    une sauvegarde devant un auditeur : %.2f ms en moyenne, l'écriture plus la copie".format(averageSaveMs(listened = true)))
    }
}
