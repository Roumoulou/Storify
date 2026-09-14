package fr.moulou.storify.factory

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AcceptingValidator
import fr.moulou.storify.support.AnnotatedMetaData
import fr.moulou.storify.support.AnnotatedTomlData
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.BareValidatedData
import fr.moulou.storify.support.NotSerializableData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.RejectingByAnnotationData
import fr.moulou.storify.support.TomlishData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validation.ValidationException
import kotlinx.serialization.SerializationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * La préséance de la factory, épinglée pour chacun des quatre paramètres : explicite > annotation
 * > repli. Et le fail-fast de C-09 : un DATA non sérialisable échoue à la création.
 */
class ResolutionTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    // ─── Le format ───

    @Test
    fun `le format explicite bat l'annotation et l'extension`() {
        val path = newStorePath("config.toml")
        StoreFactory.createFromConstructor<AnnotatedTomlData>(path.toString(), format = JsonFormat()).use { }
        assertTrue(path.readText().trimStart().startsWith("{")) // du JSON malgré l'annotation TOML et l'extension .toml
    }

    @Test
    fun `l'annotation de format bat l'extension du chemin`() {
        val path = newStorePath("data.json")
        StoreFactory.createFromConstructor<AnnotatedTomlData>(path.toString()).use { }
        assertFalse(path.readText().trimStart().startsWith("{")) // du TOML dans un .json : l'annotation gagne, comportement épinglé
        assertTrue(path.readText().contains("title"))
    }

    @Test
    fun `sans annotation, l'extension choisit dans le registre`() {
        val jsonPath = newStorePath("d.json")
        val tomlPath = newStorePath("d.toml")
        StoreFactory.createFromConstructor<PlainData>(jsonPath.toString(), config = noAutoSave).use { }
        StoreFactory.createFromConstructor<TomlishData>(tomlPath.toString(), config = noAutoSave).use { }
        assertTrue(jsonPath.readText().trimStart().startsWith("{"))
        assertTrue(tomlPath.readText().contains("title = "))
    }

    // ─── La config ───

    @Test
    fun `la config explicite bat l'annotation, le sidecar en témoin`() {
        val path = newStorePath("meta-off.json")
        StoreFactory.createFromConstructor<AnnotatedMetaData>(path.toString(), config = StoreConfig(withAutoSave = false, withMeta = false)).use { it.saveImmediate() }
        assertFalse(Files.exists(path.resolveSibling("${path.fileName}.meta.json"))) // l'annotation voulait un meta, l'explicite a dit non
    }

    @Test
    fun `la config annotée s'applique sans config explicite`() {
        val path = newStorePath("meta-on.json")
        StoreFactory.createFromConstructor<AnnotatedMetaData>(path.toString()).use { it.saveImmediate() }
        assertTrue(Files.exists(path.resolveSibling("${path.fileName}.meta.json")))
    }

    @Test
    fun `sans aucune config, la validation suit StoreConfig et reste éteinte malgré StoreValidator`() {
        // Le désaccord épinglé : le défaut de StoreConfig() est withValidation = false...
        val path = newStorePath("bare.json")
        path.writeText("""{"name": ""}""")
        StoreFactory.createFromConstructor<BareValidatedData>(path.toString()).use { store ->
            assertEquals("", store.data.name) // la valeur invalide entre sans un mot : pas de config, pas de validation
        }
    }

    @Test
    fun `StoreConfiguration vide allume la validation, son propre défaut étant true`() {
        // ... alors que le défaut de l'annotation @StoreConfiguration est withValidation = true.
        val path = newStorePath("annotated-validated.json")
        path.writeText("""{"name": ""}""")
        assertThrows(ValidationException::class.java) { StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString()) }
    }

    // ─── Le validator ───

    @Test
    fun `le validator explicite bat l'annoté`() {
        // L'annoté refuse tout : si le store naît, c'est l'explicite (qui accepte tout) qui a tourné.
        StoreFactory.createFromConstructor<RejectingByAnnotationData>(newStorePath("who.json").toString(), validator = AcceptingValidator()).use { store ->
            assertEquals("ok", store.data.name)
        }
        // Le contrôle : sans validator explicite, l'annoté tourne et refuse.
        assertThrows(ValidationException::class.java) {
            StoreFactory.createFromConstructor<RejectingByAnnotationData>(newStorePath("who2.json").toString())
        }
    }

    // ─── Le fail-fast C-09 ───

    @Test
    fun `un DATA non sérialisable échoue à la création, avant tout fichier`() {
        val path = newStorePath("never.json")
        assertThrows(SerializationException::class.java) {
            StoreFactory.createFromConstructor<NotSerializableData>(path.toString(), config = noAutoSave)
        }
        assertFalse(Files.exists(path))
    }
}
