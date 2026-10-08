// SPDX-FileCopyrightText: 2025-2026 Roumoulou
// SPDX-License-Identifier: LGPL-3.0-only

package fr.moulou.storify.demos

import fr.moulou.storify.Json5Format
import fr.moulou.storify.JsonFormat
import fr.moulou.storify.StoreDecodeException
import fr.moulou.storify.StoreFormat
import fr.moulou.storify.TomlFormat
import fr.moulou.storify.core.StoreConfig
import fr.moulou.storify.core.StoreFactory
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validateFile
import fr.moulou.storify.validation.ErrorPath
import fr.moulou.storify.validation.JsonDuplicateKeys
import fr.moulou.storify.validation.PathSegment
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.Validator
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import li.songe.json5.Json5
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

/*
 * La démo de C-50 : les clés en double. Un objet JSON qui porte deux fois la même clé était accepté : kotlinx garde la dernière valeur, à
 * l'arbre (`JsonTreeReader.readObjectImpl`, `result[key] = element`) comme au flux, et ni `JsonFormat` ni la validation ne le voyaient ;
 * li.songe:json5 fait pareil, et sa sauvegarde préservante gardait le doublon dans le fichier, que chaque relecture tranchait à nouveau ;
 * seul tomlkt refusait, par sa spécification. Pour un fichier de vérité édité à la main (AegisPerms, `groups` avec `vip` déclaré deux fois),
 * un doublon effaçait une définition en silence, avant le code du consommateur. Depuis C-50, le lecteur JSON strict et le format JSON5
 * refusent le doublon par StoreDecodeException, avec la ligne de la seconde occurrence ; le lecteur tolérant (`JsonFormat.lenient()`) reste
 * ce qu'il était, et montre ici l'avant. Depuis C-55, la faute porte aussi le chemin du doublon, `valuePath`, la clé en dernier segment, que
 * son message nomme (« Duplicate key 'vip' at groups.vip ») ; en TOML, tomlkt ne le dit que dans son message, et `valuePath` reste vide.
 *
 *   1. JSON : un doublon à la racine, dans un objet imbriqué, dans une map, par le lecteur tolérant (l'avant : le dernier gagne, sans un
 *      mot, l'arbre comme le flux) puis par le strict (depuis C-50 : refusé, avec la ligne, et le chemin depuis C-55) ; l'élément répété d'un
 *      tableau décodé en Set reste un seul élément, ce n'est pas une clé et il reste visible du validator par une List ;
 *   2. les mêmes fichiers en JSON5 et en TOML : refusés avec la ligne, le second par tomlkt depuis toujours, le chemin en JSON5 seulement ;
 *   3. au rechargement, un fichier édité avec un doublon est refusé et la mémoire reste intacte, dans les trois formats ;
 *   4. validateFile refuse de même, avec et sans store ;
 *   5. le coût, mesuré sur 5 000 joueurs (794 Ko) : le chargement strict contre le tolérant, la passe seule, le document JSON5 ; et les
 *      pièges du scanner, une clé échappée, deux clés sur une ligne, la même clé dans deux objets, un faux doublon dans une chaîne, un
 *      doublon sous un élément de liste, chacun avec son chemin.
 */

/** Un groupe de permissions, à la façon d'AegisPerms. */
@Serializable
data class PermGroup(var priority: Int = 0, var prefix: String = "")

/** Le fichier de vérité : une valeur à la racine, un objet imbriqué, une map à clé naturelle, un tableau décodé en Set. */
@Serializable
data class PermsFile(
    var version: Int = 1,
    var defaults: PermGroup = PermGroup(),
    var groups: MutableMap<String, PermGroup> = mutableMapOf(),
    var admins: MutableSet<String> = mutableSetOf(),
)

/** Le validator type d'un consommateur : il voit les groupes décodés, jamais le texte. */
class PermsFileValidator : Validator<PermsFile> {
    override fun validate(data: PermsFile, ctx: ValidationContext) {
        ctx.check(data.version >= 1, "version", "must be at least 1", data.version)
        data.groups.forEach { (name, group) ->
            ctx.check(name.isNotBlank(), "groups[$name]", "group name must not be blank")
            ctx.check(group.priority >= 0, "groups[$name].priority", "must not be negative", group.priority)
        }
    }
}

@TestMethodOrder(MethodOrderer.DisplayName::class)
class DuplicateKeysDemoTest {

    private val noAutoSave = StoreConfig(withAutoSave = false)

