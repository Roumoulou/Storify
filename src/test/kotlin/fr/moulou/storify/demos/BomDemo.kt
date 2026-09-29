// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.core.set
import fr.moulou.storify.support.PlainData
import fr.moulou.storify.support.newStorePath
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.io.path.writeText

/*
 * La démo de C-31 : le BOM UTF-8, ce qu'il est (étape 1), et ce qu'en font les trois formats (étape 2) et un vrai store (étape 3).
 * Le BOM est le caractère U+FEFF posé en tête d'un fichier texte, invisible dans un éditeur, trois octets EF BB BF en UTF-8 : le Bloc-notes
 * l'écrit quand on enregistre en « UTF-8 avec BOM », Windows PowerShell 5.1 aussi, et bien des éditeurs le laissent en place.
 * Avant C-31, JsonFormat et TomlFormat refusaient un tel fichier (« Unexpected JSON token at offset 0 », « UnexpectedTokenException (L1) »),
 * seul JSON5 le tolérait, et un store ne s'ouvrait pas sur une config enregistrée par le Bloc-notes. Depuis : Storify lit avec ou sans BOM,
 * et écrit toujours sans.
 */

@TestMethodOrder(MethodOrderer.DisplayName::class)
class BomDemoTest {

    private val bom = "\uFEFF"

    private fun outcome(attempt: () -> Any?): String = runCatching(attempt).fold({ "OK $it" }, { "${it::class.simpleName}: ${it.message?.take(90)}" })

    private fun firstBytes(path: java.nio.file.Path) = Files.readAllBytes(path).take(3).joinToString(" ") { "%02X".format(it) }

    @Test
    fun `étape 1, un BOM, c'est trois octets invisibles en tête du fichier`() {
        val path = newStorePath("bom.json")
        path.writeText(bom + "{ \"name\": \"b\" }")

        val bytes = Files.readAllBytes(path)
        println("les 5 premiers octets : ${bytes.take(5).joinToString(" ") { "%02X".format(it) }}   (EF BB BF = le BOM, puis 7B = '{', 20 = espace)")
        println("le texte relu commence par U+${"%04X".format(path.readText().first().code)} : ${if (path.readText().startsWith(bom)) "le BOM est là, invisible" else "pas de BOM"}")
    }

    @Test
    fun `étape 2, depuis C-31, les trois formats décodent un fichier avec BOM`() {
        val json = newStorePath("bom.json").also { it.writeText(bom + "{\n  \"name\": \"b\",\n  \"count\": 1,\n  \"tags\": []\n}") }
        val toml = newStorePath("bom.toml").also { it.writeText(bom + "name = \"b\"\ncount = 1\ntags = []\n") }
        val json5 = newStorePath("bom.json5").also { it.writeText(bom + "{\n  name: 'b',\n  count: 1,\n  tags: [],\n}") }

        val results = listOf(
            "JsonFormat " to outcome { JsonFormat().decodeFromPath(PlainData.serializer(), json) },
            "TomlFormat " to outcome { TomlFormat().decodeFromPath(PlainData.serializer(), toml) },
            "Json5Format" to outcome { Json5Format().decodeFromPath(PlainData.serializer(), json5) },
        )
        results.forEach { (format, result) -> println("$format : $result") }
        check(results.all { it.second.startsWith("OK") })
    }

    @Test
    fun `étape 3, depuis C-31, un store s'ouvre sur une config enregistrée avec BOM, et la réécrit sans`() {
        val path = newStorePath("config.json")
        path.writeText(bom + "{\n  \"name\": \"enregistré par le Bloc-notes\",\n  \"count\": 1,\n  \"tags\": []\n}")
        println("avant : les 3 premiers octets sont ${firstBytes(path)}")

        StoreFactory.create<PlainData>(path.toString(), config = StoreConfig(withAutoSave = false)).use { store ->
            println("ouverture du store : OK, name = ${store.data.name}")
            store.set(PlainData::count, 2)
            store.saveImmediate()
        }
        println("après une sauvegarde : les 3 premiers octets sont ${firstBytes(path)}   (7B 0A 20 = '{', saut de ligne, espace : plus de BOM)")
        check(!path.readText().startsWith(bom))
    }
}