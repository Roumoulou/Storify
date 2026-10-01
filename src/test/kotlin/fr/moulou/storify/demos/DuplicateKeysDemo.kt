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
import fr.moulou.storify.core.set
import fr.moulou.storify.support.newStorePath
import fr.moulou.storify.validateFile
import fr.moulou.storify.validation.ValidationContext
import fr.moulou.storify.validation.ValidationResult
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
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.system.measureNanoTime

/*
 * La démo de C-50 : les clés en double, la perte silencieuse mesurée avant tout design. Un objet JSON qui porte deux fois la même clé est
 * accepté par kotlinx, qui garde la dernière valeur, à l'arbre (`JsonTreeReader.readObjectImpl`, `result[key] = element`) comme au flux, et
 * ni `JsonFormat` ni la validation ne le voient. Pour un fichier de vérité édité à la main (AegisPerms, `groups` avec `vip` déclaré deux
 * fois), un doublon efface une définition sans un mot, et la sauvegarde suivante réécrit le fichier avec une seule clé.
 *
 *   1. JSON, par le flux que JsonFormat emploie depuis C-48 : un doublon à la racine, dans un objet imbriqué, dans une map, et un élément
 *      répété dans un tableau décodé en Set ; le dernier gagne, pas un avertissement au log, et l'arbre fait pareil ;
 *   2. les mêmes fichiers en JSON5, où li.songe:json5 garde aussi le dernier, et en TOML, où tomlkt refuse chaque doublon d'objet avec la
 *      ligne de la seconde occurrence, parce que sa spécification les interdit ;
 *   3. au rechargement, un fichier édité avec un doublon remplace la mémoire sans un mot (JSON, JSON5) ; TOML refuse, mémoire intacte ;
 *   4. validateFile ne voit rien, et la sauvegarde d'après : JSON réécrit une seule clé, la sauvegarde préservante de JSON5 garde le
 *      doublon dans le fichier, que chaque relecture tranche à nouveau en silence ;
 *   5. le coût d'une passe sur le texte, un prototype mesuré sur 5 000 joueurs (794 Ko), du même ordre que le décodage lui-même, et ses
 *      pièges : une clé échappée, deux clés sur une ligne, la même clé dans deux objets, un faux doublon dans une chaîne.
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

/**
 * Un prototype de passe sur le texte, JSON seulement : les objets ouverts en pile, les clés de chacun dans un ensemble, les chaînes
 * décodées de leurs échappements avant comparaison, la ligne comptée au passage. Un tableau ouvre une portée sans clés. Rend le premier
 * doublon et sa ligne, celle de la seconde occurrence.
 */
object DuplicateKeyProbe {

    data class Duplicate(val key: String, val line: Int)