    private class Variant(val label: String, val json: String, val json5: String, val toml: String)

    private val variants = listOf(
        Variant(
            "à la racine",
            """
            {
              "version": 1,
              "version": 2,
              "defaults": { "priority": 0, "prefix": "" },
              "groups": {},
              "admins": []
            }
            """.trimIndent(),
            """
            {
              // la version du schéma
              version: 1,
              version: 2,
              defaults: { priority: 0, prefix: '' },
              groups: {},
              admins: [],
            }
            """.trimIndent(),
            """
            version = 1
            version = 2

            [defaults]
            priority = 0
            prefix = ""
            """.trimIndent(),
        ),
        Variant(
            "dans un objet imbriqué",
            """
            {
              "version": 1,
              "defaults": {
                "priority": 1,
                "priority": 2,
                "prefix": ""
              },
              "groups": {},
              "admins": []
            }
            """.trimIndent(),
            """
            {
              version: 1,
              defaults: {
                priority: 1,
                priority: 2,
                prefix: '',
              },
              groups: {},
              admins: [],
            }
            """.trimIndent(),
            """
            version = 1

            [defaults]
            priority = 1
            priority = 2
            prefix = ""
            """.trimIndent(),
        ),
        Variant(
            "dans une map",
            """
            {
              "version": 1,
              "defaults": { "priority": 0, "prefix": "" },
              "groups": {
                "vip": { "priority": 10, "prefix": "[VIP]" },
                "vip": { "priority": 20, "prefix": "[vip bis]" }
              },
              "admins": []
            }
            """.trimIndent(),
            """
            {
              version: 1,
              defaults: { priority: 0, prefix: '' },
              groups: {
                vip: { priority: 10, prefix: '[VIP]' },
                vip: { priority: 20, prefix: '[vip bis]' },
              },
              admins: [],
            }
            """.trimIndent(),
            """
            version = 1

            [groups.vip]
            priority = 10
            prefix = "[VIP]"

            [groups.vip]
            priority = 20
            prefix = "[vip bis]"
            """.trimIndent(),
        ),
        Variant(
            "un élément répété, tableau en Set",
            """
            {
              "version": 1,
              "defaults": { "priority": 0, "prefix": "" },
              "groups": {},
              "admins": ["steve", "steve"]
            }
            """.trimIndent(),
            """
            {
              version: 1,
              defaults: { priority: 0, prefix: '' },
              groups: {},
              admins: ['steve', 'steve'],
            }
            """.trimIndent(),
            """
            version = 1
            admins = ["steve", "steve"]
            """.trimIndent(),
        ),
    )

    private val root get() = variants[0]
    private val nested get() = variants[1]
    private val map get() = variants[2]
    private val set get() = variants[3]

    private fun describe(p: PermsFile) =
        "version=${p.version}, defaults.priority=${p.defaults.priority}, groups=[${p.groups.entries.joinToString { "${it.key}->${it.value.priority}" }}], admins=${p.admins}"

    private fun outcome(attempt: () -> String): String = runCatching(attempt).fold({ "OK ($it)" }) { e ->
        val decode = e as? StoreDecodeException
        val where = decode?.line?.let { " at line $it" } ?: ""
        val path = decode?.valuePath?.takeIf { it.isNotEmpty() }?.let { ", path ${ErrorPath.render(it)}" } ?: ""
        "${e::class.simpleName}$where$path: ${(decode?.cause ?: e).message?.lineSequence()?.first()?.take(110)}"
    }

    private fun open(text: String, fileName: String, format: StoreFormat): String {
        val path = newStorePath(fileName).also { it.writeText(text) }
        return StoreFactory.createFromConstructor<PermsFile>(path.toString(), format = format, config = noAutoSave).use { describe(it.data) }
    }

    private fun openAll(format: StoreFormat, fileName: String, text: (Variant) -> String): Map<String, String> =
        variants.associate { variant -> variant.label to outcome { open(text(variant), fileName, format) } }

    private fun show(results: Map<String, String>, indent: String = "    ") = results.forEach { (label, result) -> println("$indent%-36s %s".format(label, result)) }

    /** Refusé à cette ligne, et à ce chemin quand le format le donne (JSON et JSON5 ; TOML ne le dit que dans son message). */
    private fun refused(result: String, line: Int, path: String? = null) = result.startsWith("StoreDecodeException at line $line${path?.let { ", path $it" } ?: ""}:")

