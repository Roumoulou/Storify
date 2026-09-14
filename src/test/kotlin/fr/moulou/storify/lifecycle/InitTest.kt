package fr.moulou.storify.lifecycle

import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.InvalidByDefaultData
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validation.ValidationException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.writeText

/**
 * L'ordre d'initialisation (C-06) : la validation passe avant toute écriture, les messages disent
 * l'origine des données, et l'ouverture balaye les restes d'un crash passé.
 */
class InitTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    @Test
    fun `des défauts invalides ne créent jamais de fichier`() {
        val path = newStorePath("never-born.json")

        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromConstructor<InvalidByDefaultData>(path.toString())
        }

        assertTrue(exception.message!!.contains("default data")) // le remède est dans le code, le message le dit
        assertFalse(Files.exists(path)) // la validation passe avant toute écriture
    }

    @Test
    fun `un fichier invalide au chargement dit qu'il vient du fichier et pointe la ligne`() {
        val path = newStorePath("invalid.json")
        path.writeText("{\n  \"name\": \"\"\n}")

        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString())
        }

        assertTrue(exception.message!!.contains("loaded from file"))
        assertTrue(exception.message!!.contains("line 2")) // l'enrichisseur a retrouvé la clé dans le fichier
    }

    @Test
    fun `des défauts valides écrivent leur fichier initial, la validation étant passée`() {
        val path = newStorePath("born.json")
        StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString()).use {
            assertTrue(Files.exists(path))
        }
    }

    @Test
    fun `la création réussit dans un dossier propre même après un faux crash`() {
        val path = newStorePath("recovery.json")
        path.resolveSibling("${path.fileName}.cafe1234.tmp").writeText("{ reste d'un crash")

        StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use {
            val leftovers = Files.list(path.parent).use { stream -> stream.filter { p -> p.fileName.toString().endsWith(".tmp") }.toList() }
            assertTrue(leftovers.isEmpty()) // l'ouverture a fait le ménage
        }
    }
}
