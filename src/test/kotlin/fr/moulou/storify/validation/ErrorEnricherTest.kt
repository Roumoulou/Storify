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
 * JSON5 (clés nues, apostrophes, commentaires) ; le chemin que le fichier n'écrit pas en entier, clé ou index, qui rend au mieux la ligne de son
 * plus proche ancêtre écrit, jamais celle d'un autre conteneur, et `null` quand rien ne s'en retrouve (C-53) ; et ses limites assumées : une clé par ligne, l'échec isolé par erreur, aucun localisateur en TOML.
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
    fun `une clé de map faite de chiffres se retrouve entre guillemets, et nue elle se lit comme un index, la ligne de la map au mieux`() {
        val text = "{\n  \"groups\": {\n    \"123\": {\n      \"weight\": 10\n    }\n  }\n}"
        assertEquals(4, lineOf(JsonFormat(), text, "weight", path = "Root.groups[\"123\"]")) // ce que validateEach écrit (C-60)
        assertEquals(2, lineOf(JsonFormat(), text, "weight", path = "Root.groups[123]")) // un index dans un objet : la ligne de la map, le contrat de la grammaire
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
    fun `une clé absente de son objet rend la ligne de l'objet, pas celle de la même clé dans un autre`() {
        val text = """
            {
              "rules": {
                "vip": {
                  "quota": {
                    "period": 60
                  }
                },
                "staff": {
                  "quota": {
                    "uses": 9,
                    "period": 60
                  }
                }
              }
            }
        """.trimIndent()
        assertEquals(10, lineOf(JsonFormat(), text, "rules[staff].quota.uses"))
        assertEquals(4, lineOf(JsonFormat(), text, "rules[vip].quota"))
        assertEquals(4, lineOf(JsonFormat(), text, "rules[vip].quota.uses")) // le quota de vip n'écrit pas uses : au mieux sa ligne, pas la 10, le uses de staff
    }

    @Test
    fun `une clé de map absente rend la ligne de la map, pas celle de la même clé dans une autre`() {
        val text = """
            {
              "homes": {
                "base": "0 64 0"
              },
              "warps": {
                "spawn": "8 70 8"
              }
            }
        """.trimIndent()
        assertEquals(3, lineOf(JsonFormat(), text, "homes[base]"))
        assertEquals(6, lineOf(JsonFormat(), text, "warps[spawn]"))
        assertEquals(2, lineOf(JsonFormat(), text, "homes[spawn]")) // au mieux la ligne de homes, pas la 6, le spawn de warps
    }

    @Test
    fun `un index au-delà de la fin de son tableau rend la ligne du tableau, pas celle d'un élément du tableau suivant`() {
        val text = """
            {
              "admins": [
                "steve"
              ],
              "builders": [
                "alex",
                "kai"
              ]
            }
        """.trimIndent()
        assertEquals(3, lineOf(JsonFormat(), text, "admins[0]"))
        assertEquals(7, lineOf(JsonFormat(), text, "builders[1]"))
        assertEquals(2, lineOf(JsonFormat(), text, "admins[1]")) // au mieux la ligne de admins, pas la 6, le premier élément de builders
    }

    @Test
    fun `un index au-delà de la fin de son tableau rend la ligne du tableau, pas celle d'une clé de l'objet suivant`() {
        val text = """
            {
              "admins": [
                "steve"
              ],
              "limits": {
                "homes": 3
              }
            }
        """.trimIndent()
        assertEquals(3, lineOf(JsonFormat(), text, "admins[0]"))
        assertEquals(2, lineOf(JsonFormat(), text, "admins[1]")) // au mieux la ligne de admins, pas la 6, la première clé de limits
    }

    @Test
    fun `un index écrit à plusieurs par ligne rend la ligne de son tableau, pas celle d'un élément du tableau suivant`() {
        val text = """
            {
              "tags": [
                "a", "b",
                "c"
              ],
              "others": [
                "x",
                "y",
                "z"
              ]
            }
        """.trimIndent()
        assertEquals(3, lineOf(JsonFormat(), text, "tags[0]"))
        assertEquals(2, lineOf(JsonFormat(), text, "tags[2]")) // le localisateur compte un élément par ligne, tags en a deux pour lui : au mieux la ligne de tags, pas la 7, le "x" de others
    }

    @Test
    fun `un objet ouvert sur la ligne de sa clé de tableau rend cette ligne, sans envoyer la recherche dans le conteneur suivant`() {
        val text = """
            {
              "items": [{
                "a": 1
              }],
              "others": [
                {
                  "a": 2
                }
              ]
            }
        """.trimIndent()
        assertEquals(7, lineOf(JsonFormat(), text, "others[0].a"))
        assertEquals(2, lineOf(JsonFormat(), text, "items[0].a")) // l'objet s'ouvre sur la ligne de items, où le localisateur ne le compte pas : au mieux cette ligne, pas la 7, le a de others
    }

    @Test
    fun `une clé absente rend la ligne de son objet, pas celle de l'objet rouvert sur la ligne qui referme le sien`() {
        val text = """
            {
              "items": [
                {
                  "a": 1
                }, {
                  "a": 2,
                  "b": 3
                }
              ]
            }
        """.trimIndent()
        assertEquals(4, lineOf(JsonFormat(), text, "items[0].a"))
        assertEquals(3, lineOf(JsonFormat(), text, "items[0].b")) // la ligne 5 referme le premier objet et ouvre le second sans que la profondeur finale bouge : au mieux la ligne du premier, pas la 7, le b du second
    }

    @Test
    fun `une valeur en ligne rend sa ligne, même quand cette ligne referme le parent`() {
        val text = """
            {
              "a": {
                "last": {"y": 1} },
              "b": {
                "c": {
                  "x": 5
                }
              }
            }
        """.trimIndent()
        assertEquals(3, lineOf(JsonFormat(), text, "a.last.x")) // la profondeur finit sous celle de la clé : au mieux la ligne de last, pas la 6, le x de b.c
    }

    @Test
    fun `un chemin dont rien ne se retrouve reste sans ligne`() {
        val text = "{\n  \"name\": \"x\"\n}"
        assertNull(lineOf(JsonFormat(), text, "missing"))
        assertNull(lineOf(JsonFormat(), text, "missing.deep[0]"))
        assertNull(lineOf(JsonFormat(), "{\"name\": \"x\"}", "name")) // un fichier écrit sur une seule ligne : aucune clé en tête de ligne
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
    fun `JSON5, une clé absente de son objet rend la ligne de l'objet, pas celle de la même clé dans un autre`() {
        val text = """
            {
              // les jeux de règles
              rules: {
                vip: {
                  'quota': {
                    period: 60, // sans uses
                  },
                },
                /* le jeu
                   du staff */
                staff: {
                  quota: {
                    uses: 9,
                    period: 60,
                  },
                },
              },
            }
        """.trimIndent()
        assertEquals(13, lineOf(Json5Format(), text, "rules[staff].quota.uses", name = "g.json5"))
        assertEquals(5, lineOf(Json5Format(), text, "rules[vip].quota", name = "g.json5"))
        assertEquals(5, lineOf(Json5Format(), text, "rules[vip].quota.uses", name = "g.json5")) // au mieux la ligne du quota de vip, pas la 13, le uses de staff
    }

    @Test
    fun `JSON5, une clé de map absente rend la ligne de la map, pas celle de la même clé dans une autre`() {
        val text = """
            {
              homes: {
                base: '0 64 0',
              },
              // les warps
              warps: {
                'spawn': '8 70 8',
              },
            }
        """.trimIndent()
        assertEquals(7, lineOf(Json5Format(), text, "warps[spawn]", name = "g.json5"))
        assertEquals(2, lineOf(Json5Format(), text, "homes[spawn]", name = "g.json5")) // au mieux la ligne de homes, pas la 7, le spawn de warps
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
