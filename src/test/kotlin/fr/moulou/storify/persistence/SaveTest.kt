// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.persistence

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.updates.CountedBox
import fr.moulou.storify.updates.CountedBoxSerializer
import fr.moulou.storify.updates.CountedData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `saveImmediate` et les captures du save : l'Initial au premier save, la copie du précédent
 * ensuite, l'Unavailable quand le deep copy est coupé ; la vie du drapeau dirty ; et, au compteur de
 * sérialisations, le snapshot du save qui ne se prend que devant un auditeur (C-41).
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
            assertFalse(first.old.isAvailable)                              // ni d'Initial : la racine n'est pas copiée sans useDeepCopy (C-29)
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

    // ─── Le snapshot du save, devant public seulement (C-41) : le compteur mesure les copies réellement faites ───

    @Test
    fun `l'ouverture ne copie pas la racine, avec ou sans useDeepCopy`() {
        val before = CountedBoxSerializer.serializations
        StoreFactory.create<CountedData>(newStorePath("counted-init.json").toString(), config = StoreConfig(withAutoSave = false)).use { }
        assertEquals(before + 1, CountedBoxSerializer.serializations) // le fichier initial s'écrit (une sérialisation), aucune copie ne part

        StoreFactory.create<CountedData>(newStorePath("counted-init-nodeep.json").toString(), config = StoreConfig(withAutoSave = false, useDeepCopy = false)).use { }
        assertEquals(before + 2, CountedBoxSerializer.serializations) // pareil sans useDeepCopy : le réglage ne change plus rien à l'ouverture
    }

    @Test
    fun `sans auditeur de save, une sauvegarde ne copie pas la racine`() {
        StoreFactory.create<CountedData>(newStorePath("counted-silent.json").toString(), config = snapshotConfig).use { store ->
            store.set(CountedData::box, CountedBox(1))
            val before = CountedBoxSerializer.serializations

            store.saveImmediate()

            assertEquals(before + 1, CountedBoxSerializer.serializations) // l'écriture du fichier, et rien d'autre
            assertFalse(store.isDirty)                                    // la sauvegarde, elle, est faite comme toujours
        }
    }

    @Test
    fun `devant un auditeur de save, l'enregistrement prend la référence et chaque sauvegarde sa copie`() {
        StoreFactory.create<CountedData>(newStorePath("counted-heard.json").toString(), config = snapshotConfig).use { store ->
            val atRegistration = CountedBoxSerializer.serializations
            store.registerOnSave { }
            assertEquals(atRegistration + 1, CountedBoxSerializer.serializations) // la référence du premier old, prise sur un store propre

            store.registerOnSave { }
            assertEquals(atRegistration + 1, CountedBoxSerializer.serializations) // un second auditeur n'en reprend pas

            store.set(CountedData::box, CountedBox(1))
            val beforeSave = CountedBoxSerializer.serializations
            store.saveImmediate()
            assertEquals(beforeSave + 2, CountedBoxSerializer.serializations) // l'écriture, plus la copie que les auditeurs reçoivent
        }
    }

    @Test
    fun `un callback enregistré sur un store déjà modifié reçoit Unavailable à son premier save, puis la copie du précédent`() {
        StoreFactory.create<PlainData>(newStorePath("late.json").toString(), config = snapshotConfig).use { store ->
            store.set(PlainData::name, "modifié avant l'écoute")
            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) }

            store.saveImmediate()
            store.set(PlainData::name, "deux")
            store.saveImmediate()

            val first = assertInstanceOf(SaveOperation::class.java, saves[0])
            assertFalse(first.old.isAvailable)                               // la mémoire n'était plus l'état du dernier save : pas de référence
            assertInstanceOf(CapturedValue.DeepCopy::class.java, first.new) // l'après, lui, est là
            val second = assertInstanceOf(SaveOperation::class.java, saves[1])
            assertEquals("modifié avant l'écoute", (second.old.valueOrNull as PlainData).name)
        }
    }

    @Test
    fun `un callback enregistré après des sauvegardes sans auditeur reçoit l'état du dernier save, pas l'Initial`() {
        StoreFactory.create<PlainData>(newStorePath("after.json").toString(), config = snapshotConfig).use { store ->
            store.set(PlainData::name, "un")
            store.saveImmediate() // sans auditeur : ni copie ni opération

            val saves = mutableListOf<Operation<PlainData>>()
            store.registerOnSave { saves.add(it) } // store propre : la référence est l'état que ce save vient d'écrire

            store.set(PlainData::name, "deux")
            store.saveImmediate()

            val save = assertInstanceOf(SaveOperation::class.java, saves.single())
            assertInstanceOf(CapturedValue.DeepCopy::class.java, save.old)
            assertEquals("un", (save.old.valueOrNull as PlainData).name)
            assertEquals("deux", (save.new.valueOrNull as PlainData).name)
        }
    }

    @Test
    fun `en lecture seule, un callback de save ne fait rien copier`() {
        StoreFactory.create<CountedData>(newStorePath("counted-ro.json").toString(), config = StoreConfig(readOnly = true)).use { store ->
            val before = CountedBoxSerializer.serializations
            store.registerOnSave { }
            assertEquals(before, CountedBoxSerializer.serializations) // un tel store ne sauve jamais : pas de référence à prendre
        }
    }
}
