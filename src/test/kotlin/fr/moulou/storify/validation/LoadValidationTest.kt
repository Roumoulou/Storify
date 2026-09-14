package fr.moulou.storify.validation

import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.AnnotatedValidatedData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.writeText

/**
 * La qualité du rapport d'échec au chargement : chemin complet, valeur rejetée et ligne pour le
 * JSON ; pas de numéro de ligne pour le TOML, la limite assumée de l'enrichisseur, épinglée.
 */
class LoadValidationTest {

    @Test
    fun `le message d'échec porte le chemin complet, la valeur rejetée et la ligne JSON`() {
        val path = newStorePath("invalid.json")
        path.writeText("{\n  \"name\": \"\"\n}")

        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString())
        }

        assertTrue(exception.message!!.contains("AnnotatedValidatedData.name")) // le chemin complet
        assertTrue(exception.message!!.contains("(was: \"\")"))                 // la valeur rejetée
        assertTrue(exception.message!!.contains("line 2"))                      // la ligne du champ fautif
    }

    @Test
    fun `en TOML, les erreurs restent sans numéro de ligne, la limite épinglée`() {
        val path = newStorePath("invalid.toml")
        path.writeText("name = \"\"\n")

        val exception = assertThrows(ValidationException::class.java) {
            StoreFactory.createFromConstructor<AnnotatedValidatedData>(path.toString())
        }

        assertTrue(exception.message!!.contains("must not be blank")) // le diagnostic est là...
        assertFalse(exception.message!!.contains("line"))             // ... mais l'enrichisseur ne parle que JSON
    }
}