    /** Ce que le logger de test écrit sur System.out pendant [block], ligne par ligne. */
    private fun captureLog(block: () -> Unit): List<String> {
        val buffer = ByteArrayOutputStream()
        val original = System.out
        System.setOut(PrintStream(buffer, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(original)
        }
        return buffer.toString(Charsets.UTF_8).lines().filter { it.isNotBlank() }
    }

    @Test
    fun `étape 1, JSON, l'avant par le lecteur tolérant, le dernier gagne sans un mot, et depuis C-50 le strict refuse avec la ligne`() {
        var tolerant = emptyMap<String, String>()
        val log = captureLog { tolerant = openAll(JsonFormat.lenient(), "perms.json") { it.json } }
        println("    avant C-50, et toujours par JsonFormat.lenient() : le dernier gagne, ${log.count { "WARN" in it }} ligne WARN au log")
        show(tolerant, "        ")
        val tree = Json.parseToJsonElement(root.json).jsonObject.getValue("version").jsonPrimitive.content
        println("        le même doublon à la racine par l'arbre (Json.parseToJsonElement) : version = $tree")
        val strict = openAll(JsonFormat(), "perms.json") { it.json }
        println("    depuis C-50, par JsonFormat() : refusé, avec la ligne de la seconde occurrence, et son chemin depuis C-55")
        show(strict, "        ")
        check(tolerant.values.all { it.startsWith("OK") } && log.none { "WARN" in it } && tree == "2")
        check("version=2" in tolerant.getValue(root.label) && "defaults.priority=2" in tolerant.getValue(nested.label) && "groups=[vip->20]" in tolerant.getValue(map.label))
        check(refused(strict.getValue(root.label), 3, "version") && refused(strict.getValue(nested.label), 5, "defaults.priority") && refused(strict.getValue(map.label), 6, "groups.vip"))
        check(strict.getValue(set.label).startsWith("OK") && "admins=[steve]" in strict.getValue(set.label))
    }

    @Test
    fun `étape 2, les mêmes doublons en JSON5, refusés depuis C-50 par l'AST de la brique, et en TOML, refusés par tomlkt depuis toujours`() {
        val json5 = openAll(Json5Format(), "perms.json5") { it.json5 }
        val toml = openAll(TomlFormat(), "perms.toml") { it.toml }
        println("    Json5Format")
        show(json5, "        ")
        println("    TomlFormat")
        show(toml, "        ")
        check(refused(json5.getValue(root.label), 4, "version") && refused(json5.getValue(nested.label), 5, "defaults.priority") && refused(json5.getValue(map.label), 6, "groups.vip"))
        check(refused(toml.getValue(root.label), 2) && refused(toml.getValue(nested.label), 5) && refused(toml.getValue(map.label), 7))
        check(json5.getValue(set.label).startsWith("OK") && toml.getValue(set.label).startsWith("OK"))
    }

    @Test
    fun `étape 3, au rechargement, le fichier édité avec un doublon est refusé et la mémoire reste intacte, dans les trois formats`() {
        val cases = listOf(Triple(JsonFormat(), "perms.json", map.json to 6), Triple(Json5Format(), "perms.json5", map.json5 to 6), Triple(TomlFormat(), "perms.toml", map.toml to 7))
        for ((format, fileName, edited) in cases) {
            val (text, line) = edited
            val valuePath = "groups.vip".takeUnless { format is TomlFormat } // tomlkt ne nomme le chemin que dans son message
            val path = newStorePath(fileName)
            StoreFactory.createFromConstructor<PermsFile>(path.toString(), format = format, config = noAutoSave).use { store ->
                path.writeText(text) // l'admin édite le fichier : « vip » déclaré deux fois
                val result = outcome { store.reloadFromFile(); "rechargé" }
                println("    %-12s %s ; en mémoire : %s".format(format::class.simpleName, result, describe(store.data)))
                check(refused(result, line, valuePath) && "groups=[]" in describe(store.data) && !store.isDirty)
            }
        }
    }

    @Test
    fun `étape 4, validateFile refuse le doublon de même, avec et sans store`() {
        val jsonPath = newStorePath("perms.json").also { it.writeText(map.json) }
        val withoutStore = outcome { JsonFormat().validateFile(jsonPath, PermsFileValidator()).toString() }
        println("    validateFile sans store, JSON            : $withoutStore")
        val json5Path = newStorePath("perms.json5").also { it.writeText(map.json5) }
        val withoutStore5 = outcome { Json5Format().validateFile(json5Path, PermsFileValidator()).toString() }
        println("    validateFile sans store, JSON5           : $withoutStore5")
        val storePath = newStorePath("perms.json")
        val withStore = StoreFactory.createFromConstructor<PermsFile>(storePath.toString(), config = noAutoSave, validator = PermsFileValidator()).use { store ->
            storePath.writeText(map.json)
            outcome { store.validateFile().toString() }.also { println("    store.validateFile(), la mémoire intacte  : $it ; en mémoire : ${describe(store.data)}") }
        }
        check(refused(withoutStore, 6, "groups.vip") && refused(withoutStore5, 6, "groups.vip") && refused(withStore, 6, "groups.vip"))
    }

    @Test
    fun `étape 5, le coût de la passe, sur 5 000 joueurs, et les pièges du scanner`() {
        val strict = JsonFormat()
        val tolerant = JsonFormat.lenient()
        val path = newStorePath("roster.json")
        strict.encodeToPath(Roster.serializer(), Roster((1..5_000).map { Player("player$it", mutableListOf("base", "mine", "farm")) }.toMutableList()), path)
        val text = path.readText()
        val runs = 20
        fun ms(block: () -> Unit): Double { repeat(5) { block() }; return measureNanoTime { repeat(runs) { block() } } / runs / 1e6 }
        println("    le fichier : ${text.length / 1024} Ko, ${text.lines().size} lignes")
        println("    le chargement tolérant, par le flux (l'avant)      : %5.2f ms".format(ms { tolerant.decodeFromPath(Roster.serializer(), path) }))
        println("    le chargement strict, le texte décodé puis scanné  : %5.2f ms".format(ms { strict.decodeFromPath(Roster.serializer(), path) }))
        println("    la passe seule, sur le texte en mémoire            : %5.2f ms".format(ms { JsonDuplicateKeys.firstDuplicate(text) }))
        println("    Json5.parseToJsonElement, l'avant de Json5Format   : %5.2f ms".format(ms { Json5.parseToJsonElement(text) }))
        println("    Json5.parseToDocument, l'AST aux doublons, depuis  : %5.2f ms".format(ms { Json5.parseToDocument(text) }))

        val escaped = """{"ab": 1, "a\u0062": 2}""" // une chaîne brute : le \u0062 reste dans le texte, c'est le scanner qui le décode
        val oneLine = """{"a": 1, "a": 2}"""
        val siblings = """{"a": {"x": 1}, "b": {"x": 2}}"""
        val elements = """[{"a": 1}, {"a": 2}]"""
        val inString = """{"a": "x: 1, \"a\": 2", "b": 1}"""
        val inList = """[{"a": 1}, {"a": 1, "a": 2}]"""
        val cases = listOf(
            "une clé échappée, le même nom une fois décodé" to escaped,
            "deux clés sur une seule ligne" to oneLine,
            "la même clé dans deux objets" to siblings,
            "la même clé dans deux éléments d'un tableau" to elements,
            "un faux doublon dans une chaîne" to inString,
            "un doublon sous un élément de liste, à son rang" to inList,
            "le doublon de la map, à sa ligne et son chemin" to map.json,
        )
        for ((label, json) in cases) {
            val found = JsonDuplicateKeys.firstDuplicate(json)?.let { "doublon « ${it.key} » à la ligne ${it.line}, chemin ${ErrorPath.render(it.path)}" } ?: "aucun doublon"
            println("    %-48s %s".format(label, found))
        }
        check(JsonDuplicateKeys.firstDuplicate(escaped) == JsonDuplicateKeys.Duplicate("ab", 1, listOf(PathSegment.Key("ab"))) && JsonDuplicateKeys.firstDuplicate(oneLine) == JsonDuplicateKeys.Duplicate("a", 1, listOf(PathSegment.Key("a"))))
        check(listOf(siblings, elements, inString).all { JsonDuplicateKeys.firstDuplicate(it) == null })
        check(JsonDuplicateKeys.firstDuplicate(inList) == JsonDuplicateKeys.Duplicate("a", 1, ErrorPath.parse("[1].a")))
        check(JsonDuplicateKeys.firstDuplicate(map.json) == JsonDuplicateKeys.Duplicate("vip", 6, ErrorPath.parse("groups.vip")) && JsonDuplicateKeys.firstDuplicate(text) == null)
    }
}
