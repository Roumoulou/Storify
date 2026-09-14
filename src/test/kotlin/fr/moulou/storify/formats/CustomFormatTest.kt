package fr.moulou.storify.formats

import fr.moulou.storify.*
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.utils.Utils
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

// ─── Le format « tiers » ──────────────────────────────────────────────────────────────

/**
 * Un format inconnu de la lib, comme en écrirait un consommateur : il délègue à une instance Json
 * dédiée sous une extension à lui (on teste le point d'extension C-09, pas un parseur).
 */
class CustomFormat : StoreFormat {

    private val json = Json { prettyPrint = false; encodeDefaults = true }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> decodeFromPath(deserializer: DeserializationStrategy<DATA>, path: Path): DATA {
        return path.inputStream().use { stream -> json.decodeFromStream(deserializer, stream) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    override fun <DATA> encodeToPath(serializer: SerializationStrategy<DATA>, data: DATA, path: Path) {
        path.parent?.createDirectories()
        path.outputStream().use { stream -> json.encodeToStream(serializer, data, stream) }
    }

    override fun fileExtension(): String = "custom"
}

@Serializable
data class CustomPayload(
    var name: String = "steve",
    var count: Int = 3,
)

// ─── Les tests ────────────────────────────────────────────────────────────────────────

/**
 * C-09 : le point d'extension des formats, exercé de bout en bout par un format que la lib ne
 * connaît pas. Avant le chantier, la factory le rejetait (le `when` figé de ses encoders).
 */
class CustomFormatTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private fun newStorePath(fileName: String): Path {
        val directory = Paths.get("build", "tmp", "storify-tests", UUID.randomUUID().toString())
        Files.createDirectories(directory)
        return directory.resolve(fileName)
    }

    @Test
    fun `un format enregistré se résout par l'extension et fait l'aller-retour complet`() {
        Utils.registerFormat("custom", CustomFormat())
        val path = newStorePath("payload.custom")

        StoreFactory.createFromConstructor<CustomPayload>(path.toString(), config = noAutoSave).use { store ->
            store.set(CustomPayload::count, 42)
            store.saveImmediate()
        }

        StoreFactory.createFromConstructor<CustomPayload>(path.toString(), config = noAutoSave).use { reloaded ->
            assertEquals(42, reloaded.data.count) // écrit par le format tiers, relu par lui : le trompe-l'oeil est mort
        }
    }

    @Test
    fun `un format passé explicitement est utilisé sans enregistrement`() {
        val path = newStorePath("payload.anything")

        StoreFactory.createFromConstructor<CustomPayload>(path.toString(), format = CustomFormat(), config = noAutoSave).use { store ->
            store.set(CustomPayload::name, "alex")
            store.saveImmediate()
        }

        StoreFactory.createFromConstructor<CustomPayload>(path.toString(), format = CustomFormat(), config = noAutoSave).use { reloaded ->
            assertEquals("alex", reloaded.data.name)
        }
    }

    @Test
    fun `une extension inconnue est refusée net au lieu de deviner un format`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromConstructor<CustomPayload>(newStorePath("payload.zzz").toString(), config = noAutoSave)
        }
        assertTrue(exception.message!!.contains("zzz")) // le message nomme l'extension fautive et les formats connus
    }

}
