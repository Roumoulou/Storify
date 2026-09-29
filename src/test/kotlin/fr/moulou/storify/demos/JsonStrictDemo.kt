// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-36 : ce que le lecteur JSON tolérant accepte, et ce que le lecteur strict en dit. Le fil :
 *
 *   1. huit fichiers `config.json` ouverts par un store au lecteur tolérant, le défaut d'avant C-36 (`JsonFormat.lenient()`) : le JSON standard,
 *      un commentaire, des clés et une chaîne sans guillemets, un booléen et un nombre entre guillemets, `NaN`, une virgule finale, une clé inconnue ;
 *   2. les mêmes fichiers par le lecteur strict, le défaut depuis C-36 (`JsonFormat()`) : ce qui cesse de charger arrive en StoreDecodeException,
 *      avec la ligne ;
 *   3. le fichier à commentaire et clés nues en `.json5` : c'est le format fait pour lui ;
 *   4. le point à trancher, `allowSpecialFloatingPointValues` : un `NaN` en mémoire, sauvegardé par un lecteur qui le tolère puis par un qui
 *      le refuse ;
 *   5. `allowStructuredMapKeys` n'est pas une tolérance de syntaxe : une map à clés textuelles écrite en tableau est refusée dans les deux cas.
 *
 * Le lecteur tolérant : Json { prettyPrint, isLenient, encodeDefaults, allowStructuredMapKeys, allowSpecialFloatingPointValues, allowComments }.
 * Le strict : les mêmes sans isLenient ni allowComments ; allowSpecialFloatingPointValues gardé, l'étape 4 montre l'autre choix.
 */

/** Un réglage avec un booléen et un nombre à virgule, pour les variantes que PlainData ne peut pas porter. */
@Serializable
data class Tuning(var enabled: Boolean = true, var ratio: Double = 1.0)

/** Une map à clés textuelles, pour l'étape 5. */
@Serializable
data class Scores(var scores: MutableMap<String, Int> = mutableMapOf())

