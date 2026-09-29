// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.lifecycle

import fr.moulou.storify.Operation
import fr.moulou.storify.ReloadOperation
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.mutate
import fr.moulou.storify.core.mutateIn
import fr.moulou.storify.core.set
import fr.moulou.storify.core.setIn
import fr.moulou.storify.core.transaction
import fr.moulou.storify.support.InnerLeaf
import fr.moulou.storify.support.OuterData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.awaitTrue
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Le mode lecture seule (C-30) : toute écriture refusée, la lecture, la validation et le rechargement intacts, ni hook ni planificateur ; le
 * fichier initial écrit une fois ou jamais (`createIfMissing`) ; et le hook débrayable seul (`withShutdownHook`).
 */
class ReadOnlyTest {

    private val readOnly = StoreConfig(readOnly = true)

    @Test
    fun `un store en lecture seule refuse set, setIn, mutate, mutateIn, transaction et saveImmediate`() {
        StoreFactory.create<OuterData>(newStorePath("ro.json").toString(), config = readOnly).use { store ->
            assertTrue(store.isReadOnly)
            val refusals = listOf<() -> Unit>(
                { store.set(OuterData::title, "écrit") },
                { store.setIn(InnerLeaf::label, "écrit") { leaf } },
                { store.mutate(OuterData::registry) { it["k"] = "v" } },
                { store.mutateIn(InnerLeaf::hits, { leaf }) { it.add(1) } },
                { store.transaction { title = "écrit" } },
                { store.saveImmediate() },
            )
            refusals.forEach { refusal ->
                val exception = assertThrows(IllegalStateException::class.java) { refusal() }
                assertTrue(exception.message!!.contains("read-only"))
            }
            assertEquals("outer", store.data.title) // rien n'est passé
            assertEquals("leaf", store.data.leaf.label)
            assertTrue(store.data.registry.isEmpty())
            assertTrue(store.data.leaf.hits.isEmpty())
            assertFalse(store.isDirty)
        }
    }

    @Test
    fun `un store en lecture seule relit le fichier, se valide et notifie le reload`() {
        val path = newStorePath("ro-reload.json")
        StoreFactory.create<PlainData>(path.toString(), config = readOnly).use { store ->
            val reloads = mutableListOf<Operation<PlainData>>()
            store.registerOnReload { reloads.add(it) }

            path.writeText(path.readText().replace("\"default\"", "\"edited\""))
            store.reloadFromFile()

            assertEquals("edited", store.data.name)
            assertInstanceOf(ReloadOperation::class.java, reloads.single())
            assertTrue(store.validateNow().isValid)
        }
    }

    @Test
    fun `le fichier initial d'un store en lecture seule s'écrit une fois, puis plus jamais`() {
        val path = newStorePath("ro-initial.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = readOnly)
        assertTrue(Files.exists(path))         // la seule écriture de sa vie
        assertFalse(store.isShutdownHookArmed) // et aucun hook pour en faire une autre

        path.writeText(path.readText().replace("\"default\"", "\"édité à la main\""))
        store.runShutdownHook()
        store.close()

        assertTrue(path.readText().contains("édité à la main")) // ni le hook ni close n'ont réécrit
    }

    @Test
    fun `sans createIfMissing, aucun fichier initial n'est écrit, un saveImmediate ultérieur le crée`() {
        val path = newStorePath("lazy.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, createIfMissing = false)).use { store ->
            assertFalse(Files.exists(path))
            assertEquals("default", store.data.name) // les défauts vivent en mémoire

            store.saveImmediate()
            assertTrue(Files.exists(path)) // createIfMissing ne gouverne que l'ouverture
        }
    }

    @Test
    fun `en lecture seule sans createIfMissing, pas un octet sur le disque`() {
        val path = newStorePath("never.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(readOnly = true, createIfMissing = false, withMeta = true)).use { store ->
            assertEquals("default", store.data.name)
        }
        assertFalse(Files.exists(path))
        assertFalse(Files.exists(path.resolveSibling("never.json.meta.json")))
    }

    @Test
    fun `en lecture seule, withAutoSave ne planifie rien et les interrupteurs sont inertes`() {
        val path = newStorePath("ro-tick.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(readOnly = true, withAutoSave = true, autoSaveIntervalMs = 50)).use { store ->
            assertFalse(store.isAutoSaveScheduled)
            store.pauseAutoSave()
            assertFalse(store.isAutoSavePaused()) // inerte

            path.writeText(path.readText().replace("\"default\"", "\"édité à la main\""))
            assertFalse(awaitTrue(timeoutMs = 300) { !path.readText().contains("édité à la main") }) // aucun tick n'a réécrit en 300 ms
        }
    }

    @Test
    fun `withShutdownHook à false n'arme pas le hook, et close fait toujours sa sauvegarde d'adieu`() {
        StoreFactory.create<PlainData>(newStorePath("hook.json").toString(), config = StoreConfig(withAutoSave = false)).use { store ->
            assertTrue(store.isShutdownHookArmed) // le défaut : le filet anti-crash est là
        }

        val path = newStorePath("nohook.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, withShutdownHook = false))
        assertFalse(store.isShutdownHookArmed)

        store.set(PlainData::name, "adieu")
        store.close()

        assertTrue(path.readText().contains("adieu")) // la sauvegarde d'adieu ne dépend pas du hook
    }
}