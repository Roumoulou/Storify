// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.persistence

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.updates.CountedBoxSerializer
import fr.moulou.storify.updates.CountedData
import fr.moulou.storify.validation.ValidationException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * `reloadFromFile` (C-05) : relit et notifie, revalide par défaut avec mémoire intacte en échec,
 * offre son échappatoire documentée, et un fichier malformé ne corrompt jamais la mémoire.
 */
class ReloadTest {

    private val snapshotConfig = StoreConfig(withAutoSave = false, defaultUpdatePolicy = UpdatePolicy.SNAPSHOT)

    @Test
    fun `reloadFromFile relit le fichier édité à la main et notifie onReload`() {
        val path = newStorePath("reload.json")
        StoreFactory.create<PlainData>(path.toString(), config = snapshotConfig).use { store ->
            val reloads = mutableListOf<Operation<PlainData>>()
            store.registerOnReload { reloads.add(it) }

            path.writeText(path.readText().replace("\"default\"", "\"edited\""))
            store.reloadFromFile()

            assertEquals("edited", store.data.name)
            assertInstanceOf(ReloadOperation::class.java, reloads.single())
        }
    }

    @Test
    fun `le reload revalide par défaut et laisse la mémoire intacte en échec`() {
        val path = newStorePath("reload-guard.json")
        StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString()).use { store ->
            store.set(AnnotatedValidatedData::name, "sain")
            store.saveImmediate()

            path.writeText(path.readText().replace("\"sain\"", "\"\"")) // invalide, mais bien formé

            assertThrows(ValidationException::class.java) { store.reloadFromFile() }
            assertEquals("sain", store.data.name) // la mémoire n'a pas bougé

            store.reloadFromFile(validate = false) // l'échappatoire documentée
            assertEquals("", store.data.name)
        }
    }

    @Test
    fun `un fichier malformé au reload échoue au décodage, la mémoire reste intacte`() {
        val path = newStorePath("malformed.json")
        StoreFactory.create<PlainData>(path.toString(), config = snapshotConfig).use { store ->
            path.writeText("{ tronqué, pas du JSON")

            val failure = assertThrows(StoreDecodeException::class.java) { store.reloadFromFile() }
            assertInstanceOf(SerializationException::class.java, failure.cause) // la cause du parseur, conservée (C-33)
            assertEquals("default", store.data.name) // le décodage a échoué AVANT toute affectation
        }
    }

    @Test
    fun `sans useDeepCopy, le reload notifie des captures Shallow au lieu de copier`() {
        val path = newStorePath("reload-shallow.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, useDeepCopy = false)).use { store ->
            val reloads = mutableListOf<Operation<PlainData>>()
            store.registerOnReload { reloads.add(it) }

            path.writeText(path.readText().replace("\"default\"", "\"edited\""))
            store.reloadFromFile()

            val reload = assertInstanceOf(ReloadOperation::class.java, reloads.single())
            assertInstanceOf(CapturedValue.Shallow::class.java, reload.old)
            assertInstanceOf(CapturedValue.Shallow::class.java, reload.new)
            assertSame(store.data, reload.new.valueOrNull) // la référence vivante, pas une copie
        }
    }

    @Test
    fun `sans auditeur de reload, la racine n'est pas copiée`() {
        val path = newStorePath("reload-silent.json")
        StoreFactory.create<CountedData>(path.toString(), config = snapshotConfig).use { store ->
            val silent = CountedBoxSerializer.serializations
            store.reloadFromFile()
            assertEquals(silent, CountedBoxSerializer.serializations) // personne n'écoute : ni copie d'avant ni copie d'après

            store.registerOnReload { }
            val heard = CountedBoxSerializer.serializations
            store.reloadFromFile()
            assertEquals(heard + 2, CountedBoxSerializer.serializations) // devant public : l'avant et l'après
        }
    }
}
