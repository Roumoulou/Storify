package fr.moulou.storify.validation

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.io.path.writeText

/**
 * L'enrichisseur de lignes (JSON pretty-printed, une clé par ligne) : clé racine, chemin imbriqué,
 * index de tableau ; et ses limites assumées : JSON seulement, échec silencieux.
 */
class ErrorEnricherTest {

    private fun error(path: String, field: String?) = ValidationError(path = path, className = "T", field = field, message = "m")

    @Test
    fun `une clé simple à la racine reçoit sa ligne`() {
        val file = newStorePath("root.json")
        file.writeText("{\n  \"name\": \"x\"\n}")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), file, listOf(error("Root", "name")))

        assertEquals(2, enriched.single().jsonLine)
    }

    @Test
    fun `un chemin imbriqué descend aux bonnes profondeurs`() {
        val file = newStorePath("nested.json")
        file.writeText("{\n  \"child\": {\n    \"leaf\": 1\n  }\n}")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), file, listOf(error("Root.child", "leaf")))

        assertEquals(3, enriched.single().jsonLine)
    }

    @Test
    fun `un index de tableau compte les objets pour trouver le bon`() {
        val file = newStorePath("array.json")
        file.writeText("{\n  \"items\": [\n    {\n      \"a\": 1\n    },\n    {\n      \"a\": 2\n    }\n  ]\n}")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), file, listOf(error("Root.items[1]", "a")))

        assertEquals(7, enriched.single().jsonLine) // le "a" du second objet du tableau
    }

    @Test
    fun `un format non JSON rend les erreurs inchangées`() {
        val file = newStorePath("data.toml")
        file.writeText("name = \"x\"\n")

        val enriched = ValidationErrorEnricher.enrich(TomlFormat(), file, listOf(error("Root", "name")))

        assertNull(enriched.single().jsonLine)
    }

    @Test
    fun `un fichier illisible reste silencieux, les erreurs inchangées`() {
        val missing = Paths.get("build", "tmp", "storify-tests", "nulle-part", "absent.json")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), missing, listOf(error("Root", "name")))

        assertNull(enriched.single().jsonLine)
    }
}