@TestMethodOrder(MethodOrderer.DisplayName::class)
class JsonStrictDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    /** Le lecteur tolérant, le défaut d'avant C-36 : isLenient et allowComments. */
    private val tolerant = JsonFormat.lenient()

    /** Le lecteur strict, le défaut depuis C-36 : ni isLenient ni allowComments, NaN toléré. */
    private val strict = JsonFormat()

    /** Le même, NaN refusé : le strict de kotlinx, et celui qu'AegisPerms se construit. */
    private val strictest = JsonFormat(Json { prettyPrint = true; encodeDefaults = true; allowStructuredMapKeys = true })

    private class Variant(val label: String, val text: String, val open: (Path, StoreFormat) -> String)

    private fun plain(label: String, text: String) = Variant(label, text) { path, format ->
        StoreFactory.create<PlainData>(path.toString(), format = format, config = noAutoSave).use { "name=${it.data.name}, count=${it.data.count}" }
    }

    private fun tuning(label: String, text: String) = Variant(label, text) { path, format ->
        StoreFactory.createFromConstructor<Tuning>(path.toString(), format = format, config = noAutoSave).use { "enabled=${it.data.enabled}, ratio=${it.data.ratio}" }
    }

    private val variants = listOf(
        plain("JSON standard", "{\n  \"name\": \"steve\",\n  \"count\": 1,\n  \"tags\": []\n}"),
        plain("un commentaire", "{\n  \"name\": \"steve\",\n  // le compteur\n  \"count\": 1,\n  \"tags\": []\n}"),
        plain("clés et chaîne sans guillemets", "{\n  name: steve,\n  count: 1,\n  tags: []\n}"),
        tuning("un booléen entre guillemets", "{\n  \"enabled\": \"true\",\n  \"ratio\": 1.0\n}"),
        plain("un nombre entre guillemets", "{\n  \"name\": \"steve\",\n  \"count\": \"1\",\n  \"tags\": []\n}"),
        tuning("NaN", "{\n  \"enabled\": true,\n  \"ratio\": NaN\n}"),
        plain("une virgule finale", "{\n  \"name\": \"steve\",\n  \"count\": 1,\n  \"tags\": [],\n}"),
        plain("une clé inconnue", "{\n  \"name\": \"steve\",\n  \"colour\": \"red\",\n  \"count\": 1,\n  \"tags\": []\n}"),
    )

    private fun outcome(attempt: () -> String): String = runCatching(attempt).fold({ "OK ($it)" }) { e ->
        val decode = e as? StoreDecodeException
        val where = decode?.line?.let { " at line $it" } ?: ""
        "${e::class.simpleName}$where: ${(decode?.cause ?: e).message?.lineSequence()?.first()?.take(100)}"
    }

    private fun openAll(format: StoreFormat): Map<String, String> = variants.associate { variant ->
        val path = newStorePath("config.json").also { it.writeText(variant.text) }
        variant.label to outcome { variant.open(path, format) }
    }

    private fun show(results: Map<String, String>) = results.forEach { (label, result) -> println("    %-32s %s".format(label, result)) }

    private fun oneLine(path: Path) = path.readText().replace(Regex("\\s+"), " ")

    @Test
    fun `étape 1, ce que le lecteur tolérant accepte, le défaut d'avant C-36, JsonFormat lenient()`() {
        val results = openAll(tolerant)
        show(results)
        // Strict sur les clés, laxiste sur la syntaxe.
        check(results.getValue("un commentaire").startsWith("OK") && results.getValue("clés et chaîne sans guillemets").startsWith("OK"))
        check(!results.getValue("une clé inconnue").startsWith("OK"))
    }

    @Test
    fun `étape 2, les mêmes fichiers par le lecteur strict, le défaut depuis C-36, JsonFormat()`() {
        val results = openAll(strict)
        show(results)
        check(results.getValue("JSON standard").startsWith("OK"))
        check(!results.getValue("un commentaire").startsWith("OK") && !results.getValue("clés et chaîne sans guillemets").startsWith("OK"))
    }

    @Test
    fun `étape 3, le fichier à commentaire et clés nues charge en JSON5, le format fait pour lui`() {
        val path = newStorePath("config.json5").also { it.writeText("{\n  // le compteur\n  name: 'steve',\n  count: 1,\n  tags: [],\n}") }
        val result = outcome { StoreFactory.create<PlainData>(path.toString(), config = noAutoSave).use { "name=${it.data.name}, count=${it.data.count}" } }
        println("    config.json5, format résolu par l'extension : $result")
        check(result.startsWith("OK"))
    }

    @Test
    fun `étape 4, un NaN en mémoire, sauvegardé par un lecteur qui le tolère puis par un qui le refuse`() {
        saveNaN("allowSpecialFloatingPointValues = true  (le défaut)", strict)
        saveNaN("allowSpecialFloatingPointValues = false (le strict de kotlinx)", strictest)
    }

    private fun saveNaN(label: String, format: JsonFormat) {
        val path = newStorePath("tuning.json")
        val store = StoreFactory.createFromConstructor<Tuning>(path.toString(), format = format, config = StoreConfig(withAutoSave = false, withShutdownHook = false))
        println("    $label")
        println("        fichier initial        : ${oneLine(path)}")
        store.set(Tuning::ratio, Double.NaN)
        val saveFailure = runCatching { store.saveImmediate() }.exceptionOrNull()
        println("        saveImmediate()        : ${saveFailure?.let { "${it::class.simpleName}: ${it.message?.lineSequence()?.first()?.take(80)}" } ?: "OK"}")
        println("        fichier après          : ${oneLine(path)}")
        val closeFailure = runCatching { store.close() }.exceptionOrNull()
        println("        close()                : ${closeFailure?.let { "${it::class.simpleName}, la sauvegarde d'adieu échoue de même : rien de ce store ne se persiste tant que le NaN est là" } ?: "OK"}")
        if (saveFailure == null) {
            val reopened = StoreFactory.createFromConstructor<Tuning>(path.toString(), format = format, config = noAutoSave).use { it.data.ratio }
            println("        relu par un store neuf : ratio = $reopened")
        }
    }

    @Test
    fun `étape 5, allowStructuredMapKeys n'est pas une tolérance de syntaxe`() {
        val path = newStorePath("scores.json").also { it.writeText("{\n  \"scores\": [\"steve\", 1]\n}") }
        val withFlag = outcome { strict.decodeFromPath(Scores.serializer(), path).scores.toString() }
        val withoutFlag = outcome { JsonFormat(Json { allowStructuredMapKeys = false }).decodeFromPath(Scores.serializer(), path).scores.toString() }
        println("    une map à clés textuelles écrite en tableau, allowStructuredMapKeys = true  : $withFlag")
        println("    la même,                                    allowStructuredMapKeys = false : $withoutFlag")
        // Le réglage ne joue que pour une map à clés structurées (une data class en clé), qu'il permet d'écrire en tableau : une capacité, pas une tolérance.
        check(!withFlag.startsWith("OK") && !withoutFlag.startsWith("OK"))
    }
}
