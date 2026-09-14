package fr.moulou.storify.persistence

import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStoreDir
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

/**
 * L'écriture atomique (C-02) : jamais de temporaire survivant, les orphelins balayés, la tempête
 * concurrente relue entière, et les dossiers parents garantis même pour un format qui les oublie
 * (la leçon C-04, généralisée par C-09).
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
}
