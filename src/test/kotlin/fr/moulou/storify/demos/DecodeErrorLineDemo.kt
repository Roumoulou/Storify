// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.FarmConfig
import fr.moulou.storify.support.Mood
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validation.ErrorPath
import fr.moulou.storify.validation.JsonLineLocator
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationException
import fr.moulou.storify.validation.Validator
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-56 : la faute de frappe la plus courante d'une configuration, une valeur hors d'un domaine fermé (FURIOUS pour une énumération
 * CALM ou ANGRY), et ce que l'admin reçoit pour la retrouver.
 *
 * Avant C-56, StoreDecodeException.line ne se remplissait que depuis un offset, un index ou un (L<n>) lu dans le message du parseur (C-33),
 * et le message de kotlinx pour une énumération n'en porte pas : il nomme le chemin, « at path $.mood », et rien d'autre. La faute sortait
 * sans ligne, à la racine comme sous une clé de map, un fichier vide ou coupé après une valeur aussi ; seule la validation (C-32) renvoyait
 * l'admin à sa ligne. Depuis : quand le message nomme le chemin sans offset, la ligne vient du localisateur du format, le chemin s'expose en
 * valuePath, et une fin de fichier atteinte rend la dernière ligne du fichier.
 *
 *   1. la même faute à la racine, dans un objet imbriqué et sous une clé de map : la ligne 2, 5 et 7, et le chemin ;
 *   2. le contraste d'avant : ANGRY, admis par le décodeur mais refusé par un validator, avait déjà sa ligne (C-32) ; FURIOUS l'a aussi ;
 *   3. un fichier vide rend la ligne 1, un fichier coupé après une valeur sa dernière ligne, un fichier coupé au milieu d'une chaîne garde
 *      la ligne de son offset ;
 *   4. la même faute en JSON5 et en TOML : ni ligne ni chemin, parce que leur message ne dit pas où, limite assumée ;
 *   5. la pièce que C-56 a branchée : le localisateur de C-32 et C-53, qui retrouve la ligne du chemin que le message nomme.
 */

/** Le validator du contraste : ANGRY est une valeur légale de l'énumération, mais refusée ici. */
class CalmOnlyValidator : Validator<FarmConfig> {
    override fun validate(data: FarmConfig, ctx: ValidationContext) {
        ctx.check(data.mood == Mood.CALM, "mood", "must be CALM", data.mood)
    }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class DecodeErrorLineDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private fun open(path: Path) = StoreFactory.createFromConstructor<FarmConfig>(path.toString(), config = noAutoSave).close()

    /** Ouvre le store, attend une StoreDecodeException, imprime sa ligne, son chemin et la première ligne de son message. */
    private fun decodeFailure(label: String, path: Path): StoreDecodeException {
        val failure = runCatching { open(path) }.exceptionOrNull()
        check(failure is StoreDecodeException) { "$label : attendu StoreDecodeException, reçu $failure" }
        println("    $label")
        println("        line      : ${failure.line}")
        println("        valuePath : ${failure.valuePath}")
        println("        message   : ${failure.cause?.message?.lines()?.first()?.take(160)}")
        return failure
    }

    @Test
    fun `étape 1, une valeur d'énumération inconnue, le message nomme le chemin, l'exception porte sa ligne et son chemin`() {
        val root = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"FURIOUS\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {}\n}") }
        val nested = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\",\n  \"favorite\": {\n    \"name\": \"rex\",\n    \"mood\": \"FURIOUS\"\n  },\n  \"pets\": {}\n}") }
        val inMap = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {\n    \"rex\": {\n      \"name\": \"rex\",\n      \"mood\": \"FURIOUS\"\n    }\n  }\n}") }
        val lines = listOf("à la racine" to root, "dans un objet imbriqué" to nested, "sous une clé de map" to inMap).map { (label, path) -> decodeFailure(label, path).line }
        check(lines == listOf(2, 5, 7)) // avant C-56 : null, null, null
    }

    @Test
    fun `étape 2, le contraste d'avant, la même clé refusée par un validator avait déjà sa ligne`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"ANGRY\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {}\n}") }
        val failure = runCatching { StoreFactory.createFromConstructor<FarmConfig>(path.toString(), config = noAutoSave, validator = CalmOnlyValidator()).close() }.exceptionOrNull()
        check(failure is ValidationException)
        val error = failure.errors.single()
        println("    ANGRY, légal pour le décodeur, refusé par le validator : ${error.path}.${error.field}, ligne ${error.line} (C-32)")
        check(error.line == 2)
    }

    @Test
    fun `étape 3, un fichier vide ou coupé après une valeur rend la ligne où il s'arrête, coupé dans une chaîne il garde son offset`() {
        val empty = newStorePath("farm.json").also { it.writeText("") }
        val cutAfterValue = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\"\n") }
        val cutInString = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CA") }
        check(decodeFailure("fichier vide", empty).line == 1)                       // avant C-56 : null
        check(decodeFailure("coupé après une valeur", cutAfterValue).line == 3)     // avant C-56 : null
        check(decodeFailure("coupé au milieu d'une chaîne", cutInString).line == 2) // l'offset de kotlinx, comme avant
    }

    @Test
    fun `étape 4, la même faute en JSON5 et en TOML, ni ligne ni chemin`() {
        val json5 = newStorePath("farm.json5").also { it.writeText("{\n  mood: 'FURIOUS',\n  favorite: { name: 'rex', mood: 'CALM' },\n  pets: {}\n}") }
        val toml = newStorePath("farm.toml").also { it.writeText("mood = \"FURIOUS\"\n\n[favorite]\nname = \"rex\"\nmood = \"CALM\"\n\n[pets]\n") }
        val failures = listOf(decodeFailure("JSON5", json5), decodeFailure("TOML", toml))
        check(failures.all { it.line == null && it.valuePath.isEmpty() })
        check(failures.none { "at path" in it.cause?.message.orEmpty() })
    }

    @Test
    fun `étape 5, la pièce branchée, le localisateur retrouve la ligne du chemin que le message nomme`() {
        val path = newStorePath("farm.json").also { it.writeText("{\n  \"mood\": \"CALM\",\n  \"favorite\": { \"name\": \"rex\", \"mood\": \"CALM\" },\n  \"pets\": {\n    \"rex\": {\n      \"name\": \"rex\",\n      \"mood\": \"FURIOUS\"\n    }\n  }\n}") }
        val failure = decodeFailure("sous une clé de map", path)
        val rawPath = Regex("""at path:? \$\.?(\S*)""").find(failure.cause?.message.orEmpty())!!.groupValues[1] // ce que la lib lit dans le message
        val segments = ErrorPath.parse(rawPath)
        val line = JsonLineLocator.lineOf(path.readText().lines(), segments)
        println("        chemin lu dans le message : « $rawPath », soit $segments ; ligne par le localisateur : $line")
        check(segments == failure.valuePath && line == failure.line)
    }
}
