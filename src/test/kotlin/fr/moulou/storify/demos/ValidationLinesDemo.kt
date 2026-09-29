// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validateFile
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationErrorEnricher
import fr.moulou.storify.validation.ValidationResult
import fr.moulou.storify.validation.Validator
import fr.moulou.storify.validation.evaluate
import kotlinx.serialization.Serializable
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import kotlin.io.path.writeText

/*
 * La démo de C-32 : le rapport de validation et ses numéros de ligne. Un validator accumule des erreurs avec un chemin (`players[steve].joinCount`)
 * et l'enrichisseur retrouve, dans le fichier, la ligne de chaque chemin, pour qu'un admin aille corriger directement. Les quatre situations :
 *   1. une erreur sur une clé de la racine : la ligne est trouvée (ça marchait déjà) ;
 *   2. des chemins qui traversent une map (`players[steve]`) : avant C-32, tout le lot perdait ses lignes ; depuis, chacune a la sienne ;
 *   3. un fichier JSON5 (clés nues, commentaires) : avant, aucune ligne ; depuis, le localisateur de la famille JSON les lit ;
 *   4. valider un fichier édité à la main sans ouvrir de store : `format.validateFile(path, validator)`.
 */

@Serializable
data class Member(var joinCount: Int = 0, var homes: MutableList<String> = mutableListOf())

@Serializable
data class Guild(var name: String = "guilde", var players: MutableMap<String, Member> = mutableMapOf())

/** Le validator type d'un mod : les chemins traversent la map des joueurs, comme celui du banc. */
class GuildValidator : Validator<Guild> {
    override fun validate(data: Guild, ctx: ValidationContext) {
        ctx.check(data.name.isNotBlank(), "name", "must not be blank", data.name)
        data.players.forEach { (id, member) ->
            ctx.check(member.joinCount >= 0, "players[$id].joinCount", "must not be negative", member.joinCount)
            member.homes.forEachIndexed { index, home -> ctx.check(home.isNotBlank(), "players[$id].homes[$index]", "must not be blank") }
        }
    }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class ValidationLinesDemoTest {

    private val jsonText = """
        {
          "name": "",
          "players": {
            "steve": {
              "joinCount": -1,
              "homes": ["base", ""]
            }
          }
        }
    """.trimIndent()

    private fun report(data: Guild, format: StoreFormat, path: Path): String {
        val result = GuildValidator().evaluate(data) as ValidationResult.Failure
        return ValidationErrorEnricher.enrich(format, path, result.errors).joinToString("\n") { "    " + it.formatFull() }
    }

    @Test
    fun `étape 1, une erreur sur une clé de la racine reçoit sa ligne`() {
        val path = newStorePath("guild.json").also { it.writeText(jsonText) }
        println(report(Guild(name = ""), JsonFormat(), path)) // aucune erreur dans la map : le lot ne contient que l'erreur racine
    }

    @Test
    fun `étape 2, depuis C-32, chaque chemin qui traverse une map a sa ligne, le tableau en ligne celle de sa clé`() {
        val path = newStorePath("guild.json").also { it.writeText(jsonText) }
        val decoded = JsonFormat().decodeFromPath(Guild.serializer(), path) // les trois erreurs : name, joinCount, homes[1]
        println(report(decoded, JsonFormat(), path))
    }

    @Test
    fun `étape 3, depuis C-32, un fichier JSON5 reçoit ses lignes, clés nues et commentaires compris`() {
        val path = newStorePath("guild.json5").also {
            it.writeText("{\n  // la guilde\n  name: '',\n  players: {\n    steve: {\n      joinCount: -1,\n      homes: ['base', ''],\n    },\n  },\n}")
        }
        val decoded = Json5Format().decodeFromPath(Guild.serializer(), path)
        println(report(decoded, Json5Format(), path))
    }

    @Test
    fun `étape 4, depuis C-32, un fichier se valide sans store`() {
        val path = newStorePath("guild.json").also { it.writeText(jsonText) }
        val result = JsonFormat().validateFile(path, GuildValidator())
        println("    " + (result as ValidationResult.Failure).formatFull().lines().joinToString("\n    "))
    }
}
