// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.persistence

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.encodeToPathAtomically
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.utils.AtomicFiles
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * L'écriture atomique (C-02) : jamais de temporaire survivant, les orphelins balayés au seul motif propre (C-28), la tempête
 * concurrente relue entière, les dossiers parents garantis même pour un format qui les oublie (la leçon C-04, généralisée par C-09),
 * le fichier précédent entier après un crash au milieu de l'écriture, et le même écrivain public hors store (C-34).
 */
class AtomicWriteTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    /** Un format tiers étourdi : il ne crée pas les dossiers parents ; `atomicWrite` doit couvrir. */
    class ForgetfulFormat : StoreFormat {
        private val json = Json { encodeDefaults = true }

        @OptIn(ExperimentalSerializationApi::class)
        override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
            return path.inputStream().use { stream -> json.decodeFromStream(deserializer, stream) }
        }

        @OptIn(ExperimentalSerializationApi::class)
        override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
            path.outputStream().use { stream -> json.encodeToStream(serializer, data, stream) } // aucun createDirectories, exprès
        }

        override fun fileExtension(): String = "forgetful"
    }

    /** Un format qui tombe en panne au milieu de l'écriture : la moitié du JSON, puis une IOException. */
    class CrashingFormat : StoreFormat {
        private val json = JsonFormat()
        override fun fileExtension(): String = "json"
        override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA = json.decodeFromPath(deserializer, path)
        override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
            path.outputStream().use { stream ->
                stream.write("{\n  \"name\": \"à moitié".toByteArray())
                throw IOException("disque plein au milieu de l'écriture")
            }
        }
    }

    private fun noTempLeft(directory: Path): Boolean = Files.list(directory).use { stream -> stream.noneMatch { it.fileName.toString().endsWith(".tmp") } }

    @Test
    fun `aucun fichier temporaire ne survit à une sauvegarde`() {
        val path = newStorePath("clean.json")
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { store ->
            store.set(PlainData::name, "propre")
            store.saveImmediate()
        }
        val leftovers = Files.list(path.parent).use { stream -> stream.filter { it.fileName.toString().endsWith(".tmp") }.toList() }
        assertTrue(leftovers.isEmpty())
    }

    @Test
    fun `un temporaire orphelin d'un crash passé est balayé à l'ouverture`() {
        val path = newStorePath("swept.json")
        val orphan = path.resolveSibling("${path.fileName}.deadbeef.tmp")
        orphan.writeText("{ tronqué par un faux crash")

        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { }

        assertFalse(Files.exists(orphan))
    }

    @Test
    fun `des sauvegardes concurrentes laissent toujours un fichier entier`() {
        val path = newStorePath("storm.json")
        val store = StoreFactory.create<PlainData>(path.toString(), config = noAutoSave)

        val threads = (1..4).map { threadNumber ->
            Thread {
                repeat(25) { i ->
                    store.set(PlainData::name, "t$threadNumber-i$i")
                    store.saveImmediate()
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        store.close()

        // La preuve d'intégrité : un store neuf relit le fichier sans broncher, quelle que soit la valeur gagnante.
        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { reloaded ->
            assertTrue(reloaded.data.name.startsWith("t"))
        }
    }

    @Test
    fun `atomicWrite garantit les dossiers parents même pour un format qui les oublie`() {
        val path = newStoreDir().resolve("deep").resolve("deeper").resolve("data.forgetful") // deux dossiers inexistants
        StoreFactory.create<PlainData>(path.toString(), format = ForgetfulFormat(), config = noAutoSave).use { store ->
            assertTrue(Files.exists(path)) // le fichier initial est né : les parents venaient d'atomicWrite, pas du format
            store.set(PlainData::name, "couvert")
            store.saveImmediate()
        }
        StoreFactory.create<PlainData>(path.toString(), format = ForgetfulFormat(), config = noAutoSave).use { reloaded ->
            assertTrue(reloaded.data.name == "couvert")
        }
    }

    @Test
    fun `un temporaire étranger survit au balayage, seul le motif propre est balayé`() {
        val path = newStorePath("homes.json")
        val foreign = listOf("homes.json.tmp", "homes.json.backup.tmp").map { path.resolveSibling(it) }
        foreign.forEach { it.writeText("écrit par une autre application") }
        val own = listOf("homes.json.0badf00d.tmp", "homes.json.meta.json.0badf00d.tmp").map { path.resolveSibling(it) }
        own.forEach { it.writeText("{ tronqué par un faux crash") }

        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { }

        foreign.forEach { assertTrue(Files.exists(it), "temporaire étranger supprimé : ${it.fileName}") }
        own.forEach { assertFalse(Files.exists(it), "temporaire propre survivant : ${it.fileName}") }
    }

    @Test
    fun `un crash au milieu de l'écriture laisse le fichier précédent entier, sans temporaire`() {
        val path = newStorePath("crash.json")
        JsonFormat().encodeToPath(PlainData.serializer(), PlainData(name = "entier"), path)
        val store = StoreFactory.create<PlainData>(path.toString(), format = CrashingFormat(), config = noAutoSave)
        store.set(PlainData::name, "jamais écrit")

        assertThrows(IOException::class.java) { store.saveImmediate() }

        assertTrue(path.readText().contains("entier")) // la panne a frappé le temporaire, pas la cible
        assertTrue(noTempLeft(path.parent))
        runCatching { store.close() } // la sauvegarde d'adieu retente et échoue de même
    }

    @Test
    fun `AtomicFiles écrit hors store aux mêmes garanties, et encodeToPathAtomically avec`() {
        val path = newStoreDir().resolve("deep").resolve("export.json") // un dossier parent inexistant
        JsonFormat().encodeToPathAtomically(PlainData.serializer(), PlainData(name = "export"), path)
        assertEquals("export", JsonFormat().decodeFromPath(PlainData.serializer(), path).name)

        assertThrows(IOException::class.java) { AtomicFiles.write(path) { temp -> CrashingFormat().encodeToPath(PlainData.serializer(), PlainData(), temp) } }

        assertEquals("export", JsonFormat().decodeFromPath(PlainData.serializer(), path).name) // intact
        assertTrue(noTempLeft(path.parent))
    }

    @Test
    fun `le balayage public épargne les temporaires étrangers`() {
        val path = newStorePath("homes.json")
        val foreign = path.resolveSibling("homes.json.tmp").also { it.writeText("étranger") }
        val own = path.resolveSibling("homes.json.0badf00d.tmp").also { it.writeText("orphelin") }

        AtomicFiles.sweepOrphanTemps(path)

        assertTrue(Files.exists(foreign))
        assertFalse(Files.exists(own))
    }
}