    fun firstDuplicate(text: String): Duplicate? {
        val scopes = ArrayDeque<HashSet<String>?>()
        var line = 1
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\n' -> { line++; i++ }
                '"' -> {
                    val (value, end) = readString(text, i + 1)
                    val scope = scopes.lastOrNull()
                    if (scope != null) {
                        var j = end
                        while (j < text.length && text[j].isWhitespace()) j++
                        if (j < text.length && text[j] == ':' && !scope.add(value)) return Duplicate(value, line)
                    }
                    i = end
                }
                '{' -> { scopes.addLast(HashSet()); i++ }
                '[' -> { scopes.addLast(null); i++ }
                '}', ']' -> { scopes.removeLastOrNull(); i++ }
                else -> i++
            }
        }
        return null
    }

    /** La chaîne qui commence à [start] (après le guillemet ouvrant), décodée, et l'index qui suit son guillemet fermant. */
    private fun readString(text: String, start: Int): Pair<String, Int> {
        val out = StringBuilder()
        var i = start
        while (i < text.length) {
            when (val c = text[i]) {
                '"' -> return out.toString() to i + 1
                '\\' -> {
                    when (val escaped = text.getOrNull(i + 1)) {
                        'u' -> { if (i + 6 <= text.length) out.append(text.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                        'n' -> { out.append('\n'); i += 2 }
                        't' -> { out.append('\t'); i += 2 }
                        'r' -> { out.append('\r'); i += 2 }
                        'b' -> { out.append('\b'); i += 2 }
                        'f' -> { out.append('\u000C'); i += 2 }
                        null -> i++
                        else -> { out.append(escaped); i += 2 } // les échappements \" \\ et \/
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString() to text.length
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
        "${e::class.simpleName}$where: ${(decode?.cause ?: e).message?.lineSequence()?.first()?.take(110)}"
    }

    private fun open(text: String, fileName: String, format: StoreFormat): String {
        val path = newStorePath(fileName).also { it.writeText(text) }
        return StoreFactory.createFromConstructor<PermsFile>(path.toString(), format = format, config = noAutoSave).use { describe(it.data) }
    }

    private fun openAll(format: StoreFormat, fileName: String, text: (Variant) -> String): Map<String, String> =
        variants.associate { variant -> variant.label to outcome { open(text(variant), fileName, format) } }

    private fun show(results: Map<String, String>, indent: String = "    ") = results.forEach { (label, result) -> println("$indent%-36s %s".format(label, result)) }

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

    private fun oneLine(path: Path) = path.readText().replace(Regex("\\s+"), " ")

    private fun occurrences(path: Path, needle: String) = Regex(Regex.escape(needle)).findAll(path.readText()).count()

    @Test
    fun `étape 1, JSON par le flux, un doublon à la racine, imbriqué, dans une map, dans un tableau en Set, le dernier gagne sans un mot`() {
        var results = emptyMap<String, String>()
        val log = captureLog { results = openAll(JsonFormat(), "perms.json") { it.json } }
        show(results)
        println("    lignes WARN au log pendant ces quatre ouvertures : ${log.count { "WARN" in it }} (les ${log.size} lignes sont les « Store closed »)")
        val tree = Json.parseToJsonElement(root.json).jsonObject.getValue("version").jsonPrimitive.content
        println("    le doublon à la racine par l'arbre (Json.parseToJsonElement) : version = $tree")
        check(results.values.all { it.startsWith("OK") } && log.none { "WARN" in it } && tree == "2")
        check("version=2" in results.getValue(root.label) && "defaults.priority=2" in results.getValue(nested.label))
        check("groups=[vip->20]" in results.getValue(map.label) && "admins=[steve]" in results.getValue(set.label))
    }

    @Test
    fun `étape 2, les mêmes doublons en JSON5, où li_songe json5 garde aussi le dernier, et en TOML, où tomlkt refuse avec la ligne`() {
        val json5 = openAll(Json5Format(), "perms.json5") { it.json5 }
        val toml = openAll(TomlFormat(), "perms.toml") { it.toml }
        println("    Json5Format")
        show(json5, "        ")
        println("    TomlFormat")
        show(toml, "        ")
        check(json5.values.all { it.startsWith("OK") } && "groups=[vip->20]" in json5.getValue(map.label))
        // La spécification TOML interdit de définir deux fois une clé ou une table : tomlkt lève, et la ligne est celle de la seconde occurrence.
        check(toml.getValue(root.label).startsWith("StoreDecodeException at line 2") && toml.getValue(nested.label).startsWith("StoreDecodeException at line 5"))
        check(toml.getValue(map.label).startsWith("StoreDecodeException at line 7") && toml.getValue(set.label).startsWith("OK"))
    }

    @Test
    fun `étape 3, au rechargement, le fichier édité avec un doublon remplace la mémoire sans un mot, TOML refuse et la mémoire reste intacte`() {
        val cases = listOf(Triple(JsonFormat(), "perms.json", map.json), Triple(Json5Format(), "perms.json5", map.json5), Triple(TomlFormat(), "perms.toml", map.toml))
        val results = mutableMapOf<String, Pair<String, String>>()
        for ((format, fileName, text) in cases) {
            val path = newStorePath(fileName)
            StoreFactory.createFromConstructor<PermsFile>(path.toString(), format = format, config = noAutoSave).use { store ->
                path.writeText(text) // l'admin édite le fichier : « vip » déclaré deux fois
                var result = ""
                val log = captureLog { result = outcome { store.reloadFromFile(); "rechargé" } }
                println("    %-12s %s ; en mémoire : %s ; lignes WARN au log : %d".format(format::class.simpleName, result, describe(store.data), log.count { "WARN" in it }))
                results[format::class.simpleName.toString()] = result to describe(store.data)
            }
        }
        check(results.getValue("JsonFormat").first.startsWith("OK") && "groups=[vip->20]" in results.getValue("JsonFormat").second)
        check(results.getValue("Json5Format").first.startsWith("OK") && "groups=[vip->20]" in results.getValue("Json5Format").second)
        check(results.getValue("TomlFormat").first.startsWith("StoreDecodeException at line 7") && "groups=[]" in results.getValue("TomlFormat").second)
    }

    @Test
    fun `étape 4, validateFile ne voit rien, et la sauvegarde d'après, JSON réécrit une seule clé, JSON5 préserve le doublon`() {
        val jsonPath = newStorePath("perms.json").also { it.writeText(map.json) }
        val jsonResult = JsonFormat().validateFile(jsonPath, PermsFileValidator())
        println("    validateFile sur le JSON à deux « vip »       : $jsonResult")
        StoreFactory.createFromConstructor<PermsFile>(jsonPath.toString(), config = noAutoSave).use { store -> store.set(PermsFile::version, 2); store.saveImmediate() }
        println("    JSON après une sauvegarde                     : ${occurrences(jsonPath, "\"vip\":")} clé « vip », ${oneLine(jsonPath)}")

        val json5Path = newStorePath("perms.json5").also { it.writeText(map.json5) }
        val json5Result = Json5Format().validateFile(json5Path, PermsFileValidator())
        println("    validateFile sur le JSON5 à deux « vip »      : $json5Result")
        StoreFactory.createFromConstructor<PermsFile>(json5Path.toString(), config = noAutoSave).use { store -> store.set(PermsFile::version, 2); store.saveImmediate() }
        println("    JSON5 après une sauvegarde préservante        : ${occurrences(json5Path, "vip:")} clés « vip », ${oneLine(json5Path)}")
        val reopened = StoreFactory.createFromConstructor<PermsFile>(json5Path.toString(), config = noAutoSave).use { describe(it.data) }
        println("    JSON5 relu par un store neuf                  : $reopened")
        check(jsonResult == ValidationResult.Success && json5Result == ValidationResult.Success)
        check(occurrences(jsonPath, "\"vip\":") == 1 && occurrences(json5Path, "vip:") == 2 && "groups=[vip->20]" in reopened)
    }

    @Test
    fun `étape 5, le coût d'une passe sur le texte, sur 5 000 joueurs, et ses pièges`() {
        val format = JsonFormat()
        val path = newStorePath("roster.json")
        format.encodeToPath(Roster.serializer(), Roster((1..5_000).map { Player("player$it", mutableListOf("base", "mine", "farm")) }.toMutableList()), path)
        val text = path.readText()
        val runs = 20
        fun ms(block: () -> Unit): Double { repeat(5) { block() }; return measureNanoTime { repeat(runs) { block() } } / runs / 1e6 }
        println("    le fichier : ${text.length / 1024} Ko, ${text.lines().size} lignes, doublon trouvé : ${DuplicateKeyProbe.firstDuplicate(text)}")
        println("    JsonFormat.decodeFromPath, le décodage seul : %5.2f ms".format(ms { format.decodeFromPath(Roster.serializer(), path) }))
        println("    la passe sur le texte déjà en mémoire       : %5.2f ms".format(ms { DuplicateKeyProbe.firstDuplicate(text) }))
        println("    readText puis la passe                      : %5.2f ms".format(ms { DuplicateKeyProbe.firstDuplicate(path.readText()) }))
        println("    Json5.parseToJsonElement sur le même texte  : %5.2f ms".format(ms { Json5.parseToJsonElement(text) }))
        println("    Json5.parseToDocument, l'AST aux doublons   : %5.2f ms".format(ms { Json5.parseToDocument(text) }))

        val escaped = """{"ab": 1, "ab": 2}"""
        val oneLine = """{"a": 1, "a": 2}"""
        val siblings = """{"a": {"x": 1}, "b": {"x": 2}}"""
        val elements = """[{"a": 1}, {"a": 2}]"""
        val inString = """{"a": "x: 1, \"a\": 2", "b": 1}"""
        val cases = listOf(
            "une clé échappée, le même nom une fois décodé" to escaped,
            "deux clés sur une seule ligne" to oneLine,
            "la même clé dans deux objets" to siblings,
            "la même clé dans deux éléments d'un tableau" to elements,
            "un faux doublon dans une chaîne" to inString,
            "le doublon de la map, à sa ligne" to map.json,
        )
        for ((label, json) in cases) {
            val found = DuplicateKeyProbe.firstDuplicate(json)?.let { "doublon « ${it.key} » à la ligne ${it.line}" } ?: "aucun doublon"
            println("    %-48s %s".format(label, found))
        }
        check(DuplicateKeyProbe.firstDuplicate(escaped) == DuplicateKeyProbe.Duplicate("ab", 1) && DuplicateKeyProbe.firstDuplicate(oneLine) == DuplicateKeyProbe.Duplicate("a", 1))
        check(listOf(siblings, elements, inString).all { DuplicateKeyProbe.firstDuplicate(it) == null })
        check(DuplicateKeyProbe.firstDuplicate(map.json) == DuplicateKeyProbe.Duplicate("vip", 6) && DuplicateKeyProbe.firstDuplicate(text) == null)
    }
}
