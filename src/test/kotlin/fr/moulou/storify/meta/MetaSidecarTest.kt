package fr.moulou.storify.meta

import fr.moulou.storify.StoreMeta
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.awaitTrue
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * Le sidecar meta : n'existe qu'avec `withMeta`, s'écrit au save, ses horodatages suivent le motif
 * exact, `lastModified` vit avec les updates (SKIP compris), et il est toujours du JSON, quel que
 * soit le format du store (C-09).
 */
class MetaSidecarTest {

    private val timestampPattern = Regex("""\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}:\d{3}""")

    private fun sidecarOf(path: Path): Path = path.resolveSibling("${path.fileName}.meta.json")

    private fun readSidecar(path: Path): StoreMeta = Json.decodeFromString(StoreMeta.serializer(), sidecarOf(path).readText())

    @Test
    fun `sans withMeta, aucun sidecar ne naît`() {
        val path = newStorePath("nometa.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false)).use { it.saveImmediate() }
        assertFalse(Files.exists(sidecarOf(path)))
    }

    @Test
    fun `avec withMeta, le sidecar naît au save, horodatages au motif exact et version à 1`() {
        val path = newStorePath("meta.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = true)).use { it.saveImmediate() }

        val meta = readSidecar(path)
        assertTrue(timestampPattern.matches(meta.createdAt))
        assertTrue(timestampPattern.matches(meta.lastModified))
        assertEquals(1, meta.version)
    }

    @Test
    fun `lastModified vit avec les updates, SKIP compris, et createdAt ne bouge jamais`() {
        val path = newStorePath("living.json")
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = true)).use { store ->
            val createdAt = store.meta!!.createdAt
            val before = store.meta.lastModified

            awaitTrue(timeoutMs = 15) { false } // laisse l'horloge avancer d'une poignée de millisecondes
            store.set(PlainData::count, 9)      // policy par défaut SKIP : le meta bouge quand même (markDirty)

            assertTrue(store.meta.lastModified >= before) // le motif est trié lexicographiquement
            assertEquals(createdAt, store.meta.createdAt)
        }
    }

    @Test
    fun `custom fait l'aller-retour disque et createdAt survit à la réouverture`() {
        val path = newStorePath("custom.json")
        val firstCreatedAt: String
        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = true)).use { store ->
            store.meta!!.setCustom("owner", "storibench")
            store.saveImmediate()
            firstCreatedAt = store.meta.createdAt
        }

        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = true)).use { reopened ->
            assertEquals("storibench", reopened.meta!!.getCustom("owner"))
            assertEquals(firstCreatedAt, reopened.meta.createdAt) // le sidecar existant est relu, pas recréé
        }
    }

    @Test
    fun `le sidecar d'un store TOML est du JSON, comme son nom le promet`() {
        val path = newStorePath("config.toml")
        StoreFactory.create<TomlishData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = true)).use { it.saveImmediate() }

        assertTrue(Files.exists(sidecarOf(path)))
        val meta = readSidecar(path) // un sidecar TOML ferait échouer ce décodage JSON (décision C-09)
        assertEquals(1, meta.version)
    }
}
