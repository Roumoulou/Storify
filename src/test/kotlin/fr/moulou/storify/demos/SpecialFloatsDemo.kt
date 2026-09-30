// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.Json5Format
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.newStorePath
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-42 : NaN et les infinis dans les trois formats. Un calcul qui tourne mal (une division par zéro, une moyenne sur zéro élément)
 * laisse un NaN ou un infini dans un Double de la racine. C-36 a tranché pour JsonFormat : ces valeurs s'écrivent et se relisent, parce qu'un
 * refus ferait échouer chaque sauvegarde du store, loin du code qui a produit la valeur. TOML les écrit aussi (nan, inf).
 *
 * Avant C-42, JSON5 était le seul à refuser : le pont Json de Json5Format n'avait pas allowSpecialFloatingPointValues, la sauvegarde levait une
 * JsonEncodingException (le fichier gardant l'ancienne valeur), et un fichier où NaN ou Infinity étaient écrits à la main, du JSON5 pourtant
 * valide, ne s'ouvrait pas (StoreDecodeException). Depuis : les trois formats tiennent la même promesse.
 *
 *   1. une valeur spéciale en mémoire, sauvegardée par un store de chaque format, puis relue par un store neuf ;
 *   2. la même valeur écrite à la main dans le fichier, comme le ferait un admin, puis ouverte par un store ;
 *   3. le pont d'avant C-42, reconstitué par le constructeur : un Json passé par le consommateur est pris tel quel, son refus compris.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class SpecialFloatsDemoTest {

    private val config = StoreConfig(withAutoSave = false, withShutdownHook = false)

    private val values = listOf("NaN" to Double.NaN, "+Infinity" to Double.POSITIVE_INFINITY, "-Infinity" to Double.NEGATIVE_INFINITY)

    private fun outcome(attempt: () -> String): String = runCatching(attempt).fold({ it }) { e -> "${e::class.simpleName}: ${(e.cause ?: e).message?.lineSequence()?.first()?.take(120)}" }

    private fun oneLine(path: Path) = path.readText().replace(Regex("\\s+"), " ").trim()

    private fun open(path: Path, format: StoreFormat? = null): String = outcome { StoreFactory.createFromConstructor<Tuning>(path.toString(), format = format, config = config).use { "OK, ratio = ${it.data.ratio}" } }

    /** Pose [value] dans un store neuf, sauvegarde, et rend ce que la sauvegarde puis un store neuf en disent. */
    private fun saveThenReopen(fileName: String, value: Double, format: StoreFormat? = null): Pair<String, String> {
        val path = newStorePath(fileName)
        val store = StoreFactory.createFromConstructor<Tuning>(path.toString(), format = format, config = config)
        store.set(Tuning::ratio, value)
        val saved = outcome { store.saveImmediate(); "OK, fichier : ${oneLine(path)}" }
        runCatching { store.close() } // si la sauvegarde a échoué, la sauvegarde d'adieu échoue de même
        return saved to open(path, format)
    }

    @Test
    fun `étape 1, depuis C-42, une valeur spéciale en mémoire se sauve et se relit dans les trois formats`() {
        for (fileName in listOf("tuning.json", "tuning.json5", "tuning.toml")) {
            println("    $fileName")
            for ((label, value) in values) {
                val (saved, reopened) = saveThenReopen(fileName, value)
                println("        %-9s  saveImmediate()        : %s".format(label, saved))
                println("        %-9s  relu par un store neuf : %s".format(label, reopened))
                check(saved.startsWith("OK") && reopened == "OK, ratio = $value")
            }
        }
    }

    @Test
    fun `étape 2, depuis C-42, la même valeur écrite à la main s'ouvre dans les trois formats`() {
        val handWritten = listOf(
            "tuning.json" to listOf("NaN", "Infinity", "-Infinity").map { it to "{\n  \"enabled\": true,\n  \"ratio\": $it\n}" },
            "tuning.json5" to listOf("NaN", "Infinity", "+Infinity", "-Infinity").map { it to "{\n  enabled: true,\n  ratio: $it,\n}" },
            "tuning.toml" to listOf("nan", "inf", "-inf").map { it to "enabled = true\nratio = $it\n" },
        )
        for ((fileName, variants) in handWritten) {
            println("    $fileName")
            for ((literal, text) in variants) {
                val path = newStorePath(fileName).also { it.writeText(text) }
                val opened = open(path)
                println("        ratio écrit %-9s : %s".format(literal, opened))
                check(opened.startsWith("OK"))
            }
        }
    }

    @Test
    fun `étape 3, le pont d'avant C-42, reconstitué, un Json passé par le consommateur est pris tel quel`() {
        val strictBridge = Json5Format(Json { encodeDefaults = true }) // le défaut d'avant C-42 : sans allowSpecialFloatingPointValues
        for ((label, value) in values) {
            val (saved, reopened) = saveThenReopen("tuning.json5", value, strictBridge)
            println("        %-9s  saveImmediate()        : %s".format(label, saved))
            println("        %-9s  relu par un store neuf : %s   (l'ancienne valeur : la modification est perdue)".format(label, reopened))
            check(saved.startsWith("JsonEncodingException") && reopened == "OK, ratio = 1.0")
        }
    }
}
