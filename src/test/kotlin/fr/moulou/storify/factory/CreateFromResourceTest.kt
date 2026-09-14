package fr.moulou.storify.factory

import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedData
import fr.moulou.storify.support.ResourceData
import fr.moulou.storify.support.ValidatedResourceData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.support.resetAnnotatedFile
import fr.moulou.storify.validation.ValidationException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.io.path.writeText

/**
 * La voie `createFromResource` : au premier lancement, une ressource du classpath est copiée vers
 * le fichier cible puis décodée. La copie reste sur disque même invalide (le voeu d'origine, C-06).
 */
class CreateFromResourceTest {

    @Test
    fun `la voie annotée copie la ressource puis la décode`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/resource-data.json")
        StoreFactory.createFromResource<ResourceData>().use { store ->
            assertEquals("resource", store.data.origin)
            assertEquals(42, store.data.level)
            assertTrue(Files.exists(Paths.get("build/tmp/storify-tests/annotated/resource-data.json")))
        }
    }

    @Test
    fun `la voie explicite copie et décode aussi`() {
        val path = newStorePath("res.json")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_default.json").use { store ->
            assertEquals("resource", store.data.origin)
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `une ressource introuvable est refusée net`() {
        val exception = assertThrows(IllegalArgumentException::class.java) {
            StoreFactory.createFromResource<ResourceData>(newStorePath("r.json").toString(), "missing_resource.json")
        }
        assertTrue(exception.message!!.contains("Resource not found"))
    }

    @Test
    fun `StoreDefaultResource absent sur la voie annotée, refus net`() {
        resetAnnotatedFile("build/tmp/storify-tests/annotated/annotated-data.json")
        val exception = assertThrows(IllegalArgumentException::class.java) { StoreFactory.createFromResource<AnnotatedData>() }
        assertTrue(exception.message!!.contains("@StoreDefaultResource"))
    }

    @Test
    fun `un fichier déjà présent gagne, la ressource n'est pas recopiée`() {
        val path = newStorePath("winner.json")
        path.writeText("""{"origin": "disk", "level": 1}""")
        StoreFactory.createFromResource<ResourceData>(path.toString(), "resource-data_default.json").use { store ->
            assertEquals("disk", store.data.origin) // le fichier existant est décodé, la ressource ignorée
        }
    }

    @Test
    fun `une ressource invalide reste sur disque et les erreurs pointent la ligne`() {
        val path = newStorePath("bad.json")
        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromResource<ValidatedResourceData>(path.toString(), "resource-data_invalid.json")
        }
        assertTrue(Files.exists(path)) // la copie reste, éditable
        assertTrue(exception.message!!.contains("line")) // et les erreurs pointent la ligne dans cette copie
    }
}
