// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.validation

import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths
import kotlin.io.path.writeText

/**
 * L'enrichisseur de lignes (C-32) : clés de la racine, chemins imbriqués, clés de map entre crochets, index de tableaux (objets et primitives),
 * JSON5 (clés nues, apostrophes, commentaires) ; et ses limites assumées : une clé par ligne, l'échec isolé par erreur, aucun localisateur en TOML.
 */
class ErrorEnricherTest {

    private fun error(path: String, field: String?) = ValidationError(path = path, className = "T", field = field, message = "m")

    private fun lineOf(format: StoreFormat, text: String, field: String, path: String = "Root", name: String = "f.json"): Int? {
        val file = newStorePath(name)
        file.writeText(text)
        return ValidationErrorEnricher.enrich(format, file, listOf(error(path, field))).single().line
    }

    @Test
    fun `une clé simple à la racine reçoit sa ligne`() {
        assertEquals(2, lineOf(JsonFormat(), "{\n  \"name\": \"x\"\n}", "name"))
    }

    @Test
    fun `un chemin imbriqué descend aux bonnes profondeurs`() {
        assertEquals(3, lineOf(JsonFormat(), "{\n  \"child\": {\n    \"leaf\": 1\n  }\n}", "leaf", path = "Root.child"))
    }

    @Test
    fun `un index de tableau compte les objets pour trouver le bon`() {
        val text = "{\n  \"items\": [\n    {\n      \"a\": 1\n    },\n    {\n      \"a\": 2\n    }\n  ]\n}"
        assertEquals(7, lineOf(JsonFormat(), text, "a", path = "Root.items[1]")) // le "a" du second objet du tableau
    }

    @Test
    fun `une clé de map entre crochets se retrouve comme une clé`() {
        val text = "{\n  \"players\": {\n    \"steve\": {\n      \"joinCount\": -1\n    }\n  }\n}"
        assertEquals(4, lineOf(JsonFormat(), text, "players[steve].joinCount"))
        assertEquals(4, lineOf(JsonFormat(), text, "players[\"steve\"].joinCount"))
    }

    @Test
    fun `une clé qui contient un point se lit entre crochets`() {
        val text = "{\n  \"homes\": {\n    \"my.home\": {\n      \"x\": 1\n    }\n  }\n}"
        assertEquals(4, lineOf(JsonFormat(), text, "homes[my.home].x"))
    }

    @Test
    fun `un tableau de primitives se pointe par son index, un tableau en ligne par sa clé`() {
        assertEquals(4, lineOf(JsonFormat(), "{\n  \"tags\": [\n    \"a\",\n    \"\"\n  ]\n}", "tags[1]"))
        assertEquals(2, lineOf(JsonFormat(), "{\n  \"tags\": [\"a\", \"\"]\n}", "tags[1]")) // au mieux : la ligne de la clé
    }

    @Test
    fun `un chemin illisible ne prive pas les autres erreurs de leur ligne`() {
        val file = newStorePath("mixed.json")
        file.writeText("{\n  \"name\": \"x\"\n}")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), file, listOf(error("Root", "players["), error("Root", "name")))

        assertNull(enriched[0].line)
        assertEquals(2, enriched[1].line)
    }

    @Test
    fun `JSON5, clés nues, apostrophes et commentaires`() {
        val text = "{\n  // la guilde\n  name: '',\n  /* un bloc\n     sur deux lignes */\n  'players': {\n    steve: {\n      joinCount: -1, // négatif\n    },\n  },\n}"
        assertEquals(3, lineOf(Json5Format(), text, "name", name = "g.json5"))
        assertEquals(8, lineOf(Json5Format(), text, "players[steve].joinCount", name = "g.json5"))
    }

    @Test
    fun `un format sans localisateur rend les erreurs inchangées`() {
        assertNull(lineOf(TomlFormat(), "name = \"x\"\n", "name", name = "data.toml"))
    }

    @Test
    fun `un fichier illisible reste silencieux, les erreurs inchangées`() {
        val missing = Paths.get("build", "tmp", "storify-tests", "nulle-part", "absent.json")

        val enriched = ValidationErrorEnricher.enrich(JsonFormat(), missing, listOf(error("Root", "name")))

        assertNull(enriched.single().line)
    }

    @Test
    fun `un BOM en tête du fichier ne dérange pas les lignes`() {
        assertEquals(2, lineOf(JsonFormat(), "﻿{\n  \"name\": \"x\"\n}", "name"))
    }
}
